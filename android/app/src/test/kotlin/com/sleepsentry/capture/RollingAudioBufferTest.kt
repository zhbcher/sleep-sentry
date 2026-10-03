package com.sleepsentry.capture

import com.sleepsentry.dsp.DspConfig
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

/**
 * 滚动音频缓冲的行为约束。
 *
 * 这个类是"择要留存"能不能成立的关键：事件要过几秒才确认，
 * 到时候必须还能把"事件前 10 秒"的原声切出来。环形覆盖一旦错位，
 * 回放出来的就会是错的那一段 —— 而用户听不出来。
 */
class RollingAudioBufferTest {

    private fun ramp(n: Int, from: Int): ShortArray =
        ShortArray(n) { (from + it).toShort() }

    @Test
    fun sliceReturnsExactRangeBeforeWrap() {
        val b = RollingAudioBuffer(10.0)
        b.append(ramp(4000, 0))          // 0.25s，样本值 0..3999
        val s = b.slice(0.1, 0.2)
        assertEquals(1600, s.size)
        assertEquals(1600.toShort(), s[0])          // 0.1s * 16000 = 1600
        assertEquals((1600 + 1599).toShort(), s[1599])
    }

    @Test
    fun sliceIsCorrectAfterRingWraps() {
        val capacitySec = 5.0
        val b = RollingAudioBuffer(capacitySec)
        val sr = DspConfig.SAMPLE_RATE
        // 写入 3 倍容量，确保绕了一圈
        b.append(ramp(sr, 0))
        b.append(ramp(sr, sr))
        b.append(ramp(sr, 2 * sr))

        val totalSec = 3.0
        val from = totalSec - 0.25
        val s = b.slice(from, totalSec)
        assertEquals(4000, s.size)
        val expectedStart = (from * sr).toLong()
        assertEquals(expectedStart.toShort(), s[0])
        assertEquals((expectedStart + 3999).toShort(), s[3999])
    }

    @Test
    fun sliceClampsToRecordedRange() {
        val b = RollingAudioBuffer(10.0)
        b.append(ramp(16000, 0))          // 只有 1 秒
        // 请求负起点与超出末尾，都应被裁剪而不是崩
        assertEquals(16000, b.slice(-5.0, 1.0).size)
        assertEquals(8000, b.slice(0.5, 99.0).size)
        assertEquals(0, b.slice(50.0, 60.0).size)
    }

    @Test
    fun slicePreservesSampleValuesBitExact() {
        val b = RollingAudioBuffer(2.0)
        // 用可识别的波形：值 = 序号 % 1000，避免 Int→Short 截断造成误判
        val n = 2 * DspConfig.SAMPLE_RATE
        val data = ShortArray(n) { (it % 1000).toShort() }
        b.append(data)
        val all = b.slice(0.0, 2.0)
        assertArrayEquals(data, all)
    }

    @Test
    fun clearResetsTimeline() {
        val b = RollingAudioBuffer(2.0)
        b.append(ramp(16000, 0))
        b.clear()
        assertEquals(0L, b.totalSamples)
        assertEquals(0, b.slice(0.0, 1.0).size)
    }

    /** 采样器：包络压缩必须单调不减地保留峰值趋势 */
    @Test
    fun envelopeSamplerKeepsShape() {
        val s = EnvelopeSampler(pointSec = 1.0)
        val perPoint = (1.0 / DspConfig.SUB_S).toInt()
        repeat(perPoint * 3) { s.push(-40.0) }
        repeat(perPoint * 2) { s.push(-10.0) }
        val out = s.snapshot()
        assertEquals(5, out.size)
        assertEquals(-40f, out[2], 0.01f)
        assertEquals(-10f, out[4], 0.01f)
        // 必须严格递增（后半夜更吵这种趋势才画得出来）
        assertTrue(out[4] > out[2])
    }

    /** 事件音频必须包含"憋气前 10 秒"——这是回放有价值的前提 */
    @Test
    fun eventSliceIncludesPreRoll() {
        val sr = DspConfig.SAMPLE_RATE
        val b = RollingAudioBuffer(100.0)
        val elapsed = 60.0
        val n = (elapsed * sr).toInt()
        b.append(ShortArray(n) { (it % 1000).toShort() })

        val startSec = 40.0
        val endSec = 52.0
        val from = (startSec - PRE_ROLL_SEC).coerceAtLeast(0.0)
        val to = (endSec + POST_ROLL_SEC).coerceAtMost(elapsed)
        val seg = b.slice(from, to)

        assertEquals(30.0, from, 1e-9)                 // 事件开始前 10 秒
        assertEquals(57.0, to, 1e-9)                   // 事件结束后 5 秒
        assertEquals((27 * sr).toInt(), seg.size)      // 12s 静默 + 前后各留白 = 27s
        // 缓冲内容是"样本序号 % 1000"，所以期望值也要取模，不能直接把序号截断成 Short
        assertEquals(((from * sr).toLong() % 1000).toShort(), seg[0])
    }

    /** 纯 JVM 可跑的最小信号链路自检：滤波不能把信号整体抹掉 */
    @Test
    fun bandFilterKeepsSignalInBand() {
        val sr = DspConfig.SAMPLE_RATE
        val n = sr * 2
        val x = FloatArray(n) { sin(2.0 * Math.PI * 300.0 * it / sr).toFloat() }
        val y = com.sleepsentry.dsp.BandFilter().filter(x, n)
        var inEnergy = 0.0
        var outEnergy = 0.0
        for (i in 0 until n) { inEnergy += x[i] * x[i]; outEnergy += y[i] * y[i] }
        assertTrue("带通后能量不应塌陷", outEnergy > inEnergy * 0.05)
    }
}