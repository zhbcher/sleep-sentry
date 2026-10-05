package com.sleepsentry.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * 日历网格与色块状态的测试。
 *
 * 日历差一天、颜色画错格子，用户一眼就能看出来 —— 这类 UI 逻辑不能只靠肉眼验。
 * 真实日历不能造"闰年 2 月 30 日"这种输入，所以既扫真实日历，也扫构造出来的边界。
 */
class MonthCalendarTest {

    // ------------------------------------------------------------ 网格数学

    @Test
    fun firstDayOfWeekMatchesSystemCalendar() {
        // 与系统 Calendar 交叉验证 4 年 × 12 月
        for (y in 2024..2027) {
            for (m in 0..11) {
                val c = Calendar.getInstance()
                c.clear()
                c.set(y, m, 1)
                val dow = c.get(Calendar.DAY_OF_WEEK)
                val expect = if (dow == Calendar.SUNDAY) 6 else dow - 2
                assertEquals("${y}-${m + 1} 的 1 号是星期几", expect, MonthGrid.firstDayOfWeek(y, m))
            }
        }
    }

    @Test
    fun daysInMonthHandlesLeapYear() {
        assertEquals(29, MonthGrid.daysInMonth(2024, 1))   // 2024 是闰年
        assertEquals(28, MonthGrid.daysInMonth(2025, 1))
        assertEquals(28, MonthGrid.daysInMonth(2023, 1))
        assertEquals(31, MonthGrid.daysInMonth(2026, 0))
        assertEquals(30, MonthGrid.daysInMonth(2026, 3))
    }

    @Test
    fun everyDayRoundTripsThroughSlot() {
        for (y in 2024..2026) {
            for (m in 0..11) {
                val n = MonthGrid.daysInMonth(y, m)
                for (d in 1..n) {
                    val slot = MonthGrid.slotOf(d, y, m)
                    assertEquals("$y-${m + 1}-$d 的格号还原", d, MonthGrid.dayAtSlot(slot, y, m))
                    assertTrue("格号不能为负", slot >= 0)
                }
            }
        }
    }

    @Test
    fun emptyGridCellsMapToZero() {
        // 2026-10 的 1 号是星期四 → 前面有 3 个空格
        val first = MonthGrid.firstDayOfWeek(2026, 9)
        for (slot in 0 until first) {
            assertEquals("月初空白格应返回 0", 0, MonthGrid.dayAtSlot(slot, 2026, 9))
        }
        val n = MonthGrid.daysInMonth(2026, 9)
        assertEquals(0, MonthGrid.dayAtSlot(first + n, 2026, 9))
    }

    @Test
    fun rowAndColStayInsideGrid() {
        for (y in 2025..2026) {
            for (m in 0..11) {
                for (d in 1..MonthGrid.daysInMonth(y, m)) {
                    val slot = MonthGrid.slotOf(d, y, m)
                    val r = MonthGrid.rowOf(slot)
                    val c = MonthGrid.colOf(slot)
                    assertTrue("行越界 r=$r", r in 0..5)
                    assertTrue("列越界 c=$c", c in 0 until MonthGrid.COLS)
                }
            }
        }
    }

    @Test
    fun shiftMonthRollsOverYear() {
        assertEquals(2027 to 0, MonthGrid.shiftMonth(2026, 11, 1))    // 12月 → 次年1月
        assertEquals(2025 to 11, MonthGrid.shiftMonth(2026, 0, -1))   // 1月 → 上年12月
        assertEquals(2028 to 0, MonthGrid.shiftMonth(2026, 11, 13))
        // 2026-12 往前推 25 个月 = 2024-11（12+13=25）
        assertEquals(2024 to 10, MonthGrid.shiftMonth(2026, 11, -25))
        // 平移 12 个月 = 明年的同一个月
        for (y in 2024..2026) for (m in 0..11) {
            assertEquals((y + 1) to m, MonthGrid.shiftMonth(y, m, 12))
            assertEquals((y - 1) to m, MonthGrid.shiftMonth(y, m, -12))
        }
    }

