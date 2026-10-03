package com.sleepsentry.dsp

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * 事件检测器 —— 生产实现（Kotlin）。
 *
 * 与 Python 参考实现 dsp/detector.py 行为一致，两侧的阈值必须同步修改并各自跑金标准测试。
 * 详见 dsp/GOLD_STANDARD.md。
 *
 * 流式使用：processChunk() 可喂任意长度的块（真实场景是 AudioRecord 每 100ms 交一块）。
 * 内部按 100ms 子帧对齐，跨块残余会保留到下一块，保证时间轴不漂移。
 */
class EventDetector(
    private val sampleRate: Int = DspConfig.SAMPLE_RATE,
    // 阈值集中在此，便于按机型/场景调参；默认值即金标准测试通过的那一组
    private val snrOnDb: Double = DspConfig.SNR_ON_DB,
    private val snrOffDb: Double = DspConfig.SNR_OFF_DB,
    private val debounceOnS: Double = DspConfig.DEBOUNCE_ON_S,
    private val debounceOffS: Double = DspConfig.DEBOUNCE_OFF_S,
    private val apneaMinS: Double = DspConfig.APNEA_MIN_S,
    private val gapMaxS: Double = DspConfig.GAP_MAX_S,
    private val eventMergeS: Double = DspConfig.EVENT_MERGE_S,
    private val eventCtxS: Double = DspConfig.EVENT_CTX_S,
    private val peakCtxS: Double = DspConfig.PEAK_CTX_S,
    private val peakPromMinDb: Double = DspConfig.PEAK_PROM_MIN_DB,
    private val peakMinGapS: Double = DspConfig.PEAK_MIN_GAP_S
) {

    private class Silence(var start: Double, var silence: Double)

    private val subN = (sampleRate * DspConfig.SUB_S).toInt()
    private val nCtx = (peakCtxS / DspConfig.SUB_S).toInt()
    private val confirmWaitS = eventCtxS + peakCtxS

    private val nf = NoiseFloor()
    private val hist = FrameWindow()

    private var t = 0.0
    private var active = false
    private var silStart = Double.NaN
    private var onRun = 0.0
    private var offRun = 0.0
    private var onCross = 0.0
    private var offCross = 0.0
    private var pkI = 0L
    private var lastPeakT = Double.NEGATIVE_INFINITY
    private var drained = 0                     // takeNewEvents 已取走的位置
    private var rem = FloatArray(0)

    private val peaks = ArrayList<BreathPeak>()
    private val silences = ArrayList<Silence>()
    private val events = ArrayList<BreathingEvent>()

    // 质量统计：SNR 直方图（0.25dB 一格），避免为一整夜的有声帧占内存
    private val histMin = -40.0
    private val histBin = 0.25
    private val histBins = IntArray(512)
    private var activeSnrCount = 0
    private var activeSnrSum = 0.0

    val recordedSeconds: Double get() = t
    val peakCount: Int get() = peaks.size
    fun confirmedEvents(): List<BreathingEvent> = events.toList()

    /** 取走"自上次调用以来新确认"的事件。采集线程用它决定何时切出音频落盘。 */
    fun takeNewEvents(): List<BreathingEvent> {
        if (drained >= events.size) return emptyList()
        val out = events.subList(drained, events.size).toList()
        drained = events.size
        return out
    }

    // ---------------------------------------------------------------- 主入口

    /** 喂入一段已带通滤波的 [-1,1] 浮点样本。返回本块新确认的事件。 */
    fun processChunk(buf: FloatArray, t0Sec: Double? = null): List<BreathingEvent> {
        if (buf.isEmpty()) return emptyList()
        val t0 = t0Sec ?: t

        // 与上次残余拼接，保证子帧对齐
        var input = buf
        if (rem.isNotEmpty()) {
            input = FloatArray(rem.size + buf.size)
            System.arraycopy(rem, 0, input, 0, rem.size)
            System.arraycopy(buf, 0, input, rem.size, buf.size)
        }
        val nSub = input.size / subN
        rem = input.copyOfRange(nSub * subN, input.size)
        if (nSub == 0) return emptyList()

        val before = events.size
        var cur = t0
        var snrPrev = Double.NaN
        var tPrev = t0
        val fbuf = FloatArray(subN)

        for (i in 0 until nSub) {
            System.arraycopy(input, i * subN, fbuf, 0, subN)
            val db = subFrameDb(fbuf, 0, subN)
            val level = nf.push(db)
            val snr = db - level

            hist.push(db, snr)
            drainPeaks()

            // 阈值穿越时刻：线性插值定位到子帧以内。
            // 去抖只决定"何时切状态"，不能让计时跟着偏移，所以穿越时刻在
            // "运行开始"的那一刻就先记下来，切换时直接取用。
            var xOn = cur
            var xOff = cur
            if (!snrPrev.isNaN()) {
                val d = snrPrev - snr
                if (kotlin.math.abs(d) > 1e-9) {
                    xOn = tPrev + DspConfig.SUB_S * clamp01((snrPrev - snrOnDb) / d)
                    xOff = tPrev + DspConfig.SUB_S * clamp01((snrPrev - snrOffDb) / d)
                }
            }

            // 不对称去抖动的迟滞状态机：
            //  转"有声"要求短(0.2s) —— 恢复性喘息本身可能只有 0.2~0.5s
            //  转"静默"要求长(0.6s) —— 安静房间纯噪声 ±3dB 波动会顶穿阈值
            if (snr >= snrOnDb) {
                if (onRun == 0.0) onCross = xOn
                onRun += DspConfig.SUB_S
            } else onRun = 0.0
            if (snr < snrOffDb) {
                if (offRun == 0.0) offCross = xOff
                offRun += DspConfig.SUB_S
            } else offRun = 0.0

            if (!active && onRun >= debounceOnS) {
                active = true
                if (!silStart.isNaN()) closeSilence(onCross)
            } else if (active && offRun >= debounceOffS) {
                active = false
                if (silStart.isNaN()) silStart = offCross
            }
            if (active) {
                activeSnrCount++
                activeSnrSum += snr
                val bin = (((snr - histMin) / histBin).toInt()).coerceIn(0, histBins.size - 1)
                histBins[bin]++
            }

            confirmSilences(cur)
            snrPrev = snr
            tPrev = cur
            cur += DspConfig.SUB_S
        }
        t = cur
        return if (events.size > before) events.subList(before, events.size).toList() else emptyList()
    }

    /** 便捷入口：16bit PCM → 滤波 → 检测 */
    fun processPcm16(pcm: ShortArray, count: Int = pcm.size, t0Sec: Double? = null): List<BreathingEvent> {
        val f = FloatArray(count) { pcm[it] / 32768.0f }
        val filtered = BandFilter(sampleRate = sampleRate).filter(f, count)
        return processChunk(filtered, t0Sec)
    }

    // ---------------------------------------------------------------- 峰检测

    /**
     * 判定下标 i（已具备 [i-n, i+n] 完整前后文）的帧是否为呼吸/鼾声峰：
     *  · center 必须是不含自身的窗口最大值
     *  · 突出度 = center − P25(峰之前 n 帧) —— 用"峰之前的安静期"当基线，
     *    这正是鼾声/喘息能被识别出来的原因
     *  · 与上一个峰间隔 ≥ PEAK_MIN_GAP_S
     */
    private fun drainPeaks() {
        while (pkI + nCtx < hist.size) {
            val i = pkI
            pkI++
            if (i - nCtx < 0) continue
            if (hist.snrAt(i) < snrOnDb) continue
            if (hist.fillBefore(i, nCtx) < 0) continue
            if (hist.fillAfter(i, nCtx) < 0) continue
            val center = hist.dbAt(i)
            if (center < hist.maxBefore(i, nCtx)) continue
            if (center < hist.maxAfter(i, nCtx)) continue
            val ctx = hist.beforePercentile25(i, nCtx)
            val prom = center - ctx
            val timeSec = (i + 1) * DspConfig.SUB_S
            if (prom >= peakPromMinDb && (timeSec - lastPeakT) >= peakMinGapS) {
                peaks.add(BreathPeak(timeSec, center.toDouble(), prom))
                lastPeakT = timeSec
            }
        }
    }

    // ---------------------------------------------------------------- 静默段

    private fun closeSilence(endT: Double) {
        val start = silStart
        silStart = Double.NaN
        val dur = endT - start
        if (dur < apneaMinS || dur > gapMaxS) return
        val last = silences.lastOrNull()
        if (last != null && start - (last.start + last.silence) < eventMergeS) {
            last.silence = endT - last.start
            return
        }
        silences.add(Silence(start, dur))
    }

    /**
     * 静默段是否已成为事件。
     *
     * 关键：峰检测有 PEAK_CTX_S 的前瞻延迟，"能用于确认的最晚峰时刻"是 end+eventCtxS，
     * 而它要到 end+eventCtxS+PEAK_CTX_S 才进列表。等待时间必须覆盖这个延迟，
     * 否则会在峰到达之前就把静默段丢掉（实测会丢 38% 的事件）。
     */
    private fun confirmSilences(tNow: Double, force: Boolean = false) {
        if (silences.isEmpty()) return
        var write = 0
        forEach@ for (k in silences.indices) {
            val s = silences[k]
            val end = s.start + s.silence
            if (!force && tNow < end + confirmWaitS) {
                silences[write++] = s
                continue
            }
            // peaks 按时间递增，用二分找到第一块窗口，避免每帧全表扫描
            val hit = findPeakInWindow(end, end + eventCtxS)
            if (hit != null && !looksLikeBreath(hit)) {
                // 找到了峰，但它不是"一次呼吸"（多半是环境噪声的起始台阶）
                continue@forEach
            }
            if (hit != null) {
                events.add(
                    BreathingEvent(
                        startSec = s.start,
                        silenceSec = s.silence,
                        endSec = end,
                        recoverySec = hit.timeSec,
                        recoveryProminenceDb = hit.prominenceDb
                    )
                )
            }
            // 无后续呼吸 → 普通安静（翻身、起夜、环境安静），丢弃
        }
        while (silences.size > write) silences.removeAt(silences.size - 1)
    }

    /**
     * 峰后 BREATH_DECAY_S 内是否回落到接近底噪。
     * 呼吸=瞬态（回到底噪），环境噪声起始=台阶（持续响）→ 只有前者算恢复性喘息。
     * 历史已被环形缓冲覆盖时返回 true，交给质量分去拦，不在这里误杀。
     */
    private fun looksLikeBreath(p: BreathPeak): Boolean {
        val i0 = Math.round((p.timeSec + 0.5) / DspConfig.SUB_S)
        val i1 = Math.round((p.timeSec + DspConfig.BREATH_DECAY_S) / DspConfig.SUB_S)
        if (!hist.inRing(i0) || !hist.inRing(i1)) return true
        var lo = Float.MAX_VALUE
        for (i in i0..i1) lo = minOf(lo, hist.dbAt(i))
        return (p.db - lo) >= DspConfig.BREATH_DECAY_DB
    }

    private fun findPeakInWindow(tFrom: Double, tTo: Double): BreathPeak? {
        var lo = 0
        var hi = peaks.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (peaks[mid].timeSec < tFrom) lo = mid + 1 else hi = mid
        }
        var i = lo
        while (i < peaks.size && peaks[i].timeSec <= tTo) {
            return peaks[i]
        }
        return null
    }

    // ---------------------------------------------------------------- 收尾

    /** 整夜结束：闭合未结束的静默段、flush 待确认事件、算质量分 */
    fun finalize(): Pair<List<BreathingEvent>, NightQuality> {
        if (!silStart.isNaN()) closeSilence(t)
        confirmSilences(t + confirmWaitS + 1.0, force = true)

        val median = snrMedian()
        val activeFrac = if (t > 0) activeSnrCount * DspConfig.SUB_S / t else 0.0
        val quality = NightQuality(
            durationSec = t,
            activeSnrMedianDb = median,
            activeFraction = activeFrac,
            peakCount = peaks.size,
            ok = t >= DspConfig.MIN_RECORD_S &&
                median >= DspConfig.MIN_ACTIVE_SNR_DB &&
                activeFrac >= DspConfig.MIN_ACTIVE_FRAC &&
                peaks.size >= DspConfig.MIN_PEAKS
        )
        return events.toList() to quality
    }

    private fun snrMedian(): Double {
        if (activeSnrCount == 0) return 0.0
        val half = activeSnrCount / 2
        var acc = 0
        for (b in histBins.indices) {
            acc += histBins[b]
            if (acc > half) return histMin + b * histBin
        }
        return histMin + (histBins.size - 1) * histBin
    }

    fun reset() {
        t = 0.0; active = false; silStart = Double.NaN
        onRun = 0.0; offRun = 0.0; pkI = 0L; drained = 0
        lastPeakT = Double.NEGATIVE_INFINITY
        rem = FloatArray(0)
        nf.reset(); hist.reset()
        peaks.clear(); silences.clear(); events.clear()
        histBins.fill(0); activeSnrCount = 0; activeSnrSum = 0.0
    }

    private fun clamp01(v: Double) = max(0.0, min(1.0, v))
}