"""
事件检测器 —— 参考实现（Python）
本文件是算法的"规格书 + 基准"，Kotlin 版本必须与它行为一致。

核心设计（与方案 v3.0 对齐）：
  · 100ms 子帧 → 能量包络（10Hz）。所有计时判定都用它，时间分辨率 0.1s。
  · 噪声底 = 20 秒滑窗的低分位数（P15），每秒重算一次 → 对鼾声/电视噪声不敏感。
  · 峰检测用 1 秒延迟的批处理（拿到完整前后文再判定），避免上升沿误判。
  · 静默段（无呼吸声）≥10s 且被一次呼吸结束 → 记为一次疑似呼吸暂停事件（临床定义）。
  · 纯 DSP，无深度模型依赖，保证可移植到 Kotlin 且行为可复现。

流式接口：process_chunk() 可喂任意长度（真实场景每 100ms 一块）。
"""
import numpy as np
from collections import deque

SR = 16000
SUB_S = 0.1                 # 子帧 100ms
SUB_N = int(SUB_S * SR)     # 1600 samples


# ---------------------------------------------------------------- 滤波器

class Biquad:
    """直接 II 型转置结构，单节二阶。Kotlin 端逐行对应实现。"""

    __slots__ = ("b0", "b1", "b2", "a1", "a2", "z1", "z2")

    def __init__(self, b0, b1, b2, a0, a1, a2):
        self.b0, self.b1, self.b2 = b0 / a0, b1 / a0, b2 / a0
        self.a1, self.a2 = a1 / a0, a2 / a0
        self.z1 = 0.0
        self.z2 = 0.0

    @staticmethod
    def lowpass(f0, fs, q=0.7071067811865476):
        w0 = 2.0 * np.pi * f0 / fs
        cw, al = np.cos(w0), np.sin(w0) / (2.0 * q)
        a0 = 1.0 + al
        return Biquad((1 - cw) / 2, 1 - cw, (1 - cw) / 2, a0, -2 * cw, 1 - al)

    @staticmethod
    def highpass(f0, fs, q=0.7071067811865476):
        w0 = 2.0 * np.pi * f0 / fs
        cw, al = np.cos(w0), np.sin(w0) / (2.0 * q)
        a0 = 1.0 + al
        return Biquad((1 + cw) / 2, -(1 + cw), (1 + cw) / 2, a0, -2 * cw, 1 - al)

    def process(self, x):
        y = self.b0 * x + self.z1
        self.z1 = self.b1 * x - self.a1 * y + self.z2
        self.z2 = self.b2 * x - self.a2 * y
        return y


class BandFilter:
    """
    高通 100Hz + 低通 4000Hz 级联（Q=1/sqrt(2)，近似 Butterworth 2 阶）。
    去掉空调/桌面震动的低频与麦克风高频噪声。
    之所以不用 scipy.butter：系数不便在 Kotlin 里 1:1 复刻，
    RBJ 形式两边能逐行对上，改参数也不会两边跑偏。
    """

    def __init__(self, f_lo=100.0, f_hi=4000.0, sr=SR):
        self.hp = Biquad.highpass(f_lo, sr)
        self.lp = Biquad.lowpass(f_hi, sr)
        self._buf = np.empty(0, dtype=np.float64)

    def coeffs(self):
        return ((self.hp.b0, self.hp.b1, self.hp.b2, 1.0, self.hp.a1, self.hp.a2),
                (self.lp.b0, self.lp.b1, self.lp.b2, 1.0, self.lp.a1, self.lp.a2))

    def __call__(self, x):
        # scipy.lfilter 实现的就是同一条差分方程 y[n]=b0x[n]+b1x[n-1]+b2x[n-2]
        #                                 -a1y[n-1]-a2y[n-2]，与上面的 Biquad.process 逐位等价，
        # 只是为了 Python 侧的向量化速度（Kotlin 端直接用 Biquad.process 的循环）。
        from scipy.signal import lfilter
        a = np.asarray(x, dtype=np.float64)
        if len(a) == 0:
            return np.zeros(0, dtype=np.float32)
        (hb0, hb1, hb2, _, ha1, ha2), (lb0, lb1, lb2, _, la1, la2) = self.coeffs()
        stage = lfilter([hb0, hb1, hb2], [1.0, ha1, ha2], a)
        out = lfilter([lb0, lb1, lb2], [1.0, la1, la2], stage)
        return out.astype(np.float32)


