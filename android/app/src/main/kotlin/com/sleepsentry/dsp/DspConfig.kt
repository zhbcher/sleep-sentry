package com.sleepsentry.dsp

import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 全局配置。数值与 Python 参考实现 (dsp/detector.py) 必须保持一致，
 * 任何一侧改动都要同步另一侧并重跑金标准测试。
 */
object DspConfig {
    const val SAMPLE_RATE = 16000

    /** 子帧 100ms —— 所有计时判定的分辨率由它决定 */
    const val SUB_S = 0.1
    val SUB_N: Int = (SAMPLE_RATE * SUB_S).toInt()   // 1600 samples

    // ---- 迟滞阈值（相对自适应噪声底）----
    const val SNR_ON_DB = 8.0       // 高于此 → 有呼吸/鼾声
    const val SNR_OFF_DB = 5.0      // 低于此 → 静默

    /**
     * 不对称去抖动。安静房间的纯噪声 RMS 有 ±3dB 波动，单帧越阈是常态；
     * 但恢复性喘息本身可能只有 0.2s。两个方向的要求必须不同，
     * 设成一样会两头都丢事件（实测对称 0.5s 直接丢掉 38% 事件）。
     */
    const val DEBOUNCE_ON_S = 0.2    // 连续多久算"有声"（要短）
    const val DEBOUNCE_OFF_S = 0.6   // 连续多久算"静默"（要长）

    // ---- 峰检测 ----
    const val PEAK_CTX_S = 1.0       // 前后文长度
    const val PEAK_PROM_MIN_DB = 5.0 // 相对峰前基线的最小突出度
    const val PEAK_MIN_GAP_S = 1.0   // 两峰最小间隔

    // ---- 事件判定 ----
    const val APNEA_MIN_S = 10.0     // 临床定义：≥10s
    /** 上限：门诊可解释的呼吸暂停极少超过 60s；更长多半是人起身 / 翻出麦克风 / 录音中断 / 恒定环境噪声。 */
    const val GAP_MAX_S = 60.0
    const val EVENT_MERGE_S = 15.0   // 相邻静默段间隔小于此则合并
    const val EVENT_CTX_S = 4.0      // 静默结束后多久内的峰算"结束它的呼吸"

    /**
     * 恢复性喘息必须是"瞬态"而不是"台阶"。
     * 一次吸气 1~2 秒内回到底噪；电视/空调突然开起来则会持续几十秒。
     * 不加这条，环境噪声的一起一落会被当成"静默 + 恢复性喘息"，凭空造出事件。
     */
    const val BREATH_DECAY_S = 3.0   // 峰后多久内应回落到接近底噪
    const val BREATH_DECAY_DB = 6.0  // 至少要回落这么多 dB

    // ---- 噪声底 ----
    const val NF_WINDOW_S = 20.0
    const val NF_PERCENTILE = 15.0
    const val NF_RECALC_EVERY_S = 1.0
    const val NF_WARMUP_S = 5.0

    // ---- 质量门槛 ----
    const val MIN_RECORD_S = 240.0
    const val MIN_ACTIVE_SNR_DB = 8.0
    const val MIN_ACTIVE_FRAC = 0.02
    const val MIN_PEAKS = 30

    /** 峰检测所需的环形历史窗口深度 */
    const val FRAME_WINDOW_CAP = 64
}

/** RMS → dB */
fun ampToDb(rms: Double): Double = 20.0 * log10(rms + 1e-12)

/**
 * 自适应噪声底：最近 WINDOW 秒 dB 值的低分位数，每秒重算一次。
 * 选低分位数而非均值：只要最近有安静期，噪声底就贴住安静期。
 * 窗口内全是鼾声时噪声底被抬高 → SNR 下降 → 质量门限拦住，属预期降级行为。
 */
class NoiseFloor(
    private val windowS: Double = DspConfig.NF_WINDOW_S,
    private val percentile: Double = DspConfig.NF_PERCENTILE,
    private val recalcEveryS: Double = DspConfig.NF_RECALC_EVERY_S,
    private val warmupS: Double = DspConfig.NF_WARMUP_S
) {
    private val cap = max(8, (windowS / DspConfig.SUB_S).toInt())
    private val ring = DoubleArray(cap)
    private var filled = 0          // 环形内有效元素个数（≤ cap）
    private var sinceRecalc = 0.0
    private val warmN = max(1, (warmupS / DspConfig.SUB_S).toInt())
    private val scratch = DoubleArray(cap)

    var level: Double = 0.0
        private set

    fun push(db: Double, dtS: Double = DspConfig.SUB_S): Double {
        // 满之前顺序填；满了以后从头覆盖（ring 本身即为按时间顺序的循环数组）
        ring[filled % cap] = db
        if (filled < cap) filled++
        sinceRecalc += dtS

        if (filled < warmN) {
            var m = Double.MAX_VALUE
            for (i in 0 until filled) if (ring[i] < m) m = ring[i]
            level = if (filled == 0) db else m
        } else if (sinceRecalc >= recalcEveryS) {
            for (i in 0 until filled) scratch[i] = ring[i]
            level = percentileLinear(scratch, filled, percentile)
            sinceRecalc = 0.0
        }
        return level
    }

    fun reset() {
        filled = 0
        sinceRecalc = 0.0
        level = 0.0
    }

    companion object {
        /** 与 numpy.percentile 默认 (linear) 插值一致 */
        fun percentileLinear(values: DoubleArray, n: Int, p: Double): Double {
            if (n <= 0) return 0.0
            val copy = values.copyOf(n)
            copy.sort()
            if (n == 1) return copy[0]
            val h = (n - 1) * (p / 100.0)
            val lo = floor(h).toInt()
            val hi = min(lo + 1, n - 1)
            val frac = h - lo
            return copy[lo] + (copy[hi] - copy[lo]) * frac
        }
    }
}

/**
 * 定长环形帧窗口。峰检测需要"峰之前 1 秒 + 峰之后 1 秒"的完整上下文，
 * 所以必须保留历史 —— 但只需保留最近若干帧，整夜数据不必驻留内存。
 *
 * 用绝对下标寻址（i % cap），与 Python 参考实现里 deque 裁剪的语义等价，
 * 但避免了裁剪时游标不同步导致"检测彻底停摆"这类问题。
 */
class FrameWindow(private val cap: Int = DspConfig.FRAME_WINDOW_CAP) {
    private val db = FloatArray(cap)
    private val snr = FloatArray(cap)
    private val beforeBuf = FloatArray(cap)
    private val afterBuf = FloatArray(cap)
    private val pctBuf = DoubleArray(cap)

    /** 已推入的帧总数（绝对下标，单调递增） */
    var size: Long = 0L
        private set

    fun push(dbv: Double, snrv: Double) {
        val s = (size % cap).toInt()
        db[s] = dbv.toFloat()
        snr[s] = snrv.toFloat()
        size++
    }

    fun has(i: Long): Boolean = i >= 0 && i < size

    /** 该绝对下标的数据是否还留在环形缓冲里（已被覆盖则读不到） */
    fun inRing(i: Long): Boolean = i >= 0 && i < size && (size - i) <= cap

    fun snrAt(i: Long): Float = snr[(i % cap).toInt()]

    fun dbAt(i: Long): Float = db[(i % cap).toInt()]

    /** 填入 [i-n, i) 的 db；返回个数，若历史不足返回 -1 */
    fun fillBefore(i: Long, n: Int): Int {
        if (i - n < 0) return -1
        var k = 0
        for (j in i - n until i) {
            if (!has(j)) return -1
            beforeBuf[k++] = db[(j % cap).toInt()]
        }
        return k
    }

    /** 填入 [i, i+n] 的 db；返回个数，若历史不足返回 -1 */
    fun fillAfter(i: Long, n: Int): Int {
        if (!has(i + n)) return -1
        var k = 0
        for (j in i..i + n) {
            if (!has(j)) return -1
            afterBuf[k++] = db[(j % cap).toInt()]
        }
        return k
    }

    fun maxBefore(i: Long, n: Int): Float {
        var m = Float.NEGATIVE_INFINITY
        for (j in i - n until i) m = maxOf(m, db[(j % cap).toInt()])
        return m
    }

    fun maxAfter(i: Long, n: Int): Float {
        var m = Float.NEGATIVE_INFINITY
        for (j in i..i + n) m = maxOf(m, db[(j % cap).toInt()])
        return m
    }

    /**
     * [i-n, i) 的 P25。用预分配的暂存数组，避免每帧一次堆分配
     * （一整夜 28.8 万帧，分配开销会直接吃掉实时线程的预算）。
     */
    fun beforePercentile25(i: Long, n: Int): Double {
        var k = 0
        for (j in i - n until i) pctBuf[k++] = db[(j % cap).toInt()].toDouble()
        pctBuf.sort(0, k)   // 小数组插入排序，10 个元素极快
        if (k == 0) return 0.0
        if (k == 1) return pctBuf[0]
        val h = (k - 1) * 0.25
        val lo = floor(h).toInt()
        val hi = minOf(lo + 1, k - 1)
        val frac = h - lo
        return pctBuf[lo] + (pctBuf[hi] - pctBuf[lo]) * frac
    }

    fun reset() {
        size = 0
    }
}

/** 子帧 RMS（1600 个 float → dB） */
fun subFrameDb(buf: FloatArray, start: Int, len: Int): Double {
    var acc = 0.0
    for (i in start until start + len) acc += buf[i].toDouble() * buf[i]
    return ampToDb(sqrt(acc / len))
}