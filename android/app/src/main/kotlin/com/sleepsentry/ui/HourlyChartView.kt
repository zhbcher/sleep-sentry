package com.sleepsentry.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.sleepsentry.store.HourlyBuckets
import kotlin.math.max

/**
 * 每小时录音中检出的声音疑似片段数。
 *
 * 为什么按"钟点"而不是"第几小时"分桶：睡眠常常跨午夜（23:00 → 07:00），
 * 按钟点展示声音片段分布；用相对小时会错位。
 *
 * 只显示有录音覆盖的钟点段，其余留空 —— 不然会让人以为那些时段也测过。
 */
class HourlyChartView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val bandPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1D3442.toInt() }
    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1f; color = 0xFF334457.toInt()
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF94A5B7.toInt(); textSize = 19f
    }
    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF94A5B7.toInt(); textSize = 22f
    }
    private val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF94A5B7.toInt(); textSize = 26f; textAlign = Paint.Align.CENTER
    }
    private val hitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x3373D5B2 }

    private var counts = IntArray(HourlyBuckets.HOURS)
    private var covered = IntArray(2)

    /** 点击某根柱子时回调（钟点 0..23） */
    var onHourClick: ((Int) -> Unit)? = null

    fun setData(buckets: IntArray, coveredRange: IntArray) {
        counts = if (buckets.size == HourlyBuckets.HOURS) {
            buckets.copyOf()
        } else {
            IntArray(HourlyBuckets.HOURS)
        }
        covered = coveredRange
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        try {
            drawContent(canvas)
        } catch (e: Exception) {
            android.util.Log.w("HourlyChartView", "绘制失败，已跳过柱状图", e)
        }
    }

    private fun drawContent(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val padL = 10f
        val padR = 10f
        val padT = 15f
        val padB = 24f
        val plotW = w - padL - padR
        val plotH = h - padT - padB
        if (plotW <= 0f || plotH <= 0f) return

        val total = counts.sum()
        if (total == 0) {
            canvas.drawText("这一晚没有记录到疑似事件", w / 2, h / 2, emptyPaint)
            return
        }

        val slot = plotW / HourlyBuckets.HOURS
        val maxV = max(1, counts.maxOrNull() ?: 1)

        // 录音覆盖时段底纹；跨午夜时拆成两段（23→24 与 0→end）
        val s = covered.getOrElse(0) { 0 }.coerceIn(0, 23)
        val e = covered.getOrElse(1) { 23 }.coerceIn(0, 23)
        fun band(fromH: Int, toH: Int) {
            val l = padL + fromH * slot
            val r = padL + (toH + 1) * slot
            canvas.drawRect(
                l, padT - 4f, minOf(r, padL + plotW), padT + plotH, bandPaint
            )
        }
        if (s <= e) band(s, e) else { band(s, 23); band(0, e) }

        // 基线
        canvas.drawLine(padL, padT + plotH, padL + plotW, padT + plotH, axisPaint)

        for (hh in 0 until HourlyBuckets.HOURS) {
            val c = counts[hh]
            val cx = padL + hh * slot + slot / 2
            if (c > 0) {
                val bh = (c.toFloat() / maxV) * plotH
                val bw = slot * 0.62f
                barPaint.color = when {
                    c <= 1 -> 0xFF73D5B2.toInt()
                    c <= 3 -> 0xFFE9C57E.toInt()
                    else -> 0xFFFF9C81.toInt()
                }
                canvas.drawRoundRect(
                    RectF(cx - bw / 2, padT + plotH - bh, cx + bw / 2, padT + plotH),
                    3f, 3f, barPaint
                )
                if (maxV <= 6) {
                    canvas.drawText(
                        c.toString(), cx - barPaint.measureText(c.toString()) / 2,
                        padT + plotH - bh - 4f, textPaint
                    )
                }
            }
            // 每 3 个钟点标一次，避免 24 个标签挤成一团
            if (hh % 3 == 0) {
                val label = "%02d".format(hh)
                canvas.drawText(
                    label, cx - textPaint.measureText(label) / 2, h - 6f, textPaint
                )
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action != MotionEvent.ACTION_UP) return true
        val w = width.toFloat()
        if (w <= 0f) return true
        val padL = 10f
        val padR = 10f
        val plotW = w - padL - padR
        if (plotW <= 0f) return true
        val slot = plotW / HourlyBuckets.HOURS
        val idx = ((event.x - padL) / slot).toInt()
        if (idx in 0 until HourlyBuckets.HOURS && counts[idx] > 0) {
            onHourClick?.invoke(idx)
            performClick()
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
