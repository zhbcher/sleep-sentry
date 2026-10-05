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
    // 亮度分层刻意拉开：浅/深两类，方便自适应选择黑白文字
    const val NO_RECORD = 0xFFEDF0F4.toInt()
    const val INSUFFICIENT = 0xFFC9D2DC.toInt()
    const val NORMAL = 0xFF8FCBA8.toInt()
    const val MILD = 0xFFF3CE7A.toInt()
    const val MODERATE = 0xFFE09256.toInt()
    const val SEVERE = 0xFFB8392C.toInt()

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

    /** 自带断言的辅助：某状态下的最佳文字色 */
    fun bestTextColor(state: MonthCalendarView.DayState): Int = textColorFor(colorFor(state))

    fun bestContrast(state: MonthCalendarView.DayState): Double =
        max(contrastRatio(colorFor(state), TEXT_DARK), contrastRatio(colorFor(state), TEXT_LIGHT))
}