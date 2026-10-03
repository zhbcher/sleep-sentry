package com.sleepsentry.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

/**
 * 「响度对比度」诊断指标的行为约束。
 *
 * 这个指标存在的意义，是记录一次失败的探索：曾试图用它当质量门限，
 * 但实测证明它无法判断录音里有没有可用的鼾声信号（详见 NightQuality 注释）。
 * 这些测试的作用是把它钉死成一个**如实命名的诊断量**，防止以后有人
 * 看到名字里有"对比度"就误当成"检测能力"而去依赖它。
 *
 * 注意：输入必须是**音频**，检测器自己会切子帧并算噪声底；
 * 直接把 SNR 序列当音频喂进去只会产生极少的子帧，达不到统计门槛。
 */
class LoudnessContrastTest {

    private val subN: Int = DspConfig.SUB_N

    private fun sine(seconds: Double, freq: Double, amp: Double): FloatArray {
        val n = (seconds * DspConfig.SAMPLE_RATE).toInt()
        return FloatArray(n) { (amp * sin(2.0 * Math.PI * freq * it / DspConfig.SAMPLE_RATE)).toFloat() }
    }

    private fun noise(seconds: Double, amp: Double, seed: Int): FloatArray {
        val n = (seconds * DspConfig.SAMPLE_RATE).toInt()
        val rnd = java.util.Random(seed.toLong())
        return FloatArray(n) { ((rnd.nextDouble() - 0.5) * 2 * amp).toFloat() }
    }

    private fun contrastOf(sig: FloatArray): Double {
        val det = EventDetector()
        det.processChunk(BandFilter().filter(sig, sig.size), 0.0)
        return det.finalize().second.loudnessContrastDb
    }

    @Test
    fun loudSignalHasLargeContrast() {
        // 10 秒底噪 + 10 秒很响的 200Hz 纯音。
        // 至少要 100 个子帧（10 秒）才够统计，否则指标按约定返回 0。
        val a = noise(10.0, 0.001, 1)
        val b = sine(10.0, 200.0, 0.2)
        val sig = FloatArray(a.size + b.size)
        System.arraycopy(a, 0, sig, 0, a.size)
        System.arraycopy(b, 0, sig, a.size, b.size)
        val c = contrastOf(sig)
        assertTrue("响亮数据应给出大对比度，实际 $c", c > 12.0)
        assertTrue("不应超过理论上限太多，实际 $c", c < 40.0)
    }

    @Test
    fun flatSignalHasNearZeroContrast() {
        // 全程是幅度恒定的低频噪声：响度分布很窄 → 对比度接近 0
        val c = contrastOf(noise(10.0, 0.01, 7))
        assertTrue("平坦数据应接近 0，实际 $c", kotlin.math.abs(c) < 8.0)
    }

    @Test
    fun tooShortSignalReturnsZero() {
        // 不足 100 个子帧 → 不给结论，返回 0
        val c = contrastOf(FloatArray(subN * 20) { 0.5f })
        assertEquals(0.0, c, 1e-9)
    }

    /**
     * 关键回归：合成金标准场景的对比度必须明显高于真实低信噪比录音
     * （APSAA 实测 3.7~10dB 却仍无鼾声信号）。
     * 这条测试防止有人误以为"对比度高 = 能检测"。
     */
    @Test
    fun syntheticGoldStandardHasFarHigherContrastThanRealLowSnrRecordings() {
        val (audio, _) = SynthAudio.generate(1800.0, apneaCount = 8, seed = 7, noiseScale = 1.0)
        val filtered = BandFilter().filter(audio, audio.size)
        val det = EventDetector()
        val CH = 10 * DspConfig.SAMPLE_RATE
        var t0 = 0.0
        for (off in 0 until filtered.size step CH) {
            val end = minOf(off + CH, filtered.size)
            det.processChunk(filtered.copyOfRange(off, end), t0)
            t0 = end.toDouble() / DspConfig.SAMPLE_RATE
        }
        val q = det.finalize().second
        assertTrue(
            "合成数据对比度应显著偏高（实际 ${q.loudnessContrastDb}dB），" +
                "以区别于真实低信噪比录音的 3.7~10dB",
            q.loudnessContrastDb > 10.0
        )
    }
}