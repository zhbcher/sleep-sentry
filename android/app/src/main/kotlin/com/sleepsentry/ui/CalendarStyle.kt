package com.sleepsentry.ui

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * 日历配色与文字对比度。
 *
 * 存在的理由：v1.2.0 用白字画在浅灰格子上，对比度约 1.1:1 —— 日期几乎看不见，
 * 是用户真实反馈的问题。教训是"颜色好不好看"不该靠眼睛判断，
 * 而应该**算出来**，并且用测试钉死。
 *
 * 判定口径采用 WCAG 2.1 的相对亮度与对比度公式，正文文字要求 ≥ 4.5:1。
 */
object CalendarStyle {

    // ---- 色板 ----
    // 夜间底色上用不同色相和明度表达记录状态，同时保留足够的日期文字对比度。
    const val NO_RECORD = 0xFF1B2938.toInt()
    const val INSUFFICIENT = 0xFF334155.toInt()
    const val NORMAL = 0xFF24483F.toInt()
    const val MILD = 0xFF70553B.toInt()
    const val MODERATE = 0xFF79483D.toInt()
    // 与中度(#79483D) 的感知色差 ΔE 从 7.2 提到 15.6（≥10 才算"一眼能分"），
    // 仍在同一暖红家族内，白字对比度 6.13:1 仍远高于 AA 的 4.5:1。
    const val SEVERE = 0xFF9A4A3E.toInt()

    const val TEXT_DARK = 0xFF1A1C1E.toInt()
    const val TEXT_LIGHT = 0xFFFFFFFF.toInt()

    /** 对比度低于这个值就算"看不清"，测试会拦住 */
    const val MIN_CONTRAST = 4.5

    fun colorFor(state: MonthCalendarView.DayState): Int = when (state) {
        MonthCalendarView.DayState.NO_RECORD -> NO_RECORD
        MonthCalendarView.DayState.INSUFFICIENT -> INSUFFICIENT
        MonthCalendarView.DayState.NORMAL -> NORMAL
        MonthCalendarView.DayState.MILD -> MILD
        MonthCalendarView.DayState.MODERATE -> MODERATE
        MonthCalendarView.DayState.SEVERE -> SEVERE
    }

    // ---- WCAG 对比度 ----

    fun channelLuminance(c: Int): Double {
        val s = c / 255.0
        return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
    }

    /** 相对亮度（0=黑，1=白） */
    fun relativeLuminance(rgb: Int): Double {
        val r = channelLuminance((rgb shr 16) and 0xFF)
        val g = channelLuminance((rgb shr 8) and 0xFF)
        val b = channelLuminance(rgb and 0xFF)
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    fun contrastRatio(a: Int, b: Int): Double {
        val la = relativeLuminance(a)
        val lb = relativeLuminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    /** 背景上该用深色还是浅色文字 —— 取对比度更高的那个 */
    fun textColorFor(background: Int): Int =
        if (contrastRatio(background, TEXT_DARK) >= contrastRatio(background, TEXT_LIGHT)) {
            TEXT_DARK
        } else {
            TEXT_LIGHT
        }

    // ---- 感知色差（CIELab ΔE）----
    // 判定"两档颜色看不看得分清"必须用感知色差，不能用亮度或色相单看一个维度：
    // 深色主题里"越严重越亮"，浅色主题里"越严重越深"，两种都成立。
    // ΔE 经验阈值：<2.3 几乎看不出，2.3~10 勉强能分，>10 明显。

    private fun srgbToLinear(c: Int): Double {
        val v = c / 255.0
        return if (v <= 0.04045) v / 12.92 else Math.pow((v + 0.055) / 1.055, 2.4)
    }

    /** sRGB → CIELab */
    fun toLab(rgb: Int): DoubleArray {
        val r = srgbToLinear((rgb shr 16) and 0xFF)
        val g = srgbToLinear((rgb shr 8) and 0xFF)
        val b = srgbToLinear(rgb and 0xFF)
        val x = 0.4124 * r + 0.3576 * g + 0.1805 * b
        val y = 0.2126 * r + 0.7152 * g + 0.0722 * b
        val z = 0.0193 * r + 0.1192 * g + 0.9505 * b
        fun f(t: Double) = if (t > 0.008856) Math.cbrt(t) else 7.787 * t + 16.0 / 116.0
        val fx = f(x / 0.95047)
        val fy = f(y)
        val fz = f(z / 1.08883)
        return doubleArrayOf(116.0 * fy - 16.0, 500.0 * (fx - fy), 200.0 * (fy - fz))
    }

    /** CIE76 色差 */
    fun deltaE(a: Int, b: Int): Double {
        val la = toLab(a)
        val lb = toLab(b)
        var sum = 0.0
        for (i in 0..2) { val d = la[i] - lb[i]; sum += d * d }
        return Math.sqrt(sum)
    }

    /** 色相角（0..360，0=红） */
    fun hueDegrees(rgb: Int): Double {
        val r = srgbToLinear((rgb shr 16) and 0xFF)
        val g = srgbToLinear((rgb shr 8) and 0xFF)
        val b = srgbToLinear(rgb and 0xFF)
        val mx = maxOf(r, g, b)
        val mn = minOf(r, g, b)
        if (mx - mn < 1e-9) return 0.0
        val d = mx - mn
        val h = when (mx) {
            r -> ((g - b) / d) % 6.0
            g -> (b - r) / d + 2.0
            else -> (r - g) / d + 4.0
        } * 60.0
        return if (h < 0) h + 360.0 else h
    }

    /** 色相与"暖色"（红/橙，约 0~60°）的角距离，越小越暖 */
    fun warmness(rgb: Int): Double {
        val h = hueDegrees(rgb)
        val d = minOf(h, 360.0 - h)          // 到红色的最短角距
        return 180.0 - d                     // 越大越暖
    }

    /** 相邻严重度档必须"一眼能分"的色差下限 */
    const val MIN_DELTA_E = 10.0

    /** 自带断言的辅助：某状态下的最佳文字色 */
    fun bestTextColor(state: MonthCalendarView.DayState): Int = textColorFor(colorFor(state))

    fun bestContrast(state: MonthCalendarView.DayState): Double =
        max(contrastRatio(colorFor(state), TEXT_DARK), contrastRatio(colorFor(state), TEXT_LIGHT))
}