def biquad_bandpass(x, f_lo=100.0, f_hi=4000.0, sr=SR):
    """便捷入口（单次使用；跨块滤波请显式持有 BandFilter 以保持状态连续）"""
    return BandFilter(f_lo, f_hi, sr)(x)


# ---------------------------------------------------------------- 噪声底

class NoiseFloor:
    """
    噪声底估计：最近 WINDOW_S 秒的 dB 值的 P15 分位数，每 RECALC_EVERY_S 秒重算一次。
    选低分位数而非均值：保证"只要最近有安静期，噪声底就贴住安静期"。
    窗口内全是鼾声时噪声底被抬高 → SNR 下降 → 质量门限会拦住，属预期降级行为。
    """

    WINDOW_S = 20.0
    PERCENTILE = 15.0
    RECALC_EVERY_S = 1.0
    WARMUP_S = 5.0

    def __init__(self):
        self.buf = deque(maxlen=int(self.WINDOW_S / SUB_S))
        self.level = None
        self._since = 0.0

    def push(self, db, dt_s=SUB_S):
        self.buf.append(float(db))
        self._since += dt_s
        warm = int(self.WARMUP_S / SUB_S)
        if len(self.buf) < warm:
            self.level = min(self.buf) if self.buf else float(db)
        elif self._since >= self.RECALC_EVERY_S:
            self.level = float(np.percentile(np.fromiter(self.buf, dtype=np.float32, count=len(self.buf)),
                                            self.PERCENTILE))
            self._since = 0.0
        return self.level


