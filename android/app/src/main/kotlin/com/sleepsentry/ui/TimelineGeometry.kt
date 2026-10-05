package com.sleepsentry.ui

import kotlin.math.max
import kotlin.math.min

/**
 * 时间轴事件标记的几何计算。
 *
 * 单独抽出来是因为这里出过一次线上崩溃：
 * 原写法 `x2.coerceIn(x1 + 3f, w)`，当事件恰好落在整夜最末尾时，
 * `x1 + 3f > w`，下界大于上界，`coerceIn` 直接抛
 * `IllegalArgumentException: Cannot coerce value to an empty range`。
 * 表现为"第一次打开没事，某晚录出末尾事件后，之后每次启动都闪退"。
 *
 * 这里改成不依赖 `coerceIn` 的显式夹取，并保证：
 *   0 ≤ left ≤ right ≤ width  且  right − left ≥ minWidth（画布够宽时）
 */
object TimelineGeometry {

    /** 标记条的最小可见宽度（px） */
    const val MIN_MARK_PX = 3f

    /**
     * @param startSec 事件起点（秒）
     * @param endSec   事件终点（秒）
     * @param totalSec 整夜总时长（秒）
     * @param width    画布宽度（px）
     * @return [left, right]，像素坐标
     */
    fun markBounds(
        startSec: Double,
        endSec: Double,
        totalSec: Double,
        width: Float
    ): FloatArray {
        if (width <= 0f) return floatArrayOf(0f, 0f)
        val total = if (totalSec > 0.0) totalSec else 1.0

        val span = max(0f, width - MIN_MARK_PX)
        var left = (startSec / total * width).toFloat()
        var right = (endSec / total * width).toFloat()

        // NaN / 无穷也要挡住，脏数据不该让界面崩掉
        if (left.isNaN()) left = 0f
        if (right.isNaN()) right = 0f

        left = min(max(left, 0f), span)
        right = min(max(right, 0f), width)

        // 终点不能落在起点左边（时长为 0 或数据异常时会出现）
        if (right < left) right = left
        // 给一点最小可见宽度，但不超过画布右边界
        if (right - left < MIN_MARK_PX) {
            right = min(left + MIN_MARK_PX, width)
        }
        return floatArrayOf(left, right)
    }

    /** 触点是否落在某个标记的容差范围内（与绘制用的是同一套几何） */
    fun isHit(
        startSec: Double,
        endSec: Double,
        totalSec: Double,
        width: Float,
        touchX: Float,
        slopPx: Float = 20f
    ): Boolean {
        val b = markBounds(startSec, endSec, totalSec, width)
        return touchX >= b[0] - slopPx && touchX <= b[1] + slopPx
    }
}