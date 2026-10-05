package com.sleepsentry.ui

import java.util.Calendar
import java.util.Locale

/**
 * 月历网格数学（纯函数，可 JVM 单元测试）。
 *
 * 日历差一天是用户一眼就能看出来的错误 —— 颜色画到了错误的格子上。
 * 这些计算必须能脱离 Android View 单独验证，所以抽出来。
 *
 * 约定：周一 = 0，周日 = 6（与中文日历习惯一致）。
 */
object MonthGrid {

    const val COLS = 7

    /** 当月 1 号是星期几（0=周一 … 6=周日） */
    fun firstDayOfWeek(year: Int, month0: Int): Int {
        val c = Calendar.getInstance()
        c.clear()
        c.set(year, month0, 1)
        val dow = c.get(Calendar.DAY_OF_WEEK)   // 日历常量：周日=1 … 周六=7
        return if (dow == Calendar.SUNDAY) 6 else dow - 2
    }

    fun daysInMonth(year: Int, month0: Int): Int {
        val c = Calendar.getInstance()
        c.clear()
        c.set(year, month0, 1)
        return c.getActualMaximum(Calendar.DAY_OF_MONTH)
    }

    /** 日期在网格中的格号（0 起，行优先） */
    fun slotOf(day: Int, year: Int, month0: Int): Int = firstDayOfWeek(year, month0) + day - 1

    /** 格号还原成日期；越界返回 0 */
    fun dayAtSlot(slot: Int, year: Int, month0: Int): Int {
        val d = slot - firstDayOfWeek(year, month0) + 1
        return if (d in 1..daysInMonth(year, month0)) d else 0
    }

    fun rowOf(slot: Int): Int = slot / COLS

    fun colOf(slot: Int): Int = slot % COLS

    /** 点击坐标 → 日期（0 表示点在空白格） */
    fun dayAt(x: Float, y: Float, left: Float, top: Float, cellW: Float, cellH: Float,
              year: Int, month0: Int): Int {
        if (cellW <= 0f || cellH <= 0f) return 0
        val c = ((x - left) / cellW).toInt()
        val r = ((y - top) / cellH).toInt()
        if (c !in 0 until COLS || r < 0) return 0
        return dayAtSlot(r * COLS + c, year, month0)
    }

    /** 月份加减，自动跨年 */
    fun shiftMonth(year: Int, month0: Int, delta: Int): Pair<Int, Int> {
        val m = month0 + delta
        val y = year + Math.floorDiv(m, 12)
        val mm = Math.floorMod(m, 12)
        return y to mm
    }

    fun formatDate(year: Int, month0: Int, day: Int): String =
        String.format(Locale.US, "%04d-%02d-%02d", year, month0 + 1, day)

    /** "yyyy-MM-dd" → Triple(year, month0, day)；解析不了返回 null */
    fun parseDate(s: String): Triple<Int, Int, Int>? {
        val p = s.split("-")
        if (p.size != 3) return null
        val y = p[0].toIntOrNull() ?: return null
        val m = p[1].toIntOrNull() ?: return null
        val d = p[2].toIntOrNull() ?: return null
        if (m !in 1..12 || d !in 1..31) return null
        return Triple(y, m - 1, d)
    }
}