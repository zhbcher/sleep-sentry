package com.sleepsentry.store

import android.content.Context
import com.sleepsentry.dsp.BreathingEvent
import com.sleepsentry.dsp.NightQuality
import com.sleepsentry.dsp.Severity
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 一晚的完整记录。
 *
 * 音频只保留"择要留存"的部分（事件前 10s + 后 5s），统计数字永久保留。
 * 这样单晚占用约 3MB，一年 300 晚约 720MB，且永远不需要用户手动清理。
 */
data class NightRecord(
    val date: String,                 // yyyy-MM-dd
    val startMillis: Long,
    val endMillis: Long,
    val recordedSec: Double,
    val events: List<StoredEvent>,
    val quality: NightQuality,
    val peakCount: Int,
    val snoreActiveSec: Double,
    /** 抽样后的能量包络，用于时间轴（每 pointSec 秒一个点） */
    val envelope: FloatArray,
    val envelopePointSec: Double,
    val fullAudioFile: String? = null
) {
    /** 疑似事件小时密度 —— 分级的主指标 */
    val eventsPerHour: Double
        get() = if (recordedSec <= 0) 0.0 else events.size / (recordedSec / 3600.0)

    val level: Severity.Level get() = Severity.levelOf(eventsPerHour)

    /** 最长静默的事件 */
    val worstEvent: StoredEvent? get() = events.maxByOrNull { it.silenceSec }

    val recordedHours: Double get() = recordedSec / 3600.0
}

data class StoredEvent(
    val startSec: Double,
    val silenceSec: Double,
    val endSec: Double,
    val recoverySec: Double,
    val prominenceDb: Double,
    val audioFile: String? = null,
    val audioStartInFileSec: Double = 0.0,
    val audioLenSec: Double = 0.0
)

object NightRecordJson {
    fun toJson(r: NightRecord): String {
        val root = JSONObject()
        root.put("date", r.date)
        root.put("startMillis", r.startMillis)
        root.put("endMillis", r.endMillis)
        root.put("recordedSec", r.recordedSec)
        root.put("peakCount", r.peakCount)
        root.put("snoreActiveSec", r.snoreActiveSec)
        root.put("envPointSec", r.envelopePointSec)
        r.fullAudioFile?.let { root.put("fullAudio", it) }
        val env = JSONArray()
        for (v in r.envelope) env.put(v.toDouble())
        root.put("envelope", env)
        val evs = JSONArray()
        for (e in r.events) {
            val o = JSONObject()
            o.put("startSec", e.startSec)
            o.put("silenceSec", e.silenceSec)
            o.put("endSec", e.endSec)
            o.put("recoverySec", e.recoverySec)
            o.put("promDb", e.prominenceDb)
            e.audioFile?.let { o.put("audio", it) }
            if (e.audioLenSec > 0) {
                o.put("audioStart", e.audioStartInFileSec)
                o.put("audioLen", e.audioLenSec)
            }
            evs.put(o)
        }
        root.put("events", evs)
        val q = JSONObject()
        q.put("durationSec", r.quality.durationSec)
        q.put("activeSnrMedianDb", r.quality.activeSnrMedianDb)
        q.put("activeFraction", r.quality.activeFraction)
        q.put("peakCount", r.quality.peakCount)
        q.put("ok", r.quality.ok)
        root.put("quality", q)
        return root.toString()
    }

    fun fromJson(s: String): NightRecord? = try {
        val root = JSONObject(s)
        val evArr = root.optJSONArray("events") ?: JSONArray()
        val events = ArrayList<StoredEvent>(evArr.length())
        for (i in 0 until evArr.length()) {
            val o = evArr.getJSONObject(i)
            events.add(
                StoredEvent(
                    startSec = o.optDouble("startSec"),
                    silenceSec = o.optDouble("silenceSec"),
                    endSec = o.optDouble("endSec"),
                    recoverySec = o.optDouble("recoverySec"),
                    prominenceDb = o.optDouble("promDb"),
                    audioFile = o.optString("audio").takeIf { it.isNotEmpty() },
                    audioStartInFileSec = o.optDouble("audioStart", 0.0),
                    audioLenSec = o.optDouble("audioLen", 0.0)
                )
            )
        }
        val q = root.optJSONObject("quality")
        val envArr = root.optJSONArray("envelope") ?: JSONArray()
        val env = FloatArray(envArr.length()) { envArr.getDouble(it).toFloat() }
        NightRecord(
            date = root.optString("date"),
            startMillis = root.optLong("startMillis"),
            endMillis = root.optLong("endMillis"),
            recordedSec = root.optDouble("recordedSec"),
            events = events,
            quality = NightQuality(
                durationSec = q?.optDouble("durationSec", 0.0) ?: 0.0,
                activeSnrMedianDb = q?.optDouble("activeSnrMedianDb", 0.0) ?: 0.0,
                activeFraction = q?.optDouble("activeFraction", 0.0) ?: 0.0,
                peakCount = q?.optInt("peakCount", 0) ?: 0,
                ok = q?.optBoolean("ok", false) ?: false
            ),
            peakCount = root.optInt("peakCount"),
            snoreActiveSec = root.optDouble("snoreActiveSec"),
            envelope = env,
            envelopePointSec = root.optDouble("envPointSec", 30.0),
            fullAudioFile = root.optString("fullAudio").takeIf { it.isNotEmpty() }
        )
    } catch (e: Exception) {
        null
    }
}