def loudness_contrast_db(all_snr):
    """
    响度对比度：最响 10% 帧 与 中位数帧 的 dB 差。

    ⚠️ 这个量**不能**回答"录音里有没有可用的鼾声信号"，只报告、不参与质量门限。
      原因是实测出来的：分组若由信噪比阈值定义，就是循环论证；
      改成按响度排序取头部后，APSAA 上量到 3.7~10 dB，但那份录音的音频包络与
      临床鼾声标注相关系数只有 -0.012 —— 那些"响的时刻"是关门声和脚步声，不是鼾声。
      单夜无标注数据无法自证"响的是什么"。
      要真正判断，只能靠频谱形状分类器（有标注训练），或直接与家用监测仪配对实测。
    """
    if len(all_snr) < 100:
        return 0.0
    a = np.sort(np.asarray(all_snr, dtype=np.float64))
    k = max(10, int(len(a) * 0.10))
    top = a[-k:]
    base = a[len(a) // 2 - k // 2: len(a) // 2 + k // 2 + 1]
    return float(top.mean() - base.mean())


# ---------------------------------------------------------------- 主检测

class EventDetector:
    # 迟滞阈值（相对噪声底）
    SNR_ON_DB = 8.0           # 高于此 → 有呼吸/鼾声
    SNR_OFF_DB = 5.0          # 低于此 → 静默
    # 不对称去抖动：安静房间的纯噪声 RMS 有 ±3dB 波动，单帧越阈是常态；
    # 但恢复性喘息本身可能只有 0.2s。两个方向的要求必须不同。
    DEBOUNCE_ON_S = 0.2      # 连续多久算"有声"（要短，否则丢事件）
    DEBOUNCE_OFF_S = 0.6     # 连续多久算"静默"（要长，否则静默被切碎）
    # 峰检测
    PEAK_CTX_S = 1.0          # 前后文长度
    PEAK_PROM_MIN_DB = 5.0    # 峰相对前文基线的最小突出度
    PEAK_MIN_GAP_S = 1.0      # 两峰最小间隔
    # 事件判定
    APNEA_MIN_S = 10.0        # 临床定义：≥10s
    # 上限：临床上门诊可解释的呼吸暂停极少超过 60s。
    # 超过 = 人起身/翻出麦克风范围/录音中断/恒定环境噪声盖住了鼾声 —— 一律不算事件。
    GAP_MAX_S = 60.0
    EVENT_MERGE_S = 15.0      # 相邻事件间隔小于此则合并
    # 质量门槛
    MIN_RECORD_S = 240.0
    MIN_ACTIVE_SNR_DB = 8.0   # 有声帧的 SNR 中位数下限（环境太差则不出结论）
    MIN_ACTIVE_FRAC = 0.02    # 有声帧占比下限
    MIN_PEAKS = 30


    def __init__(self, sample_rate: int = SR, **kw):
        for k, v in kw.items():
            if not hasattr(type(self), k):
                raise KeyError(f"unknown param {k}")
            setattr(self, k, v)
        self.sample_rate = int(sample_rate)
        self._sub_n = int(round(SUB_S * self.sample_rate))
        self.nf = NoiseFloor()
        self._reset_state()

    def _reset_state(self):
        self._t = 0.0
        self._active = False
        self._sil_start = None
        self._on_run = 0.0            # 连续"有声"时长
        self._off_run = 0.0           # 连续"静默"时长
        self._on_cross = 0.0          # 本轮"有声"开始时的阈值穿越时刻
        self._off_cross = 0.0         # 本轮"静默"开始时的阈值穿越时刻
        # 显式历史缓冲 + 判定游标。必须保留"峰之前"的上下文，
        # 所以不能用 deque 弹出式消费。
        self._hist = []          # [(t, db, snr), ...]
        self._hist_len = 0
        self._hist_base = 0
        self._pk_i = 0           # 下一个待判定为"峰"的帧下标
        self._last_peak_t = -1e9
        self._rem = None
        self.snr_active = []             # 有声帧的 SNR
        self.snr_all = []              # 全部帧的 SNR，算可分性用           # 静默帧的 SNR
        self.peaks = []                  # (t, db, prom)
        self.silences = []               # 所有 ≥10s 的静默段（待确认）
        self.events = []

    # ---- 峰检测（PEAK_CTX_S 延迟批处理）---------------------------
    def _drain_peaks(self):
        """
        对下标 i（已具备 [i-n, i+n] 完整前后文）的帧判定是否为峰：
          · center 必须是不含自身的 [i-n, i+n] 窗口内的最大值
          · prominence = center - P25(前 n 帧)   ← 用"峰之前"的安静期当基线，
            这正是鼾声/喘息能被识别出来的原因（峰之前刚经历过安静）
          · snr 必须过 SNR_ON
          · 与上一个峰间隔 ≥ PEAK_MIN_GAP_S
        """
        n = int(self.PEAK_CTX_S / SUB_S)
        h = self._hist
        while self._pk_i + n < len(h):
            i = self._pk_i
            self._pk_i += 1
            if i - n < 0:
                continue
            t0, center, s0 = h[i]
            if s0 < self.SNR_ON_DB:
                continue
            before = [h[k][1] for k in range(i - n, i)]
            after = [h[k][1] for k in range(i, i + n + 1)]
            if center < max(before) or center < max(after):
                continue
            ctx = float(np.percentile(np.array(before, dtype=np.float32), 25))
            prom = center - ctx
            if prom >= self.PEAK_PROM_MIN_DB and (t0 - self._last_peak_t) >= self.PEAK_MIN_GAP_S:
                self.peaks.append((float(t0), float(center), float(prom)))
                self._last_peak_t = t0
        # 回收已不可能再被引用的旧帧（游标必须同步回退，否则下标越界后永不再判定）
        keep_from = max(0, self._pk_i - 2 * n - 2)
        if keep_from > 64:
            del self._hist[:keep_from]
            self._pk_i -= keep_from
        self._hist_len = len(self._hist)
        self._hist_base = keep_from

    # ---- 静默段 -----------------------------------------------------
    def _close_silence(self, end_t):
        start = self._sil_start
        self._sil_start = None
        dur = end_t - start
        if dur < self.APNEA_MIN_S or dur > self.GAP_MAX_S:
            return
        # 与前一个事件太近则合并（避免同一次憋气被切成两段）
        if self.silences and start - (self.silences[-1]["start"] + self.silences[-1]["silence"]) < self.EVENT_MERGE_S:
            prev = self.silences[-1]
            prev["silence"] = end_t - prev["start"]
            return
        self.silences.append({"start": float(start), "silence": float(dur)})

    EVENT_CTX_S = 4.0        # 静默结束后多久内的峰算"结束它的呼吸"

    # 恢复性喘息必须是"瞬态"而不是"台阶"。
    # 一次吸气 1~2 秒内回到底噪；电视/空调突然开起来则会持续几十秒。
    # 不加这条，环境噪声的一起一落会被当成"静默 + 恢复性喘息"，凭空造出事件。
    BREATH_DECAY_S = 3.0     # 峰后多久内应回落到接近底噪
    BREATH_DECAY_DB = 6.0    # 至少要回落这么多 dB

    def _confirm_silences(self, t_now, force=False):
        """
        静默段已被一次呼吸峰结束 → 成为事件。

        关键：峰检测有 PEAK_CTX_S 的前瞻延迟，所以"能用于确认的最晚峰时刻"
        是 end + EVENT_CTX_S，而它要到 end + EVENT_CTX_S + PEAK_CTX_S 才进列表。
        等待时间必须覆盖这个延迟，否则会在峰到达之前就把静默段丢掉。
        """
        wait = self.EVENT_CTX_S + self.PEAK_CTX_S
        ready, keep = [], []
        for s in self.silences:
            end = s["start"] + s["silence"]
            if not force and t_now < end + wait:
                keep.append(s)
                continue
            hit = None
            for (pt, pv, prom) in self.peaks:
                if end <= pt <= end + self.EVENT_CTX_S:
                    if self._looks_like_breath(pt, pv):
                        hit = (pt, pv, prom)
                    break
            if hit is not None:
                ev = dict(s)
                ev["recovery"] = {"time": hit[0], "db": hit[1], "prominence": hit[2]}
                ev["end"] = end
                ready.append(ev)
            # 无后续呼吸 → 普通安静（翻身、起夜、环境安静），丢弃
        self.silences = keep
        self.events.extend(ready)
        return ready

    def _looks_like_breath(self, t_peak, peak_db):
        """
        峰后 BREATH_DECAY_S 内是否回落到接近底噪。
        呼吸=瞬态（回到底噪），环境噪声起始=台阶（持续响）→ 只有前者算恢复性喘息。
        """
        base = self._hist_base
        i0 = int(round((t_peak + 0.5) / SUB_S)) - base
        i1 = int(round((t_peak + self.BREATH_DECAY_S) / SUB_S)) - base
        if i0 < 0 or i1 >= len(self._hist):
            return True    # 历史不够，交给质量分去拦，不在这里误杀
        lo = min(self._hist[k][1] for k in range(i0, i1 + 1))
        return (peak_db - lo) >= self.BREATH_DECAY_DB

    # ---- 主入口 -----------------------------------------------------
    def process_chunk(self, x, t0_s=None):
        """x: float32[-1,1]；t0_s 本块在整夜中的起点（None=接续）。返回新确认事件。"""
        if x is None or len(x) == 0:
            return []
        t0 = self._t if t0_s is None else float(t0_s)
        buf = np.asarray(x, dtype=np.float32)
        if self._rem is not None and len(self._rem):
            buf = np.concatenate([self._rem, buf])
        sub_n = self._sub_n
        n_sub = len(buf) // sub_n
        self._rem = buf[n_sub * sub_n:]
        if n_sub == 0:
            return []

        t = t0
        snr_prev = None
        t_prev = None
        for i in range(n_sub):
            seg = buf[i * sub_n:(i + 1) * sub_n].astype(np.float64)
            db = 20.0 * np.log10(np.sqrt((seg ** 2).mean()) + 1e-12)
            nf = self.nf.push(db)
            snr = db - nf

            self._hist.append((t, db, snr))
            self._drain_peaks()

            # --- 阈值穿越时刻：线性插值定位到子帧以内 ---
            # 去抖只影响"何时切换状态"，绝不能让计时跟着偏移 0.5s，
            # 所以穿越时刻在"运行开始"的那一刻就先记下来，切换时直接取用。
            if snr_prev is None:
                x_on = x_off = t
            else:
                d = snr_prev - snr
                if abs(d) < 1e-9:
                    x_on = x_off = t
                else:
                    f_on = (snr_prev - self.SNR_ON_DB) / d
                    f_off = (snr_prev - self.SNR_OFF_DB) / d
                    x_on = t_prev + SUB_S * min(1.0, max(0.0, f_on))
                    x_off = t_prev + SUB_S * min(1.0, max(0.0, f_off))

            # --- 不对称去抖动的迟滞状态机 ---
            # 转"有声"要求短（0.2s）：恢复性喘息本身可能只有 0.2~0.5s，
            #   要求久了会把整个事件判丢（实测丢 38% 事件）
            # 转"静默"要求长（0.6s）：安静房间的纯噪声 RMS 有 ±3dB 波动，
            #   要求短了会把一段 45s 的静默切碎，同样丢事件
            if snr >= self.SNR_ON_DB:
                if self._on_run == 0.0:
                    self._on_cross = x_on
                self._on_run += SUB_S
            else:
                self._on_run = 0.0
            if snr < self.SNR_OFF_DB:
                if self._off_run == 0.0:
                    self._off_cross = x_off
                self._off_run += SUB_S
            else:
                self._off_run = 0.0

            if not self._active and self._on_run >= self.DEBOUNCE_ON_S:
                self._active = True
                if self._sil_start is not None:
                    self._close_silence(self._on_cross)
            elif self._active and self._off_run >= self.DEBOUNCE_OFF_S:
                self._active = False
                if self._sil_start is None:
                    self._sil_start = self._off_cross
            self.snr_all.append(snr)
            if self._active:
                self.snr_active.append(snr)

            self._confirm_silences(t)
            snr_prev, t_prev = snr, t
            t += SUB_S

        self._t = t
        return []

    # ---- 收尾 ------------------------------------------------------
    def finalize(self):
        self._hist_len = len(self._hist)
        if self._sil_start is not None:
            self._close_silence(self._t)
        self._confirm_silences(self._t, force=True)

        sa = np.array(self.snr_active) if self.snr_active else np.zeros(1)
        quality = {
            "duration_s": float(self._t),
            "active_snr_median": float(np.median(sa)),
            "active_frac": float(len(self.snr_active) * SUB_S / max(1e-9, self._t)),
            "peak_count": len(self.peaks),
            "silence_count": len(self.events),
            "loudness_contrast_db": loudness_contrast_db(self.snr_all),
        }
        quality["ok"] = (
            self._t >= self.MIN_RECORD_S
            and quality["active_snr_median"] >= self.MIN_ACTIVE_SNR_DB
            and quality["active_frac"] >= self.MIN_ACTIVE_FRAC
            and len(self.peaks) >= self.MIN_PEAKS
        )
        return list(self.events), quality

    def reset(self):
        self._reset_state()
        self._rem = None
