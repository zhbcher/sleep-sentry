package com.sleepsentry.capture

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.sleepsentry.R
import com.sleepsentry.dsp.BandFilter
import com.sleepsentry.dsp.DspConfig
import com.sleepsentry.dsp.EventDetector
import com.sleepsentry.dsp.NightQuality
import com.sleepsentry.dsp.subFrameDb
import com.sleepsentry.store.NightRecord
import com.sleepsentry.store.PcmStreamWriter
import com.sleepsentry.store.NightStore
import com.sleepsentry.store.StoredEvent
import com.sleepsentry.store.toStored
import com.sleepsentry.ui.MainActivity
import com.sleepsentry.util.Prefs
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 事件音频保留：前 10 秒 + 后 5 秒，把"憋气前那声鼾"和"憋完那口气"都留住 */
const val PRE_ROLL_SEC = 10.0
const val POST_ROLL_SEC = 5.0

fun nightDay(ms: Long): String =
    SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(ms))

/**
 * 夜间持续监听服务（前台服务，type=microphone）。
 *
 * 设计要点（方案 v3.0）：
 *  · 麦克风全程常开、全程累计计数 —— 次数一次不漏
 *  · 只在事件确认后才切出音频落盘 —— 存储降到全量的 1/300
 *  · 不依赖 App 自己后台唤醒：靠 ACTION_POWER_CONNECTED 广播拉起（见 PowerReceiver）
 */
class SentryService : Service() {

    companion object {
        const val ACTION_START = "com.sleepsentry.START"
        const val ACTION_STOP = "com.sleepsentry.STOP"
        const val CHANNEL_ARMED = "armed"
        const val NOTIF_ID_ARMED = 1001

        @Volatile var liveRecordedSec: Double = 0.0
        @Volatile var liveEventCount: Int = 0
        @Volatile var liveSnoreCount: Int = 0
        @Volatile var isRunning: Boolean = false

        fun start(ctx: Context) {
            val i = Intent(ctx, SentryService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ctx.startForegroundService(i)
            } else {
                ctx.startService(i)
            }
        }

        fun stop(ctx: Context) {
            try {
                ctx.startService(Intent(ctx, SentryService::class.java).setAction(ACTION_STOP))
            } catch (_: Exception) {
            }
        }
    }