/**
 * 记录仓库：每天一个 JSON，外加一个定容的音频环形池。
 *
 * 环形池的意义是让"永不满"成为产品特性 —— 旧的自动覆盖，
 * 用户一年都不用想"要不要清理"。
 */
class NightStore(private val ctx: Context) {

    private val nightsDir: File get() = File(ctx.filesDir, "nights").apply { mkdirs() }
    private val audioDir: File get() = File(ctx.filesDir, "audio").apply { mkdirs() }

    private val fmtDay = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val fmtFull = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    fun save(r: NightRecord) {
        File(nightsDir, "${r.date}.json").writeText(NightRecordJson.toJson(r))
    }

    fun load(date: String): NightRecord? {
        val f = File(nightsDir, "$date.json")
        if (!f.exists()) return null
        return NightRecordJson.fromJson(f.readText())
    }

    /** 按日期倒序列出全部记录 */
    fun list(): List<NightRecord> {
        val files = nightsDir.listFiles { f -> f.name.endsWith(".json") } ?: return emptyList()
        return files.mapNotNull { NightRecordJson.fromJson(it.readText()) }
            .sortedByDescending { it.startMillis }
    }

    fun latest(): NightRecord? = list().firstOrNull()

    fun audioFile(name: String): File = File(audioDir, name)

    /**
     * 写入一个音频段并执行环形覆盖。
     * 返回该段在文件内的起点秒数，便于播放时定位。
     */
    fun putSegment(
        nightDate: String,
        pcm: ShortArray,
        fileName: String,
        maxSegments: Int = MAX_SEGMENTS
    ): Pair<File, Double> {
        val f = File(audioDir, fileName)
        f.writeBytes(pcm.toBytes())
        val lenSec = pcm.size.toDouble() / com.sleepsentry.dsp.DspConfig.SAMPLE_RATE
        trimRing(nightDate, maxSegments)
        return f to lenSec
    }

    fun deleteSegment(fileName: String) {
        File(audioDir, fileName).delete()
    }

    /** 整夜音频（可选功能）：一次写入，返回时长秒 */
    fun putFullAudio(fileName: String, pcm: ShortArray): Double {
        val f = File(audioDir, fileName)
        f.writeBytes(pcm.toBytes())
        return pcm.size.toDouble() / com.sleepsentry.dsp.DspConfig.SAMPLE_RATE
    }

    /**
     * 环形覆盖：只保留最近的 maxSegments 段。
     * 统计 JSON 不受影响，永远保留。
     */
    private fun trimRing(nightDate: String, maxSegments: Int) {
        val segs = audioDir.listFiles { f -> f.name.startsWith("seg_${nightDate}_") }
            ?.sortedBy { it.name } ?: return
        if (segs.size <= maxSegments) return
        val excess = segs.size - maxSegments
        for (i in 0 until excess) segs[i].delete()
        // 同步清理引用了已删除文件的记录
        load(nightDate)?.let { rec ->
            val alive = segs.drop(excess).map { it.name }.toSet()
            val kept = rec.events.filter { it.audioFile == null || it.audioFile in alive }
            if (kept.size != rec.events.size) save(rec.copy(events = kept))
        }
    }

    fun formatDate(ms: Long): String = fmtDay.format(Date(ms))
    fun formatFull(ms: Long): String = fmtFull.format(Date(ms))

    fun totalAudioBytes(): Long =
        (audioDir.listFiles()?.sumOf { it.length() } ?: 0L)

    companion object {
        /** 事件音频环形池容量。15s × 50 段 × 32KB/s ≈ 24MB */
        const val MAX_SEGMENTS = 50
    }
}

fun ShortArray.toBytes(): ByteArray {
    val out = ByteArray(size * 2)
    for (i in indices) {
        val v = this[i].toInt()
        out[i * 2] = (v and 0xFF).toByte()
        out[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
    }
    return out
}

fun ByteArray.toShorts(): ShortArray {
    val n = size / 2
    val out = ShortArray(n)
    for (i in 0 until n) {
        val lo = this[i * 2].toInt() and 0xFF
        val hi = this[i * 2 + 1].toInt()
        out[i] = ((hi shl 8) or lo).toShort()
    }
    return out
}

fun BreathingEvent.toStored(audioFile: String?, audioStart: Double, audioLen: Double) = StoredEvent(
    startSec = startSec, silenceSec = silenceSec, endSec = endSec,
    recoverySec = recoverySec, prominenceDb = recoveryProminenceDb,
    audioFile = audioFile, audioStartInFileSec = audioStart, audioLenSec = audioLen
)