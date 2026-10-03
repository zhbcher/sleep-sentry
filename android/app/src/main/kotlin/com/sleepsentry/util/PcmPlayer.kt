package com.sleepsentry.util

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.sleepsentry.dsp.DspConfig
import com.sleepsentry.store.toShorts

/**
 * PCM 回放。
 *
 * 事件音频存的是原始 16bit PCM（不是 AAC），原因：省掉 MediaCodec/MediaMuxer 这一整层
 * 编解码风险，而且 AudioTrack 直接就能播。代价是文件大（15 秒 ≈ 480KB），
 * 但环形池固定 50 段，总量约 24MB，对一个"存一年也不满"的承诺没有影响。
 */
class PcmPlayer {

    private var track: AudioTrack? = null
    private var thread: Thread? = null

    val isPlaying: Boolean get() = thread?.isAlive == true

    /**
     * 播放一段 PCM 的 [startSec, startSec+lenSec)。
     * @param gain 音量增益（1.0 为原样）
     */
    @Suppress("DEPRECATION")
    fun play(pcm: ByteArray, startSec: Double, lenSec: Double, gain: Float = 2.5f, onDone: () -> Unit) {
        stop()
        val shorts = pcm.toShorts()
        val sr = DspConfig.SAMPLE_RATE
        val from = (startSec * sr).toInt().coerceIn(0, shorts.size)
        val count = (lenSec * sr).toInt().coerceAtMost(shorts.size - from)
        if (count <= 0) { onDone(); return }

        val minBuf = AudioTrack.getMinBufferSize(
            sr, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        ).coerceAtLeast(count * 2)

        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sr)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(minBuf)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        track = t
        thread = Thread {
            try {
                t.play()
                val chunk = 4096
                var i = from
                val end = from + count
                while (i < end) {
                    val n = minOf(chunk, end - i)
                    val buf = ShortArray(n)
                    for (k in 0 until n) {
                        // 先转 Double 再转 Int：gain 是 Float，
                        // 直接 Int*Float 会得到 Float，coerceIn 的 Int 重载就匹配不上
                        val v = (shorts[i + k].toDouble() * gain).toInt()
                            .coerceIn(-32768, 32767)
                        buf[k] = v.toShort()
                    }
                    t.write(buf, 0, n, AudioTrack.WRITE_BLOCKING)
                    i += n
                }
                // 补一段静音，避免结尾"咔"一声
                val pad = ShortArray(sr / 2)
                t.write(pad, 0, pad.size, AudioTrack.WRITE_BLOCKING)
            } catch (_: Exception) {
            } finally {
                try { t.stop() } catch (_: Exception) {}
                onDone()
            }
        }.also { it.start() }
    }

    fun stop() {
        try { thread?.join(500) } catch (_: InterruptedException) {}
        thread = null
        try {
            track?.pause()
            track?.flush()
            track?.release()
        } catch (_: Exception) {}
        track = null
    }
}