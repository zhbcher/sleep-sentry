package com.sleepsentry.ui

import android.Manifest
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.sleepsentry.R
import com.sleepsentry.capture.MorningNotifier
import com.sleepsentry.capture.SentryService
import com.sleepsentry.dsp.Severity
import com.sleepsentry.store.NightRecord
import com.sleepsentry.store.NightStore
import com.sleepsentry.store.StoredEvent
import com.sleepsentry.util.PcmPlayer
import com.sleepsentry.util.Prefs
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private lateinit var prefs: Prefs
    private lateinit var store: NightStore
    private val player = PcmPlayer()
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var stateText: TextView
    private lateinit var liveText: TextView
    private lateinit var streakText: TextView
    private lateinit var warnText: TextView
    private lateinit var toggleBtn: Button
    private lateinit var windowText: TextView
    private lateinit var morningText: TextView
    private lateinit var fullAudioSwitch: Switch
    private lateinit var emptyText: TextView
    private lateinit var reportCard: View
    private lateinit var reportHeadline: TextView
    private lateinit var reportSub: TextView
    private lateinit var reportLevel: TextView
    private lateinit var eventList: LinearLayout
    private lateinit var timeline: TimelineView
    private lateinit var trend: TrendView
    private lateinit var lastNightHeader: TextView

    private var current: NightRecord? = null

    private val ticker = object : Runnable {
        override fun run() {
            updateLive()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)
        store = NightStore(this)

        stateText = findViewById(R.id.stateText)
        liveText = findViewById(R.id.liveText)
        streakText = findViewById(R.id.streakText)
        warnText = findViewById(R.id.warnText)
        toggleBtn = findViewById(R.id.toggleBtn)
        windowText = findViewById(R.id.windowText)
        morningText = findViewById(R.id.morningText)
        fullAudioSwitch = findViewById(R.id.fullAudioSwitch)
        emptyText = findViewById(R.id.emptyText)
        reportCard = findViewById(R.id.reportCard)
        reportHeadline = findViewById(R.id.reportHeadline)
        reportSub = findViewById(R.id.reportSub)
        reportLevel = findViewById(R.id.reportLevel)
        eventList = findViewById(R.id.eventList)
        timeline = findViewById(R.id.timeline)
        trend = findViewById(R.id.trend)
        lastNightHeader = findViewById(R.id.lastNightHeader)

        toggleBtn.setOnClickListener { onToggle() }
        findViewById<View>(R.id.windowRow).setOnClickListener {
            pickTime(prefs.windowStartMin) { m ->
                prefs.windowStartMin = m
                renderSettings()
            }
        }
        findViewById<View>(R.id.morningRow).setOnClickListener {
            pickTime(prefs.morningNotifyMin) { m ->
                prefs.morningNotifyMin = m
                renderSettings()
                MorningNotifier.schedule(this, m)
            }
        }
        fullAudioSwitch.setOnCheckedChangeListener { _, v ->
            prefs.keepFullAudio = v
            if (v) toast("整夜音频每晚约 110MB，环形池只保留最近 2 晚")
        }
        timeline.onEventClick = { idx -> current?.events?.getOrNull(idx)?.let { playEvent(it) } }

        renderSettings()
    }

    override fun onResume() {
        super.onResume()
        renderSettings()
        renderReport()
        renderTrend()
        if (prefs.enabled) requestPermissionsIfNeeded()
        handler.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
        player.stop()
    }

    // ------------------------------------------------------------ 权限

    private fun has(p: String) =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun requestPermissionsIfNeeded() {
        val need = ArrayList<String>()
        if (!has(Manifest.permission.RECORD_AUDIO)) need.add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33 && !has(Manifest.permission.POST_NOTIFICATIONS)) {
            need.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (need.isNotEmpty()) requestPermissions(need.toTypedArray(), 100)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (code == 100) {
            if (has(Manifest.permission.RECORD_AUDIO)) {
                toast("已授权。接上充电器、把手机放床头就能开始")
            } else {
                toast("没有麦克风权限，无法记录")
            }
        }
    }

    // ------------------------------------------------------------ 主界面动作

    private fun onToggle() {
        if (prefs.enabled) {
            prefs.enabled = false
            SentryService.stop(this)
            toast("已停止")
        } else {
            if (!has(Manifest.permission.RECORD_AUDIO)) {
                requestPermissionsIfNeeded()
                return
            }
            prefs.enabled = true
            prefs.lastFailureReason = ""
            MorningNotifier.schedule(this, prefs.morningNotifyMin)
            // 立即开一次，用户不用等到今晚才知道能不能跑
            SentryService.start(this)
            offerBatteryExempt()
            toast("已开启。插上充电器、把手机放床头就行")
        }
        renderSettings()
        updateLive()
    }

    /**
     * 国厂 ROM 的后台限制是本项目头号风险，所以首次开启就主动引导关掉。
     * 这一步做不好，录不到一小时就会被杀。
     */
    private fun offerBatteryExempt() {
        val pm = getSystemService(android.os.PowerManager::class.java)
        val exempt = pm?.isIgnoringBatteryOptimizations(packageName) ?: false
        if (exempt) return
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:$packageName"))
            )
        } catch (_: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: Exception) {
            }
        }
    }

    private fun pickTime(currentMin: Int, onPicked: (Int) -> Unit) {
        TimePickerDialog(
            this, { _, h, m -> onPicked(h * 60 + m) },
            currentMin / 60, currentMin % 60, true
        ).show()
    }

    // ------------------------------------------------------------ 渲染

    private fun renderSettings() {
        toggleBtn.text =
            getString(if (prefs.enabled) R.string.action_disable else R.string.action_enable)
        windowText.text = prefs.windowText()
        morningText.text = prefs.notifyText()
        if (fullAudioSwitch.isChecked != prefs.keepFullAudio) {
            fullAudioSwitch.isChecked = prefs.keepFullAudio
        }
        warnText.visibility = if (prefs.lastFailureReason.isBlank()) View.GONE else View.VISIBLE
        if (prefs.lastFailureReason.isNotBlank()) {
            warnText.text =
                "上次运行异常：${prefs.lastFailureReason}\n请检查：电池优化是否关闭、麦克风权限是否被收回。"
        }
        streakText.text = if (prefs.streakDays > 0) "已连续记录 ${prefs.streakDays} 晚" else ""
        updateLive()
    }

    private fun updateLive() {
        stateText.text = when {
            SentryService.isRunning -> getString(R.string.state_armed)
            prefs.enabled -> getString(R.string.state_waiting)
            else -> getString(R.string.state_idle)
        }
        liveText.text = if (SentryService.isRunning) {
            "已监听 ${MorningNotifier.fmtDur(SentryService.liveRecordedSec)} · " +
                    "疑似事件 ${SentryService.liveEventCount} 次 · " +
                    "呼吸声 ${SentryService.liveSnoreCount} 次"
        } else {
            ""
        }
        stateText.setTextColor(
            ContextCompat.getColor(
                this, if (SentryService.isRunning) R.color.good else R.color.brand
            )
        )
    }

    private fun renderReport() {
        val rec = store.latest()
        current = rec
        if (rec == null) {
            emptyText.visibility = View.VISIBLE
            reportCard.visibility = View.GONE
            lastNightHeader.text = getString(R.string.label_last_night)
            return
        }
        emptyText.visibility = View.GONE
        reportCard.visibility = View.VISIBLE
        lastNightHeader.text = "记录 · ${rec.date}"

        if (!rec.quality.ok) {
            reportHeadline.text = "信号不足，未作判定"
            reportSub.text =
                "${rec.quality.reason()}\n有效记录 ${MorningNotifier.fmtDur(rec.recordedSec)}"
            reportLevel.text = "换一夜再看看，或把手机往床头挪近一点"
            reportLevel.setTextColor(ContextCompat.getColor(this, R.color.warn))
        } else {
            reportHeadline.text = when {
                rec.events.isEmpty() -> "未检出疑似呼吸暂停"
                else -> "疑似呼吸暂停 ${rec.events.size} 次"
            }
            val startOfNight = SimpleDateFormat("HH:mm", Locale.US).format(Date(rec.startMillis))
            reportSub.text = buildString {
                append("有效记录 ${MorningNotifier.fmtDur(rec.recordedSec)}（约 ${startOfNight} 起）\n")
                append("鼾声 ${rec.peakCount} 次 · 有声占比 ${(rec.quality.activeFraction * 100).roundToInt()}%")
                if (rec.events.isNotEmpty()) {
                    append(" · 环境信噪 ${rec.quality.activeSnrMedianDb.roundToInt()}dB")
                }
            }
            val lv = rec.level
            reportLevel.text = "每小时 ${"%.1f".format(rec.eventsPerHour)} 次 · ${lv.label}\n${lv.advice}"
            reportLevel.setTextColor(
                ContextCompat.getColor(
                    this,
                    when (lv) {
                        Severity.Level.NORMAL -> R.color.good
                        Severity.Level.MILD -> R.color.warn
                        else -> R.color.bad
                    }
                )
            )
        }

        timeline.setData(rec.envelope, rec.events, rec.recordedSec)
        renderEventList(rec)
    }

    private fun renderEventList(rec: NightRecord) {
        eventList.removeAllViews()
        if (rec.events.isEmpty()) return
        val sdf = SimpleDateFormat("HH:mm:ss", Locale.US)
        val base = rec.startMillis
        val inflater = LayoutInflater.from(this)

        rec.events.sortedByDescending { it.silenceSec }.forEach { e ->
            val row = inflater.inflate(R.layout.item_event, eventList, false)
            row.findViewById<TextView>(R.id.eventTime).text =
                sdf.format(Date(base + (e.startSec * 1000).toLong()))
            row.findViewById<TextView>(R.id.eventDetail).text =
                "静默 ${e.silenceSec.roundToInt()} 秒 · 恢复吸气强度 ${e.prominenceDb.roundToInt()}dB"
            val btn = row.findViewById<Button>(R.id.eventPlay)
            if (e.audioFile == null) {
                btn.text = "无音频"
                btn.isEnabled = false
            } else {
                btn.setOnClickListener { playEvent(e) }
            }
            eventList.addView(row)
        }
    }

    private fun playEvent(e: StoredEvent) {
        val name = e.audioFile ?: return
        val f = store.audioFile(name)
        if (!f.exists()) {
            toast("这段音频已被环形池覆盖")
            return
        }
        player.play(
            f.readBytes(), e.audioStartInFileSec, e.audioLenSec,
            onDone = { handler.post { renderReport() } }
        )
    }

    private fun renderTrend() {
        trend.setData(store.list())
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}