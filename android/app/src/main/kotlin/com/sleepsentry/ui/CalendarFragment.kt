package com.sleepsentry.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.sleepsentry.R
import com.sleepsentry.capture.MorningNotifier
import com.sleepsentry.store.NightRecord
import com.sleepsentry.store.NightStore
import com.sleepsentry.util.Prefs
import java.util.Calendar
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 日历页：每晚记录状态和声音疑似片段数量。
 *
 * 这是"长期趋势"最直观的看法 —— 一眼扫过去就知道哪几晚特别糟、有没有在变好，
 * 比翻单晚报告有用得多。点某天看当天概要，点按钮跳到报告页看完整内容。
 */
class CalendarFragment : Fragment() {

    private lateinit var store: NightStore
    private lateinit var cal: MonthCalendarView
    private lateinit var legendHost: android.widget.LinearLayout
    private lateinit var dayTitle: TextView
    private lateinit var dayState: TextView
    private lateinit var dayDetail: TextView
    private lateinit var dayReportBtn: Button
    private lateinit var trend: TrendView

    private var selectedDate: String? = null

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View =
        layoutInflater.inflate(R.layout.fragment_calendar, c, false)

    override fun onViewCreated(view: View, s: Bundle?) {
        store = NightStore(requireContext())
        cal = view.findViewById(R.id.calendar)
        legendHost = view.findViewById(R.id.legendHost)
        dayTitle = view.findViewById(R.id.dayTitle)
        dayState = view.findViewById(R.id.dayState)
        dayDetail = view.findViewById(R.id.dayDetail)
        dayReportBtn = view.findViewById(R.id.dayReportBtn)
        trend = view.findViewById(R.id.trend)

        buildLegend(legendHost)

        cal.onMonthChange = { dir ->
            val c = Calendar.getInstance()
            c.set(cal.currentYear(), cal.currentMonth() + dir, 1)
            val (ny, nm) = MonthGrid.shiftMonth(cal.currentYear(), cal.currentMonth(), dir)
            cal.setMonth(ny, nm)
            refresh()
        }
        cal.onDayClick = { day -> onDayPicked(day) }
        dayReportBtn.setOnClickListener {
            val d = selectedDate
            if (d == null) {
                (activity as? MainActivity)?.showTab(R.id.nav_report)
            } else {
                ReportSelection.date = d
                (activity as? MainActivity)?.showTab(R.id.nav_report)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        val all: List<NightRecord> = store.list()
        val y = cal.currentYear()
        val m = cal.currentMonth()
        val states = HashMap<Int, MonthCalendarView.DayState>()
        val counts = HashMap<Int, Int>()
        for (r in all) {
            val d = MonthGrid.parseDate(r.date) ?: continue
            if (d.first != y || d.second != m) continue
            val day = d.third
            counts[day] = r.events.size
            states[day] = DayStateMapper.of(r.eventsPerHour, true, r.quality.ok)
        }
        cal.setData(states, counts)
        trend.setData(all)
        onDayPicked(selectedDate?.let { MonthGrid.parseDate(it)?.third } ?: todayDayOf(y, m))
    }

    /**
     * 图例用真实的色块 + 文案，而不是一句话描述。
     * 颜色直接从 CalendarStyle 取，保证图例和日历里的颜色**永远一致**
     * —— 否则改了配色忘了改图例，用户会按错的刻度理解。
     */
    private fun buildLegend(host: android.widget.LinearLayout) {
        val ctx = requireContext()
        val pad = (6 * ctx.resources.displayMetrics.density).toInt()
        val size = (16 * ctx.resources.displayMetrics.density).toInt()
        host.removeAllViews()
        val pairs = listOf(
            MonthCalendarView.DayState.NO_RECORD to "无记录",
            MonthCalendarView.DayState.INSUFFICIENT to "信号不足",
            MonthCalendarView.DayState.NORMAL to "有记录"
        )
        pairs.chunked(3).forEach { rowItems ->
            val row = android.widget.LinearLayout(ctx).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
            }
            rowItems.forEach { (st, label) ->
                val sw = android.view.View(ctx).apply {
                    layoutParams = android.widget.LinearLayout.LayoutParams(size, size).apply {
                        marginEnd = (6 * ctx.resources.displayMetrics.density).toInt()
                    }
                    setBackgroundColor(CalendarStyle.colorFor(st))
                }
                val tv = android.widget.TextView(ctx).apply {
                    text = label
                    textSize = 12f
                    setTextColor(CalendarStyle.textColorFor(CalendarStyle.NO_RECORD))
                    layoutParams = android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { marginEnd = (14 * ctx.resources.displayMetrics.density).toInt() }
                }
                row.addView(sw)
                row.addView(tv)
            }
            host.addView(row)
        }
    }

    private fun onDayPicked(day: Int) {
        cal.select(day)
        val y = cal.currentYear()
        val m = cal.currentMonth()
        val last = MonthGrid.daysInMonth(y, m)
        val safe = day.coerceIn(1, last)
        val dateStr = MonthGrid.formatDate(y, m, safe)
        val rec = store.load(dateStr)

        dayTitle.text = dateStr
        if (rec == null) {
            dayState.text = "无记录"
            dayState.setTextColor(ContextCompat.getColor(requireContext(), R.color.textDim))
            dayDetail.text = "这天没有录音数据"
            selectedDate = null
            dayReportBtn.isEnabled = false
            return
        }
        selectedDate = dateStr
        val st = DayStateMapper.of(rec.eventsPerHour, true, rec.quality.ok)
        dayState.text = DayStateMapper.label(st)
        dayState.setTextColor(ContextCompat.getColor(requireContext(), colorOf(st)))
        dayDetail.text = buildString {
            if (!rec.quality.ok) {
                append("信号不足：${rec.quality.reason()}\n")
                append("有效记录 ${MorningNotifier.fmtDur(rec.recordedSec)}")
            } else {
                append("声音疑似片段 ${rec.events.size} 次 · 每小时录音 ${"%.1f".format(rec.eventsPerHour)} 个\n")
                append("鼾声 ${rec.peakCount} 次 · 有效记录 ${MorningNotifier.fmtDur(rec.recordedSec)}\n")
                append("声音筛查结果仅供观察，不等同于临床诊断")
                rec.worstEvent?.let { append("\n最长静默 ${it.silenceSec.roundToInt()} 秒") }
            }
        }
        dayReportBtn.isEnabled = true
    }

    private fun colorOf(st: MonthCalendarView.DayState): Int = when (st) {
        MonthCalendarView.DayState.NO_RECORD -> R.color.textDim
        MonthCalendarView.DayState.INSUFFICIENT -> R.color.textDim
        MonthCalendarView.DayState.NORMAL -> R.color.brand
        MonthCalendarView.DayState.MILD -> R.color.brand
        MonthCalendarView.DayState.MODERATE -> R.color.brand
        MonthCalendarView.DayState.SEVERE -> R.color.brand
    }

    private fun todayDayOf(y: Int, m: Int): Int {
        val c = Calendar.getInstance()
        return if (c.get(Calendar.YEAR) == y && c.get(Calendar.MONTH) == m) c.get(Calendar.DAY_OF_MONTH) else 1
    }

}
