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
        // 参考线：AHI 分级线 5 / 15 / 30
        val refColor = intArrayOf(0xFF334457.toInt(), 0xFF725D39.toInt(), 0xFF75483F.toInt())
        floatArrayOf(5f, 15f, 30f).forEachIndexed { i, v ->
            if (v > maxV) return@forEachIndexed
            val y = padT + (1f - v / maxV) * (h - padT - padB)
            gridPaint.color = refColor[i]
            canvas.drawLine(padL, y, w - padR, y, gridPaint)
            canvas.drawText(v.roundToInt().toString(), padL + 2f, y - 4f, textPaint)
        }

        val n = nights.size
        val slot = (w - padL - padR) / n
        val bw = slot * 0.62f
        for (i in 0 until n) {
            val r = nights[i]
            val v = r.eventsPerHour.toFloat()
            val bh = max(2f, (v / maxV) * (h - padT - padB))
            val x = padL + i * slot + (slot - bw) / 2
            val y = h - padB - bh
            barPaint.color = when {
                !r.quality.ok -> 0xFF526276.toInt()
                r.eventsPerHour < 5f -> 0xFF73D5B2.toInt()
                r.eventsPerHour < 15f -> 0xFFE9C57E.toInt()
                else -> 0xFFFF9C81.toInt()
            }
            canvas.drawRoundRect(RectF(x, y, x + bw, h - padB), 3f, 3f, barPaint)
        }
        fun compactDate(date: String): String = date.takeLast(5).replace('-', '/')
        datePaint.textAlign = Paint.Align.LEFT
        canvas.drawText(compactDate(nights.first().date), padL + 2f, h - 4f, datePaint)
        datePaint.textAlign = Paint.Align.RIGHT
        canvas.drawText(compactDate(nights.last().date), w - padR, h - 4f, datePaint)
    }
}
