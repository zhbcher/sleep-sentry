package com.sleepsentry.store

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * PCM 流式写入器。
 *
 * 存在的理由：整夜音频模式下，8 小时 × 16kHz × 16bit ≈ 900MB。
 * 如果用 ArrayList<ShortArray> 全攒在内存里，在绝大多数手机上必然 OOM 崩溃 ——
 * 而且是用户开了"保留整夜音频"之后必然崩，不是偶发。所以必须边录边写。
 *
 * 64KB 缓冲：足够压住磁盘写抖动，又不会占用可观内存。
 */
class PcmStreamWriter(
    private val file: File,
    private val sampleRate: Int,
    private val bufferSize: Int = 64 * 1024
) {
    private val out = BufferedOutputStream(FileOutputStream(file), bufferSize)
    private val scratch = ByteArray(bufferSize)
    private var scratchLen = 0
    private var closed = false

    var samplesWritten: Long = 0L
        private set

    fun write(pcm: ShortArray, count: Int = pcm.size) = writeAt(pcm, 0, count)

    /** 从 offset 处写 count 个样本（采集线程按不规则块喂数据时用） */
    fun writeAt(pcm: ShortArray, offset: Int, count: Int) {
        if (closed) return
        for (i in offset until offset + count) {
            val v = pcm[i].toInt()
            scratch[scratchLen++] = (v and 0xFF).toByte()
            scratch[scratchLen++] = ((v shr 8) and 0xFF).toByte()
            samplesWritten += 2
            if (scratchLen >= bufferSize) {
                out.write(scratch, 0, scratchLen)
                scratchLen = 0
            }
        }
    }

    /** 关闭并返回音频时长（秒）。重复调用安全。 */
    fun close(): Double {
        if (closed) return samplesWritten / 2.0 / sampleRate
        closed = true
        try {
            if (scratchLen > 0) out.write(scratch, 0, scratchLen)
            out.flush()
        } catch (_: Exception) {
        } finally {
            try { out.close() } catch (_: Exception) {}
        }
        return samplesWritten / 2.0 / sampleRate
    }
}