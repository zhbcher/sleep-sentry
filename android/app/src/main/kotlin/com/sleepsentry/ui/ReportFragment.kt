package com.sleepsentry.ui

import android.graphics.Rect
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.sleepsentry.R
import com.sleepsentry.capture.MorningNotifier
import com.sleepsentry.dsp.Severity
import com.sleepsentry.store.HourlyBuckets
import com.sleepsentry.store.NightRecord
import com.sleepsentry.store.NightStore
import com.sleepsentry.store.StoredEvent
import com.sleepsentry.util.PcmPlayer
import com.sleepsentry.util.Prefs
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** 报告页：单晚详情、时间轴、每小时柱状图、事件列表与回放 */
class ReportFragment : Fragment() {

    private lateinit var prefs: Prefs
    private lateinit var store: NightStore
    private val player = PcmPlayer()

    private lateinit var title: TextView
    private lateinit var emptyText: TextView
    private lateinit var card: View
    private lateinit var headline: TextView
    private lateinit var sub: TextView
    private lateinit var level: TextView
    private lateinit var eventList: LinearLayout
    private lateinit var sortBtn: Button
    private lateinit var timeline: TimelineView
    private lateinit var hourly: HourlyChartView
    private lateinit var hourlyHeader: View
    private lateinit var hourlyHelp: View
    private lateinit var eventHeader: View

    private var current: NightRecord? = null

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View =
        layoutInflater.inflate(R.layout.fragment_report, c, false)

    override fun onViewCreated(view: View, s: Bundle?) {
        prefs = Prefs(requireContext())
        store = NightStore(requireContext())
        title = view.findViewById(R.id.reportTitle)
        emptyText = view.findViewById(R.id.emptyText)
        card = view.findViewById(R.id.reportCard)
        headline = view.findViewById(R.id.reportHeadline)
        sub = view.findViewById(R.id.reportSub)
        level = view.findViewById(R.id.reportLevel)
        eventList = view.findViewById(R.id.eventList)
        sortBtn = view.findViewById(R.id.sortBtn)
        timeline = view.findViewById(R.id.timeline)
        hourly = view.findViewById(R.id.hourlyChart)
        hourlyHeader = view.findViewById(R.id.hourlyHeader)
        hourlyHelp = view.findViewById(R.id.hourlyHelp)
        eventHeader = view.findViewById(R.id.eventHeader)

        sortBtn.setOnClickListener {
            prefs.sortEventsByTime = !prefs.sortEventsByTime
            current?.let { render(it) }
        }
        timeline.onEventClick = { idx -> current?.events?.getOrNull(idx)?.let { play(it) } }
        hourly.onHourClick = { hour -> scrollToHour(hour) }
    }

    override fun onResume() {
        super.onResume()
        load()
    }

    override fun onPause() {
        super.onPause()
        player.stop()
    }

    private fun load() {
        val want = ReportSelection.date
        val rec = if (want != null) store.load(want) else store.latest()
        if (rec == null) {
            current = null
            title.text = "报告"
            emptyText.visibility = View.VISIBLE
            card.visibility = View.GONE
            eventList.visibility = View.GONE
            hourly.visibility = View.GONE
            hourlyHeader.visibility = View.GONE
            hourlyHelp.visibility = View.GONE
            eventHeader.visibility = View.GONE
            return
        }
        render(rec)
    }

    private fun render(rec: NightRecord) {
        current = rec
        title.text = "报告 · ${rec.date}"
        emptyText.visibility = View.GONE
        card.visibility = View.VISIBLE
        eventList.visibility = View.VISIBLE
        hourly.visibility = View.VISIBLE
        hourlyHeader.visibility = View.VISIBLE
        hourlyHelp.visibility = View.VISIBLE
        eventHeader.visibility = View.VISIBLE

        if (!rec.quality.ok) {
            headline.text = "信号不足，未作判定"
            sub.text = "${rec.quality.reason()}\n有效记录 ${MorningNotifier.fmtDur(rec.recordedSec)}"
            level.text = "换一夜再看看，或把手机往床头挪近一点"
            level.setTextColor(ContextCompat.getColor(requireContext(), R.color.warn))
        } else {
            headline.text = when {
                rec.events.isEmpty() -> "未检出疑似呼吸暂停"
                else -> "疑似呼吸暂停 ${rec.events.size} 次"
            }
            val startOfNight = SimpleDateFormat("HH:mm", Locale.US).format(Date(rec.startMillis))
            sub.text = buildString {
                append("有效记录 ${MorningNotifier.fmtDur(rec.recordedSec)}（约 ${startOfNight} 起）\n")
                append("鼾声 ${rec.peakCount} 次 · 有声占比 ${(rec.quality.activeFraction * 100).roundToInt()}%")
                if (rec.events.isNotEmpty()) {
                    append(" · 环境信噪 ${rec.quality.activeSnrMedianDb.roundToInt()}dB")
                }
            }
            val lv = rec.level
            level.text = "每小时 ${"%.1f".format(rec.eventsPerHour)} 次 · ${lv.label}\n${lv.advice}"
            level.setTextColor(
                ContextCompat.getColor(requireContext(), when (lv) {
                    Severity.Level.NORMAL -> R.color.good
                    Severity.Level.MILD -> R.color.warn
                    else -> R.color.bad
                })
            )
        }

        timeline.setData(rec.envelope, rec.events, rec.recordedSec)
        hourly.setData(
            HourlyBuckets.count(rec.events.map { it.startSec }, rec.startMillis),
            HourlyBuckets.coveredRange(rec.recordedSec, rec.startMillis)
        )
        renderEventList(rec)
    }

    private fun renderEventList(rec: NightRecord) {
        eventList.removeAllViews()
        sortBtn.text = if (prefs.sortEventsByTime) "排序：时间" else "排序：严重程度"
        // 默认按时间先后（用户明确要求）；可切换成"最严重的排前面"
        val ordered = if (prefs.sortEventsByTime) {
            rec.events.sortedBy { it.startSec }
        } else {
            rec.events.sortedByDescending { it.silenceSec }
        }
        val sdf = SimpleDateFormat("HH:mm:ss", Locale.US)
        val inflater = LayoutInflater.from(requireContext())
        ordered.forEach { e ->
            val row = inflater.inflate(R.layout.item_event, eventList, false)
            row.findViewById<TextView>(R.id.eventTime).text =
                sdf.format(Date(rec.startMillis + (e.startSec * 1000.0).toLong()))
            row.findViewById<TextView>(R.id.eventDetail).text =
                "静默 ${e.silenceSec.roundToInt()} 秒 · 恢复吸气强度 ${e.prominenceDb.roundToInt()}dB"
            val btn = row.findViewById<Button>(R.id.eventPlay)
            if (e.audioFile == null || !store.audioFile(e.audioFile).exists()) {
                btn.text = "无音频"
                btn.isEnabled = false
            } else {
                btn.setOnClickListener { play(e) }
            }
            eventList.addView(row)
        }
    }

    private fun scrollToHour(hour: Int) {
        val rec = current ?: return
        val inHour = rec.events.filter { HourlyBuckets.localHour(
            rec.startMillis + (it.startSec * 1000.0).toLong()
        ) == hour }
        if (inHour.isEmpty()) {
            toast("%02d:00 这一段没有记录".format(hour))
            return
        }
        val worst = inHour.maxByOrNull { it.silenceSec }!!
        val ordered = if (prefs.sortEventsByTime) {
            rec.events.sortedBy { it.startSec }
        } else {
            rec.events.sortedByDescending { it.silenceSec }
        }
        val row = ordered.indexOf(worst)
        val v = if (row in 0 until eventList.childCount) eventList.getChildAt(row) else null
        if (v != null) {
            v.requestRectangleOnScreen(Rect(0, 0, v.width, v.height), true)
            v.setBackgroundResource(R.drawable.row_bg_highlight)
            v.postDelayed({ v.setBackgroundResource(R.drawable.row_bg) }, 1500)
        }
        toast("%02d:00 起 %d 次，最长静默 %d 秒".format(hour, inHour.size, worst.silenceSec.roundToInt()))
    }

    private fun play(e: StoredEvent) {
        val name = e.audioFile ?: return
        val f = store.audioFile(name)
        if (!f.exists()) {
            toast("这段音频已被清理或超出占用上限")
            return
        }
        player.play(f.readBytes(), e.audioStartInFileSec, e.audioLenSec, onDone = {})
    }

    private fun toast(s: String) = Toast.makeText(requireContext(), s, Toast.LENGTH_SHORT).show()
}
