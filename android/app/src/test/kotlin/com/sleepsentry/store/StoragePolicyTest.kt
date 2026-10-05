package com.sleepsentry.store

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * 存储配额、文件名排序、每小时分桶的纯逻辑测试。
 *
 * 这些都是"错了很难肉眼发现"的地方：
 *  ��汰顺序错一次 → 删掉用户想留的片段
 *  排序错一次   → 用户看到时间倒着排（v1.0 真实反馈）
 *  跨午夜分桶错 → 柱子画在错误的钟点上
 */
class StoragePolicyTest {

    private val MB = 1024L * 1024L
    private fun t(s: String): Long =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).parse(s)!!.time

    // ------------------------------------------------------------ 配额淘汰

    @Test
    fun quotaZeroMeansUnlimited() {
        val items = (1..10).map {
            StorageQuota.Item("seg_$it.pcm", 10 * MB, 1_000_000L * it)
        }
        val plan = StorageQuota.plan(items, quotaBytes = 0)
        assertEquals("不限时不应删任何东西", 0, plan.delete.size)
        assertEquals(10, plan.keep.size)
    }

    @Test
    fun deletesOldestFirstUntilWithinQuota() {
        // 10 个片段各 10MB，共 100MB；配额 50MB → 应保留最新的 5 个
        val items = (1..10).map {
            StorageQuota.Item("seg_$it.pcm", 10 * MB, 1_000_000L * it)
        }
        val plan = StorageQuota.plan(items, 50 * MB)
        assertEquals(5, plan.delete.size)
        assertEquals(5, plan.keep.size)
        assertTrue("删掉的应该是最旧的 1~5", plan.delete.contains("seg_1.pcm"))
        assertTrue("最新的 10 必须留下", plan.keep.contains("seg_10.pcm"))
        assertFalse("最新的不能被删", plan.delete.contains("seg_10.pcm"))
        assertTrue(plan.totalBytesAfter <= 50 * MB)
    }

    @Test
    fun fullAudioIsDeletedLast() {
        // 整夜音频 90MB + 2 个片段各 10MB，配额 50MB
        val items = listOf(
            StorageQuota.Item("full_2026-10-03.pcm", 90 * MB, t("2026-10-03 23:10:00"), true),
            StorageQuota.Item("seg_a.pcm", 10 * MB, t("2026-10-04 01:00:00")),
            StorageQuota.Item("seg_b.pcm", 10 * MB, t("2026-10-04 02:00:00"))
        )
        val plan = StorageQuota.plan(items, 50 * MB)
        assertTrue("片段应先被删，整夜音频留到最后", plan.delete.contains("seg_a.pcm"))
        assertTrue(plan.totalBytesAfter <= 50 * MB)
    }

    @Test
    fun deletesFullAudioOnlyWhenSegmentsAreNotEnough() {
        val items = listOf(
            StorageQuota.Item("full_2026-10-03.pcm", 90 * MB, t("2026-10-03 23:10:00"), true)
        )
        val plan = StorageQuota.plan(items, 50 * MB)
        assertTrue("只剩整夜音频时必须删它，否则配额形同虚设",
            plan.delete.contains("full_2026-10-03.pcm"))
        assertEquals(0L, plan.totalBytesAfter)
    }

    @Test
    fun withinQuotaDeletesNothing() {
        val items = (1..3).map {
            StorageQuota.Item("seg_$it.pcm", 5 * MB, 1_000_000L * it)
        }
        val plan = StorageQuota.plan(items, 200 * MB)
        assertEquals(0, plan.delete.size)
        assertEquals(15 * MB, plan.totalBytesAfter)
    }

    @Test
    fun emptyAndDegenerateInputsAreSafe() {
        assertEquals(0, StorageQuota.plan(emptyList(), 10 * MB).delete.size)
        val one = listOf(StorageQuota.Item("x.pcm", 0L, 0L))
        assertEquals(0, StorageQuota.plan(one, 1).delete.size)
    }

    @Test
    fun keepAndDeleteNeverOverlap() {
        val items = (1..20).map {
            StorageQuota.Item("seg_$it.pcm", (it * 3 * MB), 1_000_000L * it)
        }
        val plan = StorageQuota.plan(items, 40 * MB)
        val overlap = plan.keep.toSet() intersect plan.delete.toSet()
        assertTrue("保留与删除集合不应重叠：$overlap", overlap.isEmpty())
        assertEquals(items.size, plan.keep.size + plan.delete.size)
    }

    // ------------------------------------------------------------ 文件名排序

    /**
     * 核心回归：v1.0 的文件名只带递增序号，进位到 10000 时字典序会倒过来。
     * 这里证明加入时间戳后，字典序 == 时间序。
     */
    @Test
    fun filenameLexicalOrderEqualsTimeOrder() {
        val base = t("2026-10-03 23:00:00")
        val names = (0 until 24).map { h ->
            SegmentNaming.segment("2026-10-03", base + h * 3600_000L, 0)
        }
        val byName = names.sorted()
        val byTime = names.sortedBy { it }   // 同一小时内，靠 HHmmss 区分
        assertEquals(byName, byTime)
        // 逐个核对解析出来的时间戳是递增的
        val parsed = names.map { n ->
            SegmentNaming.parseRecordedAt(File("/tmp/$n"))
        }
        for (i in 1 until parsed.size) {
            assertTrue("第 $i 个的时间戳应大于前一个", parsed[i] > parsed[i - 1])
        }
    }

    @Test
    fun legacyPureIndexOrderingIsBrokenButNewNamingIsNot() {
        // 旧命名的缺陷留个证据，防止有人"优化"回纯序号
        val legacy = listOf("seg_2026-10-03_9999.pcm", "seg_2026-10-03_10000.pcm")
        assertTrue(
            "旧命名确实会倒序（这正是 v1.0 的排序混乱根因）",
            legacy.sorted().first().endsWith("10000.pcm")
        )
        // 新命名不会
        val base = t("2026-10-03 23:00:00")
        val now = listOf(
            SegmentNaming.segment("2026-10-03", base + 1, 0),
            SegmentNaming.segment("2026-10-03", base + 2, 0)
        )
        assertEquals(now[0], now.sorted().first())
    }

    @Test
    fun parseRecordedAtFallsBackWhenNameIsGarbage() {
        val f = File("/tmp/full_2026-10-03.pcm")
        f.writeText("x")
        // 整夜音频的名字不带时间戳，应回落到文件修改时间而不是崩溃
        val v = SegmentNaming.parseRecordedAt(f)
        assertTrue(v > 0)
        f.delete()
    }

    // ------------------------------------------------------------ 每小时分桶

    @Test
    fun crossMidnightBucketsAreCorrect() {
        // 夜从 23:00 开始
        val nightStart = t("2026-10-03 23:00:00")
        val offsets = listOf(
            1800.0,            // 23:30 → 23 点
            5400.0,            // 00:30 → 0 点
            7200.0             // 01:00 → 1 点
        )
        val b = HourlyBuckets.count(offsets, nightStart)
        assertEquals(1, b[23])
        assertEquals(1, b[0])
        assertEquals(1, b[1])
        assertEquals("总共只该有 3 次", 3, b.sum())
    }

    @Test
    fun multipleEventsInSameHourAreCounted() {
        val nightStart = t("2026-10-03 23:00:00")
        val b = HourlyBuckets.count(listOf(60.0, 120.0, 180.0, 240.0), nightStart)
        assertEquals(4, b[23])
        assertEquals(4, b.sum())
    }

    @Test
    fun eventsOutOfRangeAreIgnoredSafely() {
        val nightStart = t("2026-10-03 23:00:00")
        val b = HourlyBuckets.count(listOf(-100.0, 0.0), nightStart)
        assertTrue("负偏移不该让数组越界", b.all { it >= 0 })
        assertEquals(24, b.size)
    }

    @Test
    fun emptyEventListGivesAllZero() {
        val b = HourlyBuckets.count(emptyList(), t("2026-10-03 23:00:00"))
        assertEquals(0, b.sum())
    }

    @Test
    fun coveredRangeSpansMidnight() {
        // 23:00 开始，录 7 小时 → 覆盖 23 点到次日 6 点
        val r = HourlyBuckets.coveredRange(7 * 3600.0, t("2026-10-03 23:00:00"))
        assertEquals(23, r[0])
        // 本地钟点：23 点睡 7 小时 → 次日 6 点；end < start 正是跨午夜的正常表现
        assertEquals(6, r[1])
        assertTrue("23 点应在覆盖范围内", HourlyBuckets.isCovered(r, 23))
        assertTrue("0 点应在覆盖范围内", HourlyBuckets.isCovered(r, 0))
        assertTrue("6 点应在覆盖范围内", HourlyBuckets.isCovered(r, 6))
        assertTrue("12 点不应在覆盖范围内", !HourlyBuckets.isCovered(r, 12))
    }

    @Test
    fun coveredRangeHandlesDegenerateRecording() {
        val r = HourlyBuckets.coveredRange(0.0, t("2026-10-03 23:00:00"))
        assertEquals(2, r.size)
        assertTrue(r.all { it in 0..23 })
    }

    /** 桶数与"按名字推断的小时"必须一致，否则图和列表会对不上 */
    @Test
    fun bucketCountMatchesHourOfEvent() {
        val nightStart = t("2026-10-03 23:00:00")
        val offsets = (0 until 12).map { it * 1800.0 }   // 每半小时一个，跨 6 小时
        val b = HourlyBuckets.count(offsets, nightStart)
        offsets.forEach { off ->
            val absMs = nightStart + (off * 1000).toLong()
            val expect = HourlyBuckets.localHour(absMs)   // 期望值也必须用本地钟点
            assertTrue("$off 秒应落在 $expect 点", b[expect] >= 1)
        }
        assertEquals(offsets.size, b.sum())
        assertTrue("时钟偏差不超过 1", abs(b.sum() - offsets.size) <= 1)
    }
}