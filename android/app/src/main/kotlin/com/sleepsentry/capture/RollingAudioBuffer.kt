package com.sleepsentry.capture

import com.sleepsentry.dsp.DspConfig

/**
 * 滚动音频缓冲（环形）。
 *
 * 存在的理由：事件是在静默结束约 5 秒后才被确认的，那时要把
 * "事件前 10 秒 + 事件本身 + 事件后 5 秒"的原声切出来，就必须一直留着前面的音频。
 *
 * 容量计算：最长事件 60s + 前后各 10s ⇒ 需要 80s，取 100s 留余量。
 * 100s × 16000 × 2B = 3.2MB，整夜常驻可接受。
 */
class RollingAudioBuffer(capacitySec: Double = 100.0) {

    private val sr = DspConfig.SAMPLE_RATE
    private val cap = (capacitySec * sr).toInt()
    private val buf = ShortArray(cap)
    private var writePos = 0L      // 已写入的累计样本数

    /** 追加 PCM（可分多次调用） */
    fun append(pcm: ShortArray, count: Int = pcm.size) {
        for (i in 0 until count) {
            buf[((writePos + i) % cap).toInt()] = pcm[i]
        }
        writePos += count
    }

    val totalSamples: Long get() = writePos

    /**
     * 取出 [fromSec, toSec] 区间（相对当前 writePos 的秒数，可为负表示已过去）。
     * 超出已录制范围的部分会被裁掉。
     */
    fun slice(fromSec: Double, toSec: Double): ShortArray {
        val totalSec = writePos.toDouble() / sr
        val lo = maxOf(fromSec, 0.0)
        val hi = minOf(toSec, totalSec)
        if (hi <= lo) return ShortArray(0)
        val startSample = (lo * sr).toLong()
        val endSample = (hi * sr).toLong()
        val n = (endSample - startSample).toInt()
        if (n <= 0) return ShortArray(0)
        val out = ShortArray(n)
        for (i in 0 until n) {
            out[i] = buf[((startSample + i) % cap).toLong().toInt()]
        }
        return out
    }

    fun clear() {
        writePos = 0
        java.util.Arrays.fill(buf, 0)
    }
}

/**
 * 能量包络抽样器：把逐秒包络压成"每 N 秒一个点"，供时间轴绘制。
 * 整夜 8h、每 30 秒一点 = 960 个点，一份几十 KB，足够画图。
 */
class EnvelopeSampler(private val pointSec: Double = 30.0) {

    private val acc = ArrayList<Float>(1024)
    private var bucketAcc = 0.0
    private var bucketN = 0
    private val perPoint = (pointSec / DspConfig.SUB_S).toInt()

    fun push(db: Double) {
        bucketAcc += db
        bucketN++
        if (bucketN >= perPoint) {
            acc.add((bucketAcc / bucketN).toFloat())
            bucketAcc = 0.0
            bucketN = 0
        }
    }

    fun snapshot(): FloatArray = acc.toFloatArray()
    fun reset() {
        acc.clear(); bucketAcc = 0.0; bucketN = 0
    }
    val pointSecInterval: Double get() = pointSec
}