package com.sleepsentry.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 单节二阶滤波器，直接 II 型转置结构。
 *
 * 与 Python 参考实现 dsp/detector.py 的 Biquad 逐行对应，系数必须一致。
 * 用 RBJ cookbook 而非通用系数表，是为了保证 Kotlin / Python 两侧可以逐行对照，
 * 改参数时不会一边改了另一边没改。
 */
class Biquad(
    b0: Double, b1: Double, b2: Double,
    a0: Double, a1: Double, a2: Double
) {
    private val c0 = b0 / a0
    private val c1 = b1 / a0
    private val c2 = b2 / a0
    private val d1 = a1 / a0
    private val d2 = a2 / a0
    private var z1 = 0.0
    private var z2 = 0.0

    fun process(x: Double): Double {
        val y = c0 * x + z1
        z1 = c1 * x - d1 * y + z2
        z2 = c2 * x - d2 * y
        return y
    }

    fun reset() {
        z1 = 0.0
        z2 = 0.0
    }

    companion object {
        const val Q_BUTTERWORTH = 0.7071067811865476

        fun lowpass(f0: Double, fs: Double, q: Double = Q_BUTTERWORTH): Biquad {
            val w0 = 2.0 * PI * f0 / fs
            val cw = cos(w0)
            val al = sin(w0) / (2.0 * q)
            val a0 = 1.0 + al
            return Biquad((1 - cw) / 2.0, 1 - cw, (1 - cw) / 2.0, a0, -2 * cw, 1 - al)
        }

        fun highpass(f0: Double, fs: Double, q: Double = Q_BUTTERWORTH): Biquad {
            val w0 = 2.0 * PI * f0 / fs
            val cw = cos(w0)
            val al = sin(w0) / (2.0 * q)
            val a0 = 1.0 + al
            return Biquad((1 + cw) / 2.0, -(1 + cw), (1 + cw) / 2.0, a0, -2 * cw, 1 - al)
        }
    }
}

/**
 * 高通 100Hz + 低通 4000Hz 级联。
 * 去掉空调/桌面震动的低频与麦克风高频噪声，保留鼾声主体频段。
 */
class BandFilter(
    fLo: Double = DEFAULT_F_LO,
    fHi: Double = DEFAULT_F_HI,
    private val sampleRate: Int = DspConfig.SAMPLE_RATE
) {
    // 上界必须随采样率收缩：4kHz 录音的奈奎斯特只有 2kHz，
    // 还按 4000Hz 设低通只会把混叠放进来（实测 APSAA 数据集就是 4kHz）。
    private val lp = Biquad.lowpass(
        fHi.coerceAtMost(sampleRate * 0.45), sampleRate.toDouble()
    )
    private val hp = Biquad.highpass(
        fLo.coerceAtMost(sampleRate * 0.45), sampleRate.toDouble()
    )

    /** 就地滤波前 count 个样本，返回同样长度的数组。滤波状态跨调用保持连续。 */
    fun filter(buf: FloatArray, count: Int = buf.size): FloatArray {
        val out = FloatArray(count)
        for (i in 0 until count) {
            out[i] = hp.process(buf[i].toDouble()).toFloat()
        }
        for (i in 0 until count) {
            out[i] = lp.process(out[i].toDouble()).toFloat()
        }
        return out
    }

    fun reset() {
        hp.reset()
        lp.reset()
    }

    companion object {
        const val DEFAULT_F_LO = 100.0
        const val DEFAULT_F_HI = 4000.0
    }
}