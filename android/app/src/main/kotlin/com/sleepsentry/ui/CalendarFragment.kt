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
 * 日历页：每晚一个色块，颜色表示那晚的严重程度。
 *
 * 这是"长期趋势"最直观的看法 —— 一眼扫过去就知道哪几晚特别糟、有没有在变好，
 * 比翻单晚报告有用得多。点某天看当天概要，点按钮跳到报告页看完整内容。
 */
class CalendarFragment : Fragment() {

    private lateinit var store: NightStore
    private lateinit var cal: MonthCalendarView
    private lateinit var legend: TextView
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
        legend = view.findViewById(R.id.legend)
        dayTitle = view.findViewById(R.id.dayTitle)
        dayState = view.findViewById(R.id.dayState)
        dayDetail = view.findViewById(R.id.dayDetail)
        dayReportBtn = view.findViewById(R.id.dayReportBtn)
        trend = view.findViewById(R.id.trend)

        legend.text = "颜色越深越严重 · " +
            "灰=无记录　浅灰=信号不足　绿=正常　黄=轻度　橙=中度　红=重度"

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
        val perHour = HashMap<Int, Double>()
        for (r in all) {
            val d = MonthGrid.parseDate(r.date) ?: continue
            if (d.first != y || d.second != m) continue
            val day = d.third
            perHour[day] = r.eventsPerHour
            states[day] = DayStateMapper.of(r.eventsPerHour, true, r.quality.ok)
        }
        cal.setData(states, perHour)
        trend.setData(all)
        onDayPicked(selectedDate?.let { MonthGrid.parseDate(it)?.third } ?: todayDayOf(y, m))
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
                append("疑似事件 ${rec.events.size} 次 · 每小时 ${"%.1f".format(rec.eventsPerHour)} 次\n")
                append("鼾声 ${rec.peakCount} 次 · 有效记录 ${MorningNotifier.fmtDur(rec.recordedSec)}\n")
                append("分级：${rec.level.label}（${rec.level.advice}）")
                rec.worstEvent?.let { append("\n最长静默 ${it.silenceSec.roundToInt()} 秒") }
            }
        }
        dayReportBtn.isEnabled = true
    }

    private fun colorOf(st: MonthCalendarView.DayState): Int = when (st) {
        MonthCalendarView.DayState.NO_RECORD -> R.color.textDim
        MonthCalendarView.DayState.INSUFFICIENT -> R.color.textDim
        MonthCalendarView.DayState.NORMAL -> R.color.good
        MonthCalendarView.DayState.MILD -> R.color.warn
        MonthCalendarView.DayState.MODERATE -> R.color.bad
        MonthCalendarView.DayState.SEVERE -> R.color.bad
    }

    private fun todayDayOf(y: Int, m: Int): Int {
        val c = Calendar.getInstance()
        return if (c.get(Calendar.YEAR) == y && c.get(Calendar.MONTH) == m) c.get(Calendar.DAY_OF_MONTH) else 1
    }

}
