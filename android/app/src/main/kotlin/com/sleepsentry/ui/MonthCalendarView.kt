package com.sleepsentry.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.sleepsentry.R
import com.sleepsentry.dsp.Severity
import java.util.Calendar

/**
 * 月历视图：每晚一个色块，颜色深浅 = 当晚疑似呼吸暂停的严重程度。
 *
 * v1.2.0 用**白字画浅灰格子**，对比度只有 1.14:1，用户反馈"看不清日期"。
 * 两处结构性修正：
 *  1. **文字颜色按背景亮度自适应**（见 CalendarStyle），所有状态都 ≥ WCAG AA 4.5:1，
 *     并有单元测试钉死，日期在夜间色块上仍然清楚
 *  2. **格里直接显示当晚的事件次数**，成为数据日历而不是纯色块，
 *     色弱用户不靠颜色也能读懂
 *
 * 布局遵循标准日历：月份标题居中 + 左右翻页、星期表头、6×7 网格、
 * 今天加圈、选中加粗描边。
 */
class MonthCalendarView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    enum class DayState { NO_RECORD, INSUFFICIENT, NORMAL, MILD, MODERATE, SEVERE }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val dayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val countPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER; textSize = 22f
    }
    private val weekPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.textDim); textSize = 24f; textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val weekEndPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.textFaint); textSize = 24f; textAlign = Paint.Align.CENTER
    }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.text); textSize = 34f; textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = context.getColor(R.color.brandDim); strokeWidth = 3f
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val todayRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2.5f; color = context.getColor(R.color.brandDim)
    }
    private val selectedRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 3.5f; color = context.getColor(R.color.brand)
    }

    private var year = 0
    private var month = 0
    private val states = HashMap<Int, DayState>()
    private val counts = HashMap<Int, Int>()   // 当晚事件数，格内显示

    private var selectedDay = -1
    var onDayClick: ((Int) -> Unit)? = null
    var onMonthChange: ((Int) -> Unit)? = null

    private val arrowLeftHit = RectF()
    private val arrowRightHit = RectF()
    private val cellsRect = RectF()
    private var cellW = 0f
    private var cellH = 0f

    init {
        val c = Calendar.getInstance()
        year = c.get(Calendar.YEAR)
        month = c.get(Calendar.MONTH)
        setBackgroundColor(Color.TRANSPARENT)
    }

    fun setMonth(y: Int, m: Int) { year = y; month = m; invalidate() }

    fun setData(dayStates: Map<Int, DayState>, dayCounts: Map<Int, Int>) {
        states.clear(); states.putAll(dayStates)
        counts.clear(); counts.putAll(dayCounts)
        invalidate()
    }

    fun select(day: Int) { selectedDay = day; invalidate() }
    fun currentYear(): Int = year
    fun currentMonth(): Int = month
    fun title(): String = "${year}年${month + 1}月"
    fun stateOf(day: Int): DayState = states[day] ?: DayState.NO_RECORD
    fun countOf(day: Int): Int = counts[day] ?: 0
    fun hasData(day: Int): Boolean = states.containsKey(day)

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

        val padX = 8f
        val headerH = 52f
        val weekH = 34f
        val arrowW = 64f
        val cols = MonthGrid.COLS

        // 月份标题 + 翻页箭头
        canvas.drawText(title(), w / 2, 40f, titlePaint)
        val arrowY = headerH / 2
        val leftX = padX + arrowW / 2
        canvas.drawLine(leftX + 4f, arrowY - 7f, leftX - 3f, arrowY, arrowPaint)
        canvas.drawLine(leftX - 3f, arrowY, leftX + 4f, arrowY + 7f, arrowPaint)
        val rightX = w - padX - arrowW / 2
        canvas.drawLine(rightX - 4f, arrowY - 7f, rightX + 3f, arrowY, arrowPaint)
        canvas.drawLine(rightX + 3f, arrowY, rightX - 4f, arrowY + 7f, arrowPaint)
        arrowLeftHit.set(0f, 0f, padX * 2 + arrowW, headerH)
        arrowRightHit.set(w - padX * 2 - arrowW, 0f, w, headerH)

        // 星期表头
        val week = arrayOf("一", "二", "三", "四", "五", "六", "日")
        val gridLeft = padX
        val gridW = w - padX * 2
        cellW = gridW / cols
        for (i in 0 until cols) {
            canvas.drawText(
                week[i], gridLeft + cellW * (i + 0.5f), headerH + weekH - 10f,
                if (i >= 5) weekEndPaint else weekPaint
            )
        }

        val gridTop = headerH + weekH
        val rows = 6
        cellH = ((h - gridTop) / rows).coerceAtLeast(30f)

        val today = Calendar.getInstance()
        val isThisMonth = today.get(Calendar.YEAR) == year && today.get(Calendar.MONTH) == month
        val todayDay = today.get(Calendar.DAY_OF_MONTH)

        val days = MonthGrid.daysInMonth(year, month)
        for (d in 1..days) {
            val slot = MonthGrid.slotOf(d, year, month)
            val r = MonthGrid.rowOf(slot)
            val c = MonthGrid.colOf(slot)
            val cx = gridLeft + cellW * c
            val cy = gridTop + cellH * r
            val inset = (minOf(cellW, cellH) * 0.10f)
            val rect = RectF(
                cx + inset, cy + inset,
                cx + cellW - inset, cy + cellH - inset
            )

            val state = stateOf(d)
            fillPaint.color = CalendarStyle.colorFor(state)
            canvas.drawRoundRect(rect, 12f, 12f, fillPaint)

            if (isThisMonth && d == todayDay) canvas.drawRoundRect(rect, 12f, 12f, todayRing)
            if (d == selectedDay) canvas.drawRoundRect(rect, 12f, 12f, selectedRing)

            // 文字颜色随背景亮度自适应，确保日期数字始终清楚
            val tc = CalendarStyle.bestTextColor(state)
            dayPaint.color = tc
            countPaint.color = tc
            dayPaint.textSize = (cellH * 0.34f).coerceIn(20f, 34f)
            countPaint.textSize = (cellH * 0.22f).coerceIn(13f, 22f)

            val cxm = rect.centerX()
            if (hasData(d)) {
                canvas.drawText(d.toString(), cxm, rect.centerY() + dayPaint.textSize * 0.14f, dayPaint)
                val n = countOf(d)
                if (n > 0) {
                    countPaint.alpha = 205
                    canvas.drawText("$n", cxm, rect.bottom - inset * 1.4f, countPaint)
                    countPaint.alpha = 255
                }
            } else {
                canvas.drawText(d.toString(), cxm, rect.centerY() + dayPaint.textSize * 0.36f, dayPaint)
            }
        }
        cellsRect.set(gridLeft, gridTop, gridLeft + gridW, gridTop + cellH * rows)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_UP) return true
        if (arrowLeftHit.contains(event.x, event.y)) {
            onMonthChange?.invoke(-1); performClick(); return true
        }
        if (arrowRightHit.contains(event.x, event.y)) {
            onMonthChange?.invoke(1); performClick(); return true
        }
        if (!cellsRect.contains(event.x, event.y)) return true
        val day = MonthGrid.dayAt(
            event.x, event.y, cellsRect.left, cellsRect.top, cellW, cellH, year, month
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

/** 记录 → 色块状态 */
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
