package com.sleepsentry.store

import com.sleepsentry.dsp.DspConfig
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.sin
import kotlin.random.Random

/**
 * 流式写入器的正确性约束。
 *
 * 为什么要专门测它：整夜音频从"内存攒完再一次性落盘"改成"边录边写"，
 * 是为了避免 8 小时约 900MB 撑爆内存。但这种改动一旦有个字节顺序写错，
 * 回放出来就是噪音 —— 而用户要靠这段声音判断"刚才那声憋气是不是真的"。
 * 所以必须钉死：流式写出来的字节，与原来一次性写出来的完全一致。
 */
class PcmStreamWriterTest {

    private fun tmp(name: String) = File(System.getProperty("java.io.tmpdir"), "ss_$name.pcm")

    private fun toBytes(pcm: ShortArray): ByteArray {
        val out = ByteArray(pcm.size * 2)
        for (i in pcm.indices) {
            val v = pcm[i].toInt()
            out[i * 2] = (v and 0xFF).toByte()
            out[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return out
    }

    @Test
    fun streamingOutputIsByteIdenticalToInMemory() {
        val rnd = Random(42)
        // 刻意用非整块大小，逼出跨 64KB 边界的拼接错误
        val n = 3 * 65536 + 1234
        val pcm = ShortArray(n) { rnd.nextInt(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort() }

        val f = tmp("identical")
        val w = PcmStreamWriter(f, DspConfig.SAMPLE_RATE)
        // 分成不规则的小批写入，模拟 AudioRecord 每次 100ms
        var off = 0
        while (off < n) {
            val len = minOf(1600, n - off)
            w.writeAt(pcm, off, len)
            off += len
        }
        val sec = w.close()

        assertArrayEquals("流式写入必须与一次性写入逐字节一致", toBytes(pcm), f.readBytes())
        assertEquals(n.toDouble() / DspConfig.SAMPLE_RATE, sec, 1e-9)
        f.delete()
    }

    @Test
    fun multipleWritesAccumulateCorrectly() {
        val f = tmp("multi")
        val w = PcmStreamWriter(f, DspConfig.SAMPLE_RATE, bufferSize = 16)
        val a = ShortArray(10) { (it + 1).toShort() }
        val b = ShortArray(10) { (it - 1).toShort() }
        w.write(a); w.write(b)
        w.close()
        val expected = ByteArray(40)
        System.arraycopy(toBytes(a), 0, expected, 0, 20)
        System.arraycopy(toBytes(b), 0, expected, 20, 20)
        assertArrayEquals(expected, f.readBytes())
        f.delete()
    }

    @Test
    fun closeIsIdempotent() {
        val f = tmp("idem")
        val w = PcmStreamWriter(f, DspConfig.SAMPLE_RATE)
        w.write(ShortArray(1600) { 7 })
        val first = w.close()
        val second = w.close()
        assertEquals(first, second, 1e-9)
        // 关闭后再写必须被忽略，不能把已关闭的流写坏
        w.write(ShortArray(1600) { 9 })
        assertEquals(3200, f.length())
        f.delete()
    }

    /** 负数样本的字节序是最容易搞错的地方，单独钉一条 */
    @Test
    fun negativeSamplesUseLittleEndian() {
        val f = tmp("neg")
        val w = PcmStreamWriter(f, DspConfig.SAMPLE_RATE)
        w.write(shortArrayOf(0, -1, Short.MIN_VALUE, Short.MAX_VALUE))
        w.close()
        // 0x0000 0xFFFF 0x8000 0x7FFF，小端序，每样本 2 字节 → 共 8 字节
        assertArrayEquals(
            byteArrayOf(0, 0, -1, -1, 0, -128, -1, 127),
            f.readBytes()
        )
        f.delete()
    }

    /** 一整夜规模的写入量（约 115MB）不该把内存撑爆；这里只验证计数与时长正确 */
    @Test
    fun longNightDurationIsCorrect() {
        val f = tmp("long")
        val sr = DspConfig.SAMPLE_RATE
        val chunk = ShortArray(1600)
        for (i in chunk.indices) chunk[i] = (sin(2.0 * Math.PI * 200 * i / sr) * 8000).toInt().toShort()
        val w = PcmStreamWriter(f, sr)
        // 每块 1600 样本 = 0.1 秒，要凑够 300 秒就得写 3000 块
        repeat(3000) { w.write(chunk) }
        val sec = w.close()
        assertEquals(300.0, sec, 0.001)
        assertTrue("文件大小应为 2 字节/样本", f.length() == 300L * sr * 2)
        f.delete()
    }
}