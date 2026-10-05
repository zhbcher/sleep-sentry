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

    // ---------------------------------------------------------------- 音频段

    /**
     * 片段文件名里**带事件发生的绝对时刻**。
     *
     * 旧版只带递增序号（seg_<date>_0001.pcm），有两个问题：
     *  1. 事件要过约 5 秒才确认，序号 ≠ 发生时间，顺序本身就对不上
     *  2. 序号从 9999 进到 10000 时，字典序 "10000" < "9999"，
     *     按文件名排序会**倒过来** —— 这就是用户看到的"排序混乱"
     * 带时刻之后，字典序 == 时间序，排序问题从根上消失。
     */
    fun segmentFileName(date: String, eventAbsMillis: Long, index: Int): String =
        SegmentNaming.segment(date, eventAbsMillis, index)

    fun fullAudioFileName(date: String): String = SegmentNaming.fullAudio(date)

    /**
     * 写入一个音频段，并按配额淘汰旧文件。
     * @param eventAbsMillis 该事件发生的绝对时刻（决定淘汰顺序）
     */
    fun putSegment(
        nightDate: String,
        pcm: ShortArray,
        fileName: String,
        eventAbsMillis: Long = System.currentTimeMillis()
    ): Pair<File, Double> {
        val f = File(audioDir, fileName)
        f.writeBytes(pcm.toBytes())
        f.setLastModified(eventAbsMillis)
        val lenSec = pcm.size.toDouble() / com.sleepsentry.dsp.DspConfig.SAMPLE_RATE
        enforceQuota(nightDate)
        return f to lenSec
    }

    fun deleteSegment(fileName: String) {
        File(audioDir, fileName).delete()
    }

    /** 整夜音频（可选功能）：一次写入，返回时长秒 */
    fun putFullAudio(fileName: String, pcm: ShortArray, recordedAtMillis: Long): Double {
        val f = File(audioDir, fileName)
        f.writeBytes(pcm.toBytes())
        f.setLastModified(recordedAtMillis)
        enforceQuota(f.name.removePrefix("full_").removeSuffix(".pcm"))
        return pcm.size.toDouble() / com.sleepsentry.dsp.DspConfig.SAMPLE_RATE
    }

    // ---------------------------------------------------------------- 配额

    /** 该夜所有音频文件的清单（含事件片段与整夜音频） */
    fun audioItems(nightDate: String): List<StorageQuota.Item> {
        val prefix = nightDate
        return (audioDir.listFiles() ?: emptyArray())
            .filter { it.name.startsWith("seg_$prefix") || it.name.startsWith("full_$prefix") }
            .map {
                StorageQuota.Item(
                    name = it.name,
                    bytes = it.length(),
                    recordedAtMillis = SegmentNaming.parseRecordedAt(it),
                    isFullAudio = it.name.startsWith("full_")
                )
            }
    }

    /** 该夜音频当前占用字节 */
    fun audioUsageBytes(nightDate: String): Long = audioItems(nightDate).sumOf { it.bytes }

    /**
     * 按配额淘汰。统计 JSON 不受影响，永远保留；只删音频。
     * 必须在写完新文件之后调用（这样新片段一定在"最新"那端，不会被立刻删掉）。
     */
    fun enforceQuota(nightDate: String, quotaMb: Int) {
        val items = audioItems(nightDate)
        if (items.isEmpty()) return
        val quotaBytes = if (quotaMb <= 0) 0L else quotaMb.toLong() * 1024 * 1024
        val plan = StorageQuota.plan(items, quotaBytes)
        if (plan.delete.isEmpty()) return

        for (name in plan.delete) File(audioDir, name).delete()

        // 同步把已删除音频的引用从当晚记录里摘掉，避免界面点回放时找不到文件
        val rec = load(nightDate) ?: return
        val alive = plan.keep.toSet()
        val kept = rec.events.filter { it.audioFile == null || it.audioFile in alive }
        if (kept.size != rec.events.size) save(rec.copy(events = kept))
    }

    fun enforceQuota(nightDate: String) =
        enforceQuota(nightDate, com.sleepsentry.util.Prefs(ctx).storageQuotaMb)

    // ---------------------------------------------------------------- 清除

    /** 清除返回删除的文件数与释放的字节数 */
    fun clearAll(): Pair<Int, Long> {
        var n = 0
        var bytes = 0L
        (audioDir.listFiles() ?: emptyArray()).forEach {
            bytes += it.length()
            if (it.delete()) n++
        }
        (nightsDir.listFiles() ?: emptyArray()).forEach { it.delete() }
        return n to bytes
    }

    /** 只清某一晚的音频，保留当晚统计 */
    fun clearAudioOf(nightDate: String): Long {
        var bytes = 0L
        audioItems(nightDate).forEach {
            bytes += it.bytes
            File(audioDir, it.name).delete()
        }
        val rec = load(nightDate)
        if (rec != null) {
            save(rec.copy(
                events = rec.events.map { it.copy(audioFile = null) },
                fullAudioFile = null
            ))
        }
        return bytes
    }

    /** 全部音频总占用（设置页展示用） */
    fun totalAudioBytes(): Long = (audioDir.listFiles()?.sumOf { it.length() } ?: 0L)

    /** 按录音时间先后列出的片段（时间正序） */
    fun listSegmentsChronological(nightDate: String): List<File> =
        audioItems(nightDate).sortedBy { it.recordedAtMillis }
            .map { File(audioDir, it.name) }

    fun formatDate(ms: Long): String = fmtDay.format(Date(ms))
    fun formatFull(ms: Long): String = fmtFull.format(Date(ms))


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