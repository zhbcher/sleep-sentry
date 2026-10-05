package com.sleepsentry.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 日历可读性测试。
 *
 * 背景：v1.2.0 用**白字画在浅灰格子上**（#FFFFFF on #E8ECF1，对比度约 1.1:1），
 * 用户反馈"无记录时日期和背景反差太小，看不清日期"。
 *
 * 这类问题不能再靠眼睛判断 —— 调完看着"还行"不等于读得清。
 * 改成**算出来**：每种色块的最佳文字色对比度都必须 ≥ WCAG AA 的 4.5:1。
 */
class CalendarContrastTest {

    @Test
    fun everyStateMeetsWcagAA() {
        MonthCalendarView.DayState.values().forEach { st ->
            val bg = CalendarStyle.colorFor(st)
            val ratio = CalendarStyle.bestContrast(st)
            assertTrue(
                "${DayStateMapper.label(st)} 的日期文字对比度只有 %.2f:1，低于 4.5:1（背景 #%06X）".format(ratio, bg),
                ratio >= CalendarStyle.MIN_CONTRAST
            )
        }
    }

    /** 回归：v1.2.0 的具体配色必须被判定为"看不清" */
    @Test
    fun legacyWhiteOnLightGrayIsRejected() {
        val legacyNoRecord = 0xFFE8ECF1.toInt()
        val white = 0xFFFFFFFF.toInt()
        assertTrue(
            "白字画在 #E8ECF1 上必须被判为不合格，否则说明对比度算错了",
            CalendarStyle.contrastRatio(legacyNoRecord, white) < CalendarStyle.MIN_CONTRAST
        )
    }

    @Test
    fun contrastIsSymmetric() {
        val a = CalendarStyle.NORMAL
        val b = CalendarStyle.SEVERE
        assertEquals(
            CalendarStyle.contrastRatio(a, b),
            CalendarStyle.contrastRatio(b, a),
            1e-9
        )
    }

    @Test
    fun identicalColorsGiveRatioOfOne() {
        assertEquals(1.0, CalendarStyle.contrastRatio(0xFF123456.toInt(), 0xFF123456.toInt()), 1e-9)
    }

    @Test
    fun blackOnWhiteIsMaximum() {
        val r = CalendarStyle.contrastRatio(0xFF000000.toInt(), 0xFFFFFFFF.toInt())
        assertTrue("黑白对比度应接近 21", r > 20.0)
    }

    @Test
    fun textColorFollowsBackgroundLuminance() {
        // 深色背景 → 白字；浅色背景 → 黑字
        assertEquals(CalendarStyle.TEXT_LIGHT, CalendarStyle.textColorFor(CalendarStyle.SEVERE))
        assertEquals(CalendarStyle.TEXT_DARK, CalendarStyle.textColorFor(CalendarStyle.NO_RECORD))
        assertEquals(CalendarStyle.TEXT_DARK, CalendarStyle.textColorFor(CalendarStyle.MILD))
    }

    @Test
    fun adjacentSeverityStatesAreDistinguishable() {
        // 相邻分档之间也要有可见差别，否则"颜色深浅表示严重程度"这句话就不成立
        val order = listOf(
            MonthCalendarView.DayState.NO_RECORD,
            MonthCalendarView.DayState.NORMAL,
            MonthCalendarView.DayState.MILD,
            MonthCalendarView.DayState.MODERATE,
            MonthCalendarView.DayState.SEVERE
        )
        for (i in 0 until order.size - 1) {
            val a = CalendarStyle.colorFor(order[i])
            val b = CalendarStyle.colorFor(order[i + 1])
            assertTrue(
                "${order[i]} 与 ${order[i + 1]} 颜色完全相同（#%06X）".format(a),
                a != b
            )
            assertTrue(
                "${DayStateMapper.label(order[i])} 与 ${DayStateMapper.label(order[i + 1])} 亮度差太小，视觉上分不出",
                Math.abs(CalendarStyle.relativeLuminance(a) - CalendarStyle.relativeLuminance(b)) > 0.02
            )
        }
    }

    @Test
    fun statesAreOrderedByLuminanceForSevereSide() {
        // 越严重颜色越深（亮度越低），这是"颜色深浅表示严重程度"的前提
        val lNormal = CalendarStyle.relativeLuminance(CalendarStyle.NORMAL)
        val lMild = CalendarStyle.relativeLuminance(CalendarStyle.MILD)
        val lMod = CalendarStyle.relativeLuminance(CalendarStyle.MODERATE)
        val lSev = CalendarStyle.relativeLuminance(CalendarStyle.SEVERE)
        assertTrue("重度应比中度深", lSev < lMod)
        assertTrue("中度应比轻度深", lMod < lMild)
    }
}
