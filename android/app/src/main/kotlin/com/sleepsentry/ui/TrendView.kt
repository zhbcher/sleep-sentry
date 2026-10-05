package com.sleepsentry.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.sleepsentry.store.NightRecord
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 近 30 晚趋势。
 *
 * 刻意不比"别人"，只比"你自己" —— 一切对比都跟自己比，
 * 这是个人健康记录 App 与医疗器械最根本的差别。
 */
class TrendView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : View(context, attrs, defStyle) {

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1f; color = 0xFF334457.toInt()
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF94A5B7.toInt(); textSize = 22f
    }
    private val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF94A5B7.toInt(); textSize = 26f; textAlign = Paint.Align.CENTER
    }
    private val datePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF94A5B7.toInt(); textSize = 18f
    }

    private var nights: List<NightRecord> = emptyList()

    fun setData(list: List<NightRecord>) {
        nights = list.sortedByDescending { it.startMillis }.take(30).reversed()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return
        val padL = 8f
        val padR = 8f
        val padT = 26f
        val padB = 26f

        if (nights.isEmpty()) {
            canvas.drawText("还没有足够的记录", w / 2, h / 2, emptyPaint)
            return
        }

        val maxV = max(10.0, nights.maxOf { it.eventsPerHour }).toFloat()
        // 仅展示个人声音疑似片段趋势，不使用临床 AHI 分级参考线。
        gridPaint.color = 0xFF334457.toInt()
        canvas.drawLine(padL, padT + (h - padT - padB) / 2f, w - padR,
            padT + (h - padT - padB) / 2f, gridPaint)

        val n = nights.size
        val slot = (w - padL - padR) / n
        val bw = slot * 0.62f
        for (i in 0 until n) {
            val r = nights[i]
            val v = r.eventsPerHour.toFloat()
            val bh = max(2f, (v / maxV) * (h - padT - padB))
            val x = padL + i * slot + (slot - bw) / 2
            val y = h - padB - bh
            barPaint.color = if (r.quality.ok) 0xFF73D5B2.toInt() else 0xFF526276.toInt()
            canvas.drawRoundRect(RectF(x, y, x + bw, h - padB), 3f, 3f, barPaint)
        }
        fun compactDate(date: String): String = date.takeLast(5).replace('-', '/')
        datePaint.textAlign = Paint.Align.LEFT
        canvas.drawText(compactDate(nights.first().date), padL + 2f, h - 4f, datePaint)
        datePaint.textAlign = Paint.Align.RIGHT
        canvas.drawText(compactDate(nights.last().date), w - padR, h - 4f, datePaint)
    }
}
