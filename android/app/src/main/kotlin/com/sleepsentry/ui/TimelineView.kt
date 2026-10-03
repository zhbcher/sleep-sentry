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
        style = Paint.Style.FILL; color = 0xFF9BB7CC.toInt()
    }
    private val markPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = 0xFFC25A3E.toInt()
    }
    private val markStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2f; color = 0xFF8C3A26.toInt()
    }
    private val hitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = 0x33C25A3E
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF6B7280.toInt(); textSize = 24f
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
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        val labelH = 28f
        val chartTop = 8f
        val chartBottom = h - labelH

        // 背景
        canvas.drawColor(Color.WHITE)
        val frame = RectF(0f, chartTop, w, chartBottom)
        canvas.drawRoundRect(frame, 8f, 8f, Paint().apply { color = 0xFFF2F5F8.toInt() })

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

        // 事件标记
        events.forEachIndexed { i, e ->
            val x1 = (e.startSec / recordedSec).toFloat() * w
            val x2 = (e.endSec / recordedSec).toFloat() * w
            val r = RectF(
                x1.coerceIn(0f, w - 2f), chartTop + 4f,
                x2.coerceIn(x1 + 3f, w), chartBottom - 4f
            )
            if (i == selected) canvas.drawRoundRect(r, 6f, 6f, hitPaint)
            canvas.drawRoundRect(r, 6f, 6f, if (i == selected) markStroke else markPaint)
        }

        // 时间刻度
        val hours = (recordedSec / 3600.0).toInt().coerceAtLeast(1)
        for (hh in 0..hours) {
            val x = (hh / hours.toFloat()) * w
            canvas.drawText("+${hh}h", x + 4f, h - 8f, textPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_UP) return true
        val w = width.toFloat()
        val best = -1
        var bestD = 1e9f
        events.forEachIndexed { i, e ->
            val x1 = (e.startSec / recordedSec).toFloat() * w
            val x2 = (e.endSec / recordedSec).toFloat() * w
            val x = event.x
            if (x >= x1 - 20f && x <= x2 + 20f) {
                val d = kotlin.math.abs(x - (x1 + x2) / 2)
                if (d < bestD) { bestD = d; }
                if (best == -1) { /* keep first */ }
            }
        }
        // 重新取最近的一个
        var pick = -1
        var pd = 1e9f
        events.forEachIndexed { i, e ->
            val x1 = (e.startSec / recordedSec).toFloat() * w
            val x2 = (e.endSec / recordedSec).toFloat() * w
            if (event.x >= x1 - 20f && event.x <= x2 + 20f) {
                val d = kotlin.math.abs(event.x - (x1 + x2) / 2)
                if (d < pd) { pd = d; pick = i }
            }
        }
        selected = pick
        invalidate()
        if (pick >= 0) onEventClick?.invoke(pick)
        return true
    }
}