    private lateinit var prefs: Prefs
    private lateinit var store: NightStore
    private var worker: Thread? = null
    @Volatile private var stopRequested = false
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        store = NightStore(this)
        createChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopRequested = true
            stopSelf()
            return START_NOT_STICKY
        }
        startAsForeground()
        if (worker == null) {
            stopRequested = false
            worker = Thread({ runLoop() }, "sentry-capture").apply { start() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopRequested = true
        try { worker?.join(3000) } catch (_: InterruptedException) {}
        worker = null
        isRunning = false
        liveRecordedSec = 0.0
        liveEventCount = 0
        try { wakeLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
        wakeLock = null
        super.onDestroy()
    }

    // ------------------------------------------------------------ 前台通知

    private fun startAsForeground() {
        val n = NotificationCompat.Builder(this, CHANNEL_ARMED)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(getString(R.string.notif_armed_title))
            .setContentText(getString(R.string.tagline))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openApp())
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID_ARMED, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID_ARMED, n)
        }
    }

    private fun createChannels() {
        val mgr = getSystemService(NotificationManager::class.java) ?: return
        mgr.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ARMED, getString(R.string.notif_channel_armed),
                NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
        )
        mgr.createNotificationChannel(
            NotificationChannel(
                MorningNotifier.CHANNEL_REPORT, getString(R.string.notif_channel_report),
                NotificationManager.IMPORTANCE_DEFAULT
            )
        )
        mgr.createNotificationChannel(
            NotificationChannel(
                MorningNotifier.CHANNEL_ALERT, getString(R.string.notif_channel_alert),
                NotificationManager.IMPORTANCE_HIGH
            )
        )
    }

    private fun openApp(): PendingIntent {
        val i = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            this, 0, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    // ------------------------------------------------------------ 主循环

    private fun runLoop() {
        val sr = DspConfig.SAMPLE_RATE
        val minBuf = AudioRecord.getMinBufferSize(
            sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) {
            prefs.lastFailureReason = "设备不支持 16kHz 单声道录音"
            return
        }
        val readChunk = DspConfig.SUB_N            // 每次读 100ms
        val recBuf = ShortArray(readChunk * 4)
        val detector = EventDetector()
        val filter = BandFilter()
        val rolling = RollingAudioBuffer(100.0)
        val sampler = EnvelopeSampler(30.0)
        // 整夜音频必须边录边写：攒在内存里 8 小时约 900MB，必然 OOM
        var fullWriter: PcmStreamWriter? = if (prefs.keepFullAudio) {
            runCatching {
                PcmStreamWriter(store.audioFile(store.fullAudioFileName(nightDay(System.currentTimeMillis()))), sr)
            }.getOrNull()
        } else null

        val source = pickSource()
        val rec = try {
            AudioRecord(
                source, sr, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, minBuf * 2
            )
        } catch (e: SecurityException) {
            prefs.lastFailureReason = "麦克风权限被拒绝"
            return
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            prefs.lastFailureReason = "无法打开麦克风（可能被其他应用占用）"
            rec.release()
            return
        }

        // 息屏下 CPU 不能被挂起，否则录音会断断续续
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sleepsentry:capture")
                ?.apply { acquire(14 * 60 * 60 * 1000L) }
        } catch (_: Exception) {}

        val day = nightDay(System.currentTimeMillis())
        val events = ArrayList<StoredEvent>()
        var segSeq = 0
        val startMs = System.currentTimeMillis()
        var elapsed = 0.0
        isRunning = true

        try {
            rec.startRecording()
            while (!stopRequested) {
                if (!prefs.enabled) break
                val n = rec.read(recBuf, 0, readChunk)
                if (n < 0) {
                    prefs.lastFailureReason = when (n) {
                        AudioRecord.ERROR_DEAD_OBJECT -> "麦克风被系统回收（其他应用抢占）"
                        AudioRecord.ERROR_INVALID_OPERATION -> "录音操作非法"
                        else -> "录音错误码 $n"
                    }
                    break
                }
                if (n == 0) continue
                val pcm = if (n == recBuf.size) recBuf else recBuf.copyOf(n)

                // 先留音频再判定：事件要过几秒才确认，但音频必须提前躺在缓冲里
                rolling.append(pcm, n)
                fullWriter?.write(pcm, n)

                val f = FloatArray(n) { pcm[it] / 32768.0f }
                val filtered = filter.filter(f, n)
                val clipped = (0 until n).count { kotlin.math.abs(pcm[it].toInt()) >= 32760 }
                detector.processChunk(filtered, elapsed, n, clipped)
                elapsed += n.toDouble() / sr

                var off = 0
                while (off + DspConfig.SUB_N <= filtered.size) {
                    sampler.push(subFrameDb(filtered, off, DspConfig.SUB_N))
                    off += DspConfig.SUB_N
                }

                // 取出"已确认"的新事件并落盘
                for (e in detector.takeNewEvents()) {
                    val st = sliceFor(rolling, e.startSec, e.endSec, elapsed)
                    val absMs = startMs + (e.startSec * 1000.0).toLong()
                    val name = store.segmentFileName(day, absMs, segSeq++)
                    store.putSegment(day, st.first, name, absMs)
                    events.add(e.toStored(name, 0.0, st.second))
                }

                liveRecordedSec = elapsed
                liveEventCount = events.size
                liveSnoreCount = detector.peakCount
            }
        } catch (e: Exception) {
            prefs.lastFailureReason = e.message ?: e.javaClass.simpleName
        } finally {
            isRunning = false
            try { rec.stop() } catch (_: Exception) {}
            rec.release()

            val (detEvents, quality) = detector.finalize()
            // 收尾阶段才确认的事件，缓冲里可能还留着，补落盘
            for (e in detEvents) {
                if (events.any { kotlin.math.abs(it.startSec - e.startSec) < 0.05 }) continue
                val st = sliceFor(rolling, e.startSec, e.endSec, elapsed)
                val absMs = startMs + (e.startSec * 1000.0).toLong()
                val name = store.segmentFileName(day, absMs, segSeq++)
                store.putSegment(day, st.first, name, absMs)
                events.add(e.toStored(name, 0.0, st.second))
            }

            val fullLenSec = fullWriter?.close() ?: 0.0
            saveNight(day, startMs, elapsed, events, quality, fullWriter, fullLenSec, sampler)
        }
    }

    /** 事件音频 = [开始前 10s, 结束后 5s]；返回 (PCM, 秒数) */
    private fun sliceFor(
        rolling: RollingAudioBuffer, startSec: Double, endSec: Double, elapsed: Double
    ): Pair<ShortArray, Double> {
        val from = (startSec - PRE_ROLL_SEC).coerceAtLeast(0.0)
        val to = (endSec + POST_ROLL_SEC).coerceAtMost(elapsed)
        val seg = rolling.slice(from, to)
        return seg to (seg.size.toDouble() / DspConfig.SAMPLE_RATE)
    }

    private fun pickSource(): Int {
        val candidates = intArrayOf(
            MediaRecorder.AudioSource.UNPROCESSED,
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC
        )
        for (s in candidates) {
            if (s == 0) continue
            try {
                val t = AudioRecord(
                    s, DspConfig.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT, 4096
                )
                val ok = t.state == AudioRecord.STATE_INITIALIZED
                t.release()
                if (ok) return s
            } catch (_: Exception) {}
        }
        return MediaRecorder.AudioSource.VOICE_RECOGNITION
    }

    private fun saveNight(
        day: String,
        startMs: Long,
        elapsed: Double,
        events: List<StoredEvent>,
        quality: NightQuality,
        fullWriter: PcmStreamWriter?,
        fullLenSec: Double,
        sampler: EnvelopeSampler
    ) {
        if (elapsed < 30.0) return     // 太短不记，避免垃圾数据

        val fullAudioName = if (fullWriter != null && fullLenSec > 0) store.fullAudioFileName(day) else null

        val rec = NightRecord(
            date = day,
            startMillis = startMs,
            endMillis = System.currentTimeMillis(),
            recordedSec = elapsed,
            events = events.sortedBy { it.startSec },
            quality = quality,
            peakCount = quality.peakCount,
            snoreActiveSec = quality.activeFraction * elapsed,
            envelope = sampler.snapshot(),
            envelopePointSec = sampler.pointSecInterval,
            fullAudioFile = fullAudioName
        )
        store.save(rec)

        if (quality.ok) {
            prefs.lastFailureReason = ""
            prefs.streakDays = prefs.streakDays + 1
        } else {
            prefs.lastFailureReason = quality.reason()
        }
        prefs.lastRunMillis = startMs

        MorningNotifier.schedule(this, prefs.morningNotifyMin)
    }
}
