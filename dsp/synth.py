"""
合成睡眠音频生成器 —— 金标准测试用
生成"已知答案"的录音：知道打鼾几次、憋气几次、每次多长、在第几秒。

信号模型（尽量贴近真实）：
  鼾声    低频谐波堆(90-250Hz) + 带噪声，1/f 幅度谱，攻击 0.1s 后指数衰减
  恢复性喘息  比鼾声更高频(300-800Hz)、更响(+6~12dB)、更短(0.4~1.5s)
  环境噪声  风扇低频嗡鸣(100-200Hz) + 宽带嘶声，偶发电视/人声爆发
  呼吸暂停  鼾声完全停止 12~50s，随后一声恢复性喘息
"""
import numpy as np

SR = 16000


def _env_ar(n, attack_s, decay_s):
    """攻击-指数衰减包络"""
    a = max(1, int(attack_s * SR))
    env = np.exp(-np.arange(n) / max(1.0, decay_s * SR))
    env[:a] *= np.linspace(0, 1, a)
    return env.astype(np.float32)


def _fade_out(sig, fade_s=0.12):
    """
    尾部淡出到严格 0。
    没有它，指数衰减的尾巴会在标称时长之外还残留一点点能量，
    使得"真值的结束时刻"与"声学上真正没声了的时刻"不一致，
    计时指标就变成在测合成器的瑕疵而不是检测器的精度。
    """
    n = int(fade_s * SR)
    if n <= 0 or len(sig) < n:
        return sig
    sig[-n:] *= np.linspace(1.0, 0.0, n, dtype=np.float32)
    return sig


def _snore(dur_s, level, rng):
    """一段鼾声：谐波堆 + 噪声"""
    n = int(dur_s * SR)
    t = np.arange(n) / SR
    f0 = rng.uniform(95, 160)
    # 谐波堆，幅度按 1/f 衰减
    sig = np.zeros(n, dtype=np.float32)
    for k in range(1, 9):
        f = f0 * k
        if f > SR / 2:
            break
        amp = 1.0 / k
        sig += amp * np.sin(2 * np.pi * f * t + rng.uniform(0, 6.28))
    # 气流噪声
    sig += rng.normal(0, 0.35, n).astype(np.float32)
    sig *= _env_ar(n, 0.08, dur_s * 0.45)
    sig /= (np.abs(sig).max() + 1e-9)
    sig = _fade_out(sig)
    return (sig * level).astype(np.float32)


def _gasp(dur_s, level, rng):
    """恢复性喘息：更高、更响、更短"""
    n = int(dur_s * SR)
    t = np.arange(n) / SR
    sig = np.zeros(n, dtype=np.float32)
    for f, amp in [(320, 1.0), (560, 0.7), (780, 0.4), (1100, 0.2)]:
        sig += amp * np.sin(2 * np.pi * f * t * (1 + 0.05 * np.sin(2 * np.pi * 3 * t)) + rng.uniform(0, 6.28))
    sig += rng.normal(0, 0.25, n).astype(np.float32)
    sig *= _env_ar(n, 0.05, dur_s * 0.4)
    sig /= (np.abs(sig).max() + 1e-9)
    sig = _fade_out(sig, 0.08)
    return (sig * level).astype(np.float32)


def _noise(n, rng, fan_level=0.006, hiss_level=0.0015):
    """环境底噪：风扇低频嗡鸣 + 宽带嘶声"""
    t = np.arange(n) / SR
    fan = np.zeros(n, dtype=np.float32)
    for f in (100, 150, 200):
        fan += (1.0 / (f / 100)) * np.sin(2 * np.pi * f * t + rng.uniform(0, 6.28))
    fan /= 3.0
    hiss = rng.normal(0, 1, n).astype(np.float32)
    # 嘶声做低通（模拟麦克风频响）
    k = np.ones(6, dtype=np.float32) / 6
    hiss = np.convolve(hiss, k, mode="same")
    return (fan * fan_level + hiss * hiss_level).astype(np.float32)


def generate(duration_s=1800, apnea_count=8, seed=7,
             snore_gap_range=(3.0, 9.0), snore_dur_range=(1.5, 5.0),
             snore_level_range=(0.05, 0.35),
             apnea_silence_range=(12.0, 50.0),
             noise_scale=1.0, tv_bursts=3):
    """
    返回 (audio float32 [duration_s*SR], truth dict)
    truth: {'snores': [(start_s, dur_s)], 'apneas': [(start_s, silence_s)], 'tv': [(start_s, dur_s)]}
    """
    rng = np.random.default_rng(seed)
    n = int(duration_s * SR)
    audio = _noise(n, rng, fan_level=0.006 * noise_scale, hiss_level=0.0015 * noise_scale)

    snores, apneas = [], []

    # 把整夜切成若干"睡眠段"，在段内布鼾声与暂停
    # 注意：真值必须按"声音"记录 —— 鼾声结束的那一刻就已经进入静默了，
    # 鼾声与暂停之间的自然间隙也是静默的一部分。
    settle_end = min(duration_s * 0.03, 300.0)
    t = settle_end
    last_sound_end = settle_end
    apnea_count = min(apnea_count, max(0, int((duration_s - settle_end) // 60)))

    while t < duration_s - 60 and len(apneas) < apnea_count:
        # 正常鼾声串
        for _ in range(int(rng.integers(3, 12))):
            if t >= duration_s - 60:
                break
            dur = float(rng.uniform(*snore_dur_range))
            lvl = float(rng.uniform(*snore_level_range))
            seg = _snore(dur, lvl, rng)
            i = int(t * SR)
            j = min(n, i + len(seg))
            audio[i:j] += seg[: j - i]
            snores.append((t, dur))
            t += dur
            last_sound_end = t
            t += float(rng.uniform(*snore_gap_range))

        # 插入一次呼吸暂停：静默从"最后一个声音结束"开始
        sil = float(rng.uniform(*apnea_silence_range))
        sil_start = last_sound_end
        sil_end = t + sil
        apneas.append((sil_start, sil_end - sil_start))
        t = sil_end
        # 恢复性喘息
        gdur = float(rng.uniform(0.4, 1.5))
        glvl = float(rng.uniform(0.25, 0.6))
        seg = _gasp(gdur, glvl, rng)
        i = int(t * SR)
        j = min(n, i + len(seg))
        audio[i:j] += seg[: j - i]
        t += gdur
        last_sound_end = t
        t += float(rng.uniform(1.0, 4.0))

    # 电视/环境突发噪声（应被判为"非信号"，不能被算成呼吸事件）
    # 必须避开暂停段：电视开着的时候本来就不该判成呼吸暂停，
    # 让它盖在真值上等于自相矛盾，测出来的"漏报"是数据的问题不是算法的问题。
    apneas.sort()
    def overlaps_apnea(a, b, guard=6.0):
        return any(not (b + guard < s or a - guard > s + d) for s, d in apneas)

    def overlaps_tv(a, b, sep=10.0):
        return any(not (b + sep < ts or a - sep > ts + td) for ts, td in tv)

    def overlaps_snores(a, b, min_hits=3):
        """真实房间里的电视是连续背景音，不会围着人的呼吸开关。
        必须落在有鼾声的活动段里，否则会造出"两段电视之间夹一段纯安静"
        这种现实中不存在的场景，测出的误报是数据的问题不是算法的问题。"""
        return sum(1 for (ss, sd) in snores if ss < b and ss + sd > a) >= min_hits

    tv = []
    tries = 0
    while len(tv) < tv_bursts and tries < 500:
        tries += 1
        start = float(rng.uniform(settle_end, max(settle_end + 1, duration_s - 200)))
        dur = float(rng.uniform(30, 120))
        if overlaps_apnea(start, start + dur):
            continue
        if not overlaps_snores(start, start + dur):
            continue
        if overlaps_tv(start, start + dur):
            continue
        i = int(start * SR)
        j = min(n, i + int(dur * SR))
        seg = rng.normal(0, 0.02, j - i).astype(np.float32)
        k = np.ones(20, dtype=np.float32) / 20
        seg = np.convolve(seg, k, mode="same")  # 语音般的低频隆隆声
        # 真实电视/人声是非平稳的（说话有起伏），恒定噪声才是空调/风扇。
        # 这个区别对算法是本质的：呼吸暂停是"安静但稳定"，电视是"有起伏的噪声"。
        seg *= (0.55 + 0.85 * rng.random(int((j - i) / (0.8 * SR)) + 2)
                .repeat(int(0.8 * SR))[: j - i]).astype(np.float32)
        audio[i:j] += seg
        tv.append((start, dur))

    # 归一化到 [-1,1] 的安全范围
    peak = np.abs(audio).max()
    if peak > 0.98:
        audio = audio / peak * 0.98
    return audio.astype(np.float32), {
        "snores": snores,
        "apneas": apneas,
        "tv": tv,
        "duration_s": duration_s,
        "sr": SR,
        "seed": seed,
    }


if __name__ == "__main__":
    import sys, os
    dur = int(sys.argv[1]) if len(sys.argv) > 1 else 600
    out = sys.argv[2] if len(sys.argv) > 2 else "/tmp/synth.wav"
    seed = int(sys.argv[3]) if len(sys.argv) > 3 else 7
    a, truth = generate(dur, apnea_count=max(1, dur // 150), seed=seed)
    import wave
    with wave.open(out, "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR)
        w.writeframes((a * 32767).astype(np.int16).tobytes())
    print(f"wrote {out}  {dur}s  snores={len(truth['snores'])} apneas={len(truth['apneas'])}")