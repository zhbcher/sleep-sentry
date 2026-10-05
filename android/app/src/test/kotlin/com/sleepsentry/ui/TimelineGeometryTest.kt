package com.sleepsentry.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回归测试：v1.0 曾因 `coerceIn` 下界大于上界导致每次启动闪退。
 *
 * 崩溃现场（用户真机堆栈）：
 *   IllegalArgumentException: Cannot coerce value to an empty range:
 *     maximum 1072.0 is less than minimum 1073.0825
 *   at TimelineView.onDraw
 *
 * 触发条件：事件出现在整夜最末尾（startSec/recordedSec 极接近 1），
 * 此时 `x1 + 3f` 大于画布宽度。原实现直接崩，且因为每次启动都会重绘，
 * 于是表现为"录出一晚之后，之后每次打开都闪退"。
 *
 * 这个类把"任何输入都不能崩"钉成契约。
 */
class TimelineGeometryTest {

    private val W = 1072f      // 与崩溃现场的画布宽度一致
    private val HOURS = 7.5 * 3600  // 7.5 小时

    /** 崩溃现场：事件落在最末尾 */
    @Test
    fun eventAtVeryEndOfNightDoesNotCrash() {
        val b = TimelineGeometry.markBounds(26998.0, 27000.0, 27000.0, W)
        assertTrue("left 不能越界", b[0] >= 0f)
        assertTrue("right 不能越界", b[1] <= W)
        assertTrue("left ≤ right", b[0] <= b[1])
    }

    /** 崩溃现场的原始数值，逐位复现 */
    @Test
    fun reproducesExactCrashValues() {
        val total = 27000.0
        // 起点落在 width - 1.9px 处 → x1 + 3 > width
        val startSec = total * (1.0 - 1.9175 / W)
        val b = TimelineGeometry.markBounds(startSec, startSec + 1.0, total, W)
        assertTrue(b[0] <= b[1])
        assertTrue(b[1] <= W)
    }

    @Test
    fun zeroDurationEventStillHasVisibleWidth() {
        val b = TimelineGeometry.markBounds(3600.0, 3600.0, HOURS, W)
        assertTrue("起点=终点时也应有最小可见宽度", b[1] - b[0] >= TimelineGeometry.MIN_MARK_PX - 0.01f)
    }

    @Test
    fun negativeAndOversizedTimesAreClamped() {
        val b1 = TimelineGeometry.markBounds(-500.0, -100.0, HOURS, W)
        assertTrue(b1[0] >= 0f); assertTrue(b1[1] <= W); assertTrue(b1[0] <= b1[1])

        val b2 = TimelineGeometry.markBounds(HOURS * 3, HOURS * 5, HOURS, W)
        assertTrue(b2[0] >= 0f); assertTrue(b2[1] <= W); assertTrue(b2[0] <= b2[1])
    }

    @Test
    fun reversedTimesDoNotProduceInvertedRect() {
        // 数据异常：终点早于起点
        val b = TimelineGeometry.markBounds(7200.0, 3600.0, HOURS, W)
        assertTrue("终点早于起点时也不能出现反向矩形", b[0] <= b[1])
    }

    @Test
    fun degenerateInputsAreSafe() {
        assertEquals(0f, TimelineGeometry.markBounds(0.0, 0.0, 0.0, 0f)[0], 1e-6f)
        assertEquals(0f, TimelineGeometry.markBounds(0.0, 0.0, HOURS, -5f)[1], 1e-6f)
        val nan = TimelineGeometry.markBounds(Double.NaN, Double.NaN, HOURS, W)
        assertTrue("NaN 不该传出 NaN", !nan[0].isNaN() && !nan[1].isNaN())
    }

    @Test
    fun normalEventMapsProportionally() {
        val b = TimelineGeometry.markBounds(HOURS / 4, HOURS / 2, HOURS, W)
        assertEquals(W * 0.25f, b[0], 1.0f)
        assertEquals(W * 0.5f, b[1], 1.0f)
    }

    @Test
    fun hitTestAgreesWithDrawnBounds() {
        val mid = HOURS / 2
        val b = TimelineGeometry.markBounds(mid, mid + 30, HOURS, W)
        val center = (b[0] + b[1]) / 2
        assertTrue(TimelineGeometry.isHit(mid, mid + 30, HOURS, W, center))
        assertTrue(!TimelineGeometry.isHit(mid, mid + 30, HOURS, W, 0f))
    }

    /** 随机扫描：任何 (start,end,total,width) 组合都不能产生非法区间 */
    @Test
    fun fuzzNeverProducesIllegalBounds() {
        val rnd = java.util.Random(2026)
        repeat(20000) {
            val total = rnd.nextDouble() * 40000.0 - 100.0      // 含负数
            val s = rnd.nextDouble() * 40000.0 - 5000.0
            val e = rnd.nextDouble() * 40000.0 - 5000.0
            val w = rnd.nextFloat() * 2000f - 200f               // 含负宽度
            val b = TimelineGeometry.markBounds(s, e, total, w)
            assertTrue("第 $it 次：left=${b[0]} right=${b[1]} w=$w s=$s e=$e total=$total",
                b[0].isFinite() && b[1].isFinite() && b[0] <= b[1] &&
                    b[0] >= 0f && b[1] <= maxOf(w, 0f))
        }
    }
}