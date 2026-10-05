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
    fun textColorAlwaysPicksTheHigherContrastOption() {
        // 不写死"哪种底色该配哪种字色"，只断言**规则**：
        // 无论浅色还是深色主题，都必须选对比度更高的那个。
        // （v1.2.x 的深色主题重设计后，原先"浅底配黑字"的硬编码断言就过时了。）
        MonthCalendarView.DayState.values().forEach { st ->
            val bg = CalendarStyle.colorFor(st)
            val picked = CalendarStyle.textColorFor(bg)
            val crDark = CalendarStyle.contrastRatio(bg, CalendarStyle.TEXT_DARK)
            val crLight = CalendarStyle.contrastRatio(bg, CalendarStyle.TEXT_LIGHT)
            val expected = if (crDark >= crLight) CalendarStyle.TEXT_DARK else CalendarStyle.TEXT_LIGHT
            assertEquals("${DayStateMapper.label(st)} 的文字色应取对比度更高的一侧", expected, picked)
        }
    }

    /**
     * 相邻档必须"一眼能分" —— 用感知色差 ΔE 判定，不用亮度单维度。
     *
     * 为什么换度量：深色主题里相邻档靠**色相**拉开（冷 → 绿 → 琥珀 → 砖红 → 红），
     * 亮度差可以很小但仍然一眼可辨。反过来浅色主题靠亮度拉开。
     * 两种主题下 ΔE 都是可靠的判据，亮度不是。
     */
    @Test
    fun adjacentSeverityStatesAreDistinguishable() {
        val order = listOf(
            MonthCalendarView.DayState.NO_RECORD,
            MonthCalendarView.DayState.INSUFFICIENT,
            MonthCalendarView.DayState.NORMAL,
            MonthCalendarView.DayState.MILD,
            MonthCalendarView.DayState.MODERATE,
            MonthCalendarView.DayState.SEVERE
        )
        for (i in 0 until order.size - 1) {
            val a = CalendarStyle.colorFor(order[i])
            val b = CalendarStyle.colorFor(order[i + 1])
            val de = CalendarStyle.deltaE(a, b)
            assertTrue(
                "${DayStateMapper.label(order[i])} 与 ${DayStateMapper.label(order[i + 1])} " +
                    "感知色差只有 %.1f（#%06X vs #%06X），低于 %.0f 会被看成同一档".format(
                        de, a, b, CalendarStyle.MIN_DELTA_E),
                de >= CalendarStyle.MIN_DELTA_E
            )
        }
    }

    /**
     * 严重程度的排序轴是**色相（暖度）**，不是亮度。
     *
     * 设计稿里明确写着"颜色越暖，代表当晚记录到的疑似事件越多"，
     * 实测配色也确实如此：平稳(绿 165°) → 轻度(琥珀 29°) → 中度(砖红 11°) → 较高(红 7°)。
     *
     * 试过按亮度排序，不成立：轻度 L*=38.3 比中度 L*=36.1 反而更亮，
     * 按亮度排会出现"轻度比中度更醒目"的错觉。
     * 这也是为什么判"分不分得清"要用感知色差 ΔE 而不是亮度差。
     */
    @Test
    fun severityIsMonotonicInWarmth() {
        val ramp = listOf(
            MonthCalendarView.DayState.NORMAL,
            MonthCalendarView.DayState.MILD,
            MonthCalendarView.DayState.MODERATE,
            MonthCalendarView.DayState.SEVERE
        )
        val warm = ramp.map { CalendarStyle.warmness(CalendarStyle.colorFor(it)) }
        for (i in 1 until warm.size) {
            assertTrue(
                "${DayStateMapper.label(ramp[i])} 应比 ${DayStateMapper.label(ramp[i - 1])} 更暖" +
                    "（暖度 ${"%.1f".format(warm[i])} vs ${"%.1f".format(warm[i - 1])}，" +
                    "色相 ${"%.0f".format(CalendarStyle.hueDegrees(CalendarStyle.colorFor(ramp[i])))}°）",
                warm[i] > warm[i - 1]
            )
        }
    }

    /** 无记录 / 信号不足 是"没有结论"，不该被读成"程度轻" */
    @Test
    fun nonRecordedStatesAreCoolerThanRecordedOnes() {
        val cool = listOf(
            CalendarStyle.warmness(CalendarStyle.colorFor(MonthCalendarView.DayState.NO_RECORD)),
            CalendarStyle.warmness(CalendarStyle.colorFor(MonthCalendarView.DayState.INSUFFICIENT))
        )
        val warmestRecorded = maxOf(
            CalendarStyle.warmness(CalendarStyle.colorFor(MonthCalendarView.DayState.NORMAL)),
            CalendarStyle.warmness(CalendarStyle.colorFor(MonthCalendarView.DayState.SEVERE))
        )
        for ((i, w) in cool.withIndex()) {
            assertTrue(
                "无结论的第 $i 档比" + "\"有记录但最轻/最重\"还暖，会被误读为程度更重",
                w < warmestRecorded
            )
        }
    }
}
