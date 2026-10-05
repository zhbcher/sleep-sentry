package com.sleepsentry.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.sleepsentry.store.StoredEvent
import kotlin.math.max
import kotlin.math.min

/**
 * 整夜时间轴：能量包络波形 + 事件标记。
 *
 * 这是报告页的核心 —— 用户一眼看出"哪一段最闹"，以及"憋气都集中在后半夜"。
 * 点一下事件标记可以回听。
 */
class TimelineView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private val envPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = 0xFF486C72.toInt()
    }
    private val markPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = 0xFFFF9C81.toInt()
    }
    private val markStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2f; color = 0xFFFFC9B8.toInt()
    }
    private val hitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = 0x44FF9C81
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF94A5B7.toInt(); textSize = 24f
    }

    private var envelope: FloatArray = FloatArray(0)
    private var events: List<StoredEvent> = emptyList()
    private var recordedSec: Double = 1.0
    private var selected = -1

    /** 点击某个事件时回调（index） */
    var onEventClick: ((Int) -> Unit)? = null

    fun setData(envelope: FloatArray, events: List<StoredEvent>, recordedSec: Double) {
        this.envelope = envelope
        this.events = events
        this.recordedSec = max(1.0, recordedSec)
        this.selected = -1
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        // 兜底：绘图异常绝不能带走整个 App —— 报告里的文字比图表重要得多。
        // 几何计算本身已由 TimelineGeometry 保证安全（并有 2 万次随机扫描测试），
        // 这里只是防止将来新增的绘制代码再引入同类问题。
        try {
            drawContent(canvas)
        } catch (e: Exception) {
            android.util.Log.w("TimelineView", "绘制失败，已跳过时间轴", e)
            canvas.drawColor(Color.TRANSPARENT)
        }
    }

    private fun drawContent(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        val labelH = 28f
        val chartTop = 8f
        val chartBottom = h - labelH

        // 背景
        val frame = RectF(0f, chartTop, w, chartBottom)
        canvas.drawRoundRect(frame, 12f, 12f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1A293C.toInt() })

        // 包络：把 dB 映射到高度，低能量压到底部
        if (envelope.isNotEmpty()) {
            var lo = Float.MAX_VALUE
            var hi = -Float.MAX_VALUE
            for (v in envelope) { lo = min(lo, v); hi = max(hi, v) }
            if (hi - lo < 3f) hi = lo + 3f
            val bw = w / envelope.size
            for (i in envelope.indices) {
                val norm = ((envelope[i] - lo) / (hi - lo)).coerceIn(0f, 1f)
                val bh = norm * (chartBottom - chartTop) * 0.92f
                canvas.drawRect(
                    i * bw, chartBottom - bh, (i + 1) * bw + 0.6f, chartBottom, envPaint
                )
            }
        }

        // 事件标记（几何计算交给 TimelineGeometry，那里保证不会出现下界>上界）
        events.forEachIndexed { i, e ->
            val b = TimelineGeometry.markBounds(e.startSec, e.endSec, recordedSec, w)
            val r = RectF(b[0], chartTop + 4f, b[1], chartBottom - 4f)
            if (i == selected) canvas.drawRoundRect(r, 6f, 6f, hitPaint)
            canvas.drawRoundRect(r, 6f, 6f, if (i == selected) markStroke else markPaint)
        }

        // 时间刻度
        val hours = (recordedSec / 3600.0).toInt().coerceIn(1, 24)
        for (hh in 0..hours) {
            val x = (hh / hours.toFloat()) * w
            canvas.drawText("+${hh}h", x + 4f, h - 8f, textPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_UP) return true
        val w = width.toFloat()
        // 取点击位置附近、离中心最近的那个事件（与绘制共用同一套几何，避免两处算出不同的位置）
        var pick = -1
        var bestDist = Float.MAX_VALUE
        events.forEachIndexed { i, e ->
            if (!TimelineGeometry.isHit(e.startSec, e.endSec, recordedSec, w, event.x)) return@forEachIndexed
            val b = TimelineGeometry.markBounds(e.startSec, e.endSec, recordedSec, w)
            val d = kotlin.math.abs(event.x - (b[0] + b[1]) / 2)
            if (d < bestDist) { bestDist = d; pick = i }
        }
        selected = pick
        invalidate()
        if (pick >= 0) onEventClick?.invoke(pick)
        return true
    }
}