    @Test
    fun dateParsingAndFormattingRoundTrip() {
        val s = MonthGrid.formatDate(2026, 9, 3)
        assertEquals("2026-10-03", s)
        val d = MonthGrid.parseDate(s)!!
        assertEquals(2026, d.first)
        assertEquals(9, d.second)
        assertEquals(3, d.third)
        assertTrue(MonthGrid.parseDate("乱码") == null)
        assertTrue(MonthGrid.parseDate("2026-13-01") == null)
        assertTrue(MonthGrid.parseDate("2026-10") == null)
        assertTrue(MonthGrid.parseDate("") == null)
    }

    @Test
    fun hitTestingMapsBackToTheRightDay() {
        val y = 2026
        val m = 9
        val first = MonthGrid.firstDayOfWeek(y, m)
        val cellW = 100f
        val cellH = 80f
        for (d in 1..MonthGrid.daysInMonth(y, m)) {
            val slot = MonthGrid.slotOf(d, y, m)
            val x = 10f + cellW * MonthGrid.colOf(slot) + cellW / 2
            val yy = 5f + cellH * MonthGrid.rowOf(slot) + cellH / 2
            assertEquals("$y-${m + 1}-$d 点击位置还原", d,
                MonthGrid.dayAt(x, yy, 10f, 5f, cellW, cellH, y, m))
        }
    }

    @Test
    fun hitTestingOnBlankCellReturnsZero() {
        val y = 2026; val m = 9
        val first = MonthGrid.firstDayOfWeek(y, m)
        if (first == 0) return   // 该月 1 号就是周一，没有空白格
        val x = 10f + 100f * 0 + 50f
        val yy = 5f + 80f * 0 + 40f
        assertEquals(0, MonthGrid.dayAt(x, yy, 10f, 5f, 100f, 80f, y, m))
    }

    @Test
    fun degenerateGeometryIsSafe() {
        assertEquals(0, MonthGrid.dayAt(0f, 0f, 0f, 0f, 0f, 0f, 2026, 9))
        assertEquals(0, MonthGrid.dayAt(-100f, -100f, 0f, 0f, 100f, 100f, 2026, 9))
    }

    // ------------------------------------------------------------ 色块状态

    @Test
    fun dayStateFollowsSeverityScale() {
        assertEquals(MonthCalendarView.DayState.NO_RECORD, DayStateMapper.of(99.0, false, true))
        assertEquals(MonthCalendarView.DayState.INSUFFICIENT, DayStateMapper.of(0.0, true, false))
        assertEquals(MonthCalendarView.DayState.NORMAL, DayStateMapper.of(2.0, true, true))
        assertEquals(MonthCalendarView.DayState.MILD, DayStateMapper.of(8.0, true, true))
        assertEquals(MonthCalendarView.DayState.MODERATE, DayStateMapper.of(20.0, true, true))
        assertEquals(MonthCalendarView.DayState.SEVERE, DayStateMapper.of(40.0, true, true))
    }

    @Test
    fun dayStateBoundariesMatchAhiScale() {
        assertEquals(MonthCalendarView.DayState.NORMAL, DayStateMapper.of(4.99, true, true))
        assertEquals(MonthCalendarView.DayState.MILD, DayStateMapper.of(5.0, true, true))
        assertEquals(MonthCalendarView.DayState.MILD, DayStateMapper.of(14.99, true, true))
        assertEquals(MonthCalendarView.DayState.MODERATE, DayStateMapper.of(15.0, true, true))
        assertEquals(MonthCalendarView.DayState.MODERATE, DayStateMapper.of(29.99, true, true))
        assertEquals(MonthCalendarView.DayState.SEVERE, DayStateMapper.of(30.0, true, true))
    }

    @Test
    fun insufficientDataTakesPriorityOverSeverity() {
        // 信号不足的那晚不该按严重程度上色 —— 那会让用户以为"这天很糟"
        assertEquals(MonthCalendarView.DayState.INSUFFICIENT, DayStateMapper.of(50.0, true, false))
    }

    @Test
    fun everyStateHasALabel() {
        MonthCalendarView.DayState.values().forEach {
            assertTrue("状态 ${it} 缺少文案", DayStateMapper.label(it).isNotBlank())
        }
    }
}