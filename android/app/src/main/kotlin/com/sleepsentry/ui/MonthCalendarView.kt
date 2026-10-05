package com.sleepsentry.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.sleepsentry.dsp.Severity
import java.util.Calendar
import java.util.Locale

/**
 * 月历视图：每个日期一个色块，颜色深浅表示那晚疑似呼吸暂停的严重程度。
 *
 * 这是"长期趋势"最直观的呈现 —— 一次看一个月，
 * 哪几晚特别糟、是否在改善，比翻单晚报告有用得多。
 *
 * 颜色分档与报告页的分级完全一致（Severity.Level），不另立一套标准。
 */
class MonthCalendarView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    /** 某一天的状态 */
    enum class DayState { NO_RECORD, INSUFFICIENT, NORMAL, MILD, MODERATE, SEVERE }

    private val cellPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val dayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 26f; textAlign = Paint.Align.CENTER
    }
    private val headerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF9AA4B2.toInt(); textSize = 22f; textAlign = Paint.Align.CENTER
    }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF1A1C1E.toInt(); textSize = 30f; textAlign = Paint.Align.CENTER
    }
    private val todayRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 3f; color = 0xFF2C5F8A.toInt()
    }
    private val selectedRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 4f; color = 0xFFD08A3E.toInt()
    }

    private var year = 0
    private var month = 0            // 0..11
    private val states = HashMap<Int, DayState>()   // dayOfMonth -> state
    private val perHour = HashMap<Int, Double>()    // dayOfMonth -> eventsPerHour

    private var selectedDay = -1
    /** 点击某天（1..31），0 表示点了空白格 */
    var onDayClick: ((Int) -> Unit)? = null
    /** 点击左右箭头 */
    var onMonthChange: ((Int) -> Unit)? = null    // -1 上月, +1 下月

    private val monthNames = arrayOf(
        "1 月", "2 月", "3 月", "4 月", "5 月", "6 月",
        "7 月", "8 月", "9 月", "10 月", "11 月", "12 月"
    )

    init {
        val c = Calendar.getInstance()
        year = c.get(Calendar.YEAR)
        month = c.get(Calendar.MONTH)
    }

    fun setMonth(y: Int, m: Int) {
        year = y
        month = m
        invalidate()
    }

    fun setData(dayStates: Map<Int, DayState>, dayPerHour: Map<Int, Double>) {
        states.clear(); states.putAll(dayStates)
        perHour.clear(); perHour.putAll(dayPerHour)
        invalidate()
    }

    fun select(day: Int) {
        selectedDay = day
        invalidate()
    }

    fun currentYear(): Int = year
    fun currentMonth(): Int = month

    fun title(): String = "${year}年 ${month + 1}月"

    private fun colorFor(state: DayState): Int = when (state) {
        DayState.NO_RECORD -> 0xFFE8ECF1.toInt()
        DayState.INSUFFICIENT -> 0xFFCFD6DE.toInt()
        DayState.NORMAL -> 0xFF6FA98A.toInt()
        DayState.MILD -> 0xFFD9B96A.toInt()
        DayState.MODERATE -> 0xFFD08A7A.toInt()
        DayState.SEVERE -> 0xFFC05545.toInt()
    }

    fun stateOf(day: Int): DayState = states[day] ?: DayState.NO_RECORD

    override fun onDraw(canvas: Canvas) {
        try {
            drawContent(canvas)
        } catch (e: Exception) {
            android.util.Log.w("MonthCalendarView", "日历绘制失败", e)
        }
    }

    private fun drawContent(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val padX = 12f
        val headerH = 40f
        val weekH = 26f
        val arrowW = 44f

        // 年月 + 箭头
        canvas.drawText(title(), w / 2, 28f, titlePaint)
        canvas.drawText("‹", padX + arrowW / 2, 32f, headerPaint)
        canvas.drawText("›", w - padX - arrowW / 2, 32f, headerPaint)
        // 加大点击热区
        arrowLeftHit.set(padX, 0f, padX + arrowW, headerH)
        arrowRightHit.set(w - padX - arrowW, 0f, w - padX, headerH)

        val gridTop = headerH + weekH
        val cols = MonthGrid.COLS
        val cellW = (w - padX * 2) / cols
        val rows = 6
        val cellH = ((h - gridTop) / rows).coerceAtLeast(24f)

        // 星期表头
        val week = arrayOf("一", "二", "三", "四", "五", "六", "日")
        for (i in 0 until cols) {
            canvas.drawText(
                week[i], padX + cellW * (i + 0.5f), headerH + weekH - 8f, headerPaint
            )
        }

        val firstDow = MonthGrid.firstDayOfWeek(year, month)
        val daysInMonth = MonthGrid.daysInMonth(year, month)

        val today = Calendar.getInstance()
        val isThisMonth = today.get(Calendar.YEAR) == year && today.get(Calendar.MONTH) == month
        val todayDay = today.get(Calendar.DAY_OF_MONTH)

        for (d in 1..daysInMonth) {
            val slot = firstDow + d - 1
            val r = slot / cols
            val c = slot % cols
            val cx = padX + cellW * c
            val cy = gridTop + cellH * r
            val inset = (minOf(cellW, cellH) * 0.14f)
            val rect = RectF(
                cx + inset, cy + inset,
                cx + cellW - inset, cy + cellH - inset
            )
            cellPaint.color = colorFor(stateOf(d))
            canvas.drawRoundRect(rect, 10f, 10f, cellPaint)

            if (isThisMonth && d == todayDay) canvas.drawRoundRect(rect, 10f, 10f, todayRing)
            if (d == selectedDay) canvas.drawRoundRect(rect, 10f, 10f, selectedRing)

            val label = d.toString()
            canvas.drawText(label, rect.centerX(), rect.centerY() + 9f, dayPaint)

            // 右上角小圆点提示"这天有数据"
            if (perHour.containsKey(d)) {
                cellPaint.color = 0x66FFFFFF.toInt()
                canvas.drawCircle(rect.right - inset * 0.8f, rect.top + inset * 0.8f, 3.5f, cellPaint)
            }
        }
        cellsRect.set(padX, gridTop, w - padX, gridTop + cellH * rows)
        cellWValue = cellW
        cellHValue = cellH
    }

    private val arrowLeftHit = RectF()
    private val arrowRightHit = RectF()
    private val cellsRect = RectF()
    private var cellWValue = 0f
    private var cellHValue = 0f

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_UP) return true
        val x = event.x
        val y = event.y
        if (arrowLeftHit.contains(x, y)) { onMonthChange?.invoke(-1); performClick(); return true }
        if (arrowRightHit.contains(x, y)) { onMonthChange?.invoke(1); performClick(); return true }
        if (!cellsRect.contains(x, y)) return true

        val day = MonthGrid.dayAt(
            x, y, cellsRect.left, cellsRect.top, cellWValue, cellHValue, year, month
        )
        if (day > 0) {
            selectedDay = day
            invalidate()
            onDayClick?.invoke(day)
            performClick()
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}

/** 把某一晚记录映射成日历色块状态 */
object DayStateMapper {
    fun of(eventsPerHour: Double, hasRecord: Boolean, qualityOk: Boolean): MonthCalendarView.DayState {
        if (!hasRecord) return MonthCalendarView.DayState.NO_RECORD
        if (!qualityOk) return MonthCalendarView.DayState.INSUFFICIENT
        return when (Severity.levelOf(eventsPerHour)) {
            Severity.Level.NORMAL -> MonthCalendarView.DayState.NORMAL
            Severity.Level.MILD -> MonthCalendarView.DayState.MILD
            Severity.Level.MODERATE -> MonthCalendarView.DayState.MODERATE
            Severity.Level.SEVERE -> MonthCalendarView.DayState.SEVERE
        }
    }

    fun label(state: MonthCalendarView.DayState): String = when (state) {
        MonthCalendarView.DayState.NO_RECORD -> "无记录"
        MonthCalendarView.DayState.INSUFFICIENT -> "信号不足"
        MonthCalendarView.DayState.NORMAL -> "正常范围"
        MonthCalendarView.DayState.MILD -> "轻度"
        MonthCalendarView.DayState.MODERATE -> "中度"
        MonthCalendarView.DayState.SEVERE -> "重度"
    }
}