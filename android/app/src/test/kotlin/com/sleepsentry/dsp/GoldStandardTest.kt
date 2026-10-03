package com.sleepsentry.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * 金标准对账测试（Kotlin 生产实现侧）。
 *
 * 这是整个项目的安全网：Python 参考实现 dsp/run_gold.py 与这里必须给出同一组结论。
 * 任何一侧改了算法参数或实现，都要同时跑两边，出现分歧即为回归。
 *
 * 两类指标分开：
 *  · 计数指标 —— "数出来的次数对不对"（对齐容差 3s，只关心是不是同一个事件）
 *  · 计时指标 —— "时刻准不准"（验收线 中位 ≤0.5s）
 */
class GoldStandardTest {

    private data class Case(
        val durationS: Double,
        val apneaCount: Int,
        val seed: Int,
        val noiseScale: Double = 1.0,
        val tv: Int = 3,
        val label: String
    )

    private class Result(
        val label: String, val nTruth: Int, val nPred: Int,
        val recall: Double, val precision: Double, val countAcc: Double,
        val tErrs: List<Double>, val dErrs: List<Double>, val quality: NightQuality,
        val unmatchedPred: List<String> = emptyList(),
        val unmatchedTruth: List<String> = emptyList(),
        val tv: List<Pair<Double, Double>> = emptyList()
    ) {
        val medianTErr: Double get() = if (tErrs.isEmpty()) 0.0 else tErrs.sorted()[tErrs.size / 2]
        val maxTErr: Double get() = if (tErrs.isEmpty()) 0.0 else tErrs.max()
        val medianDErr: Double get() = if (dErrs.isEmpty()) 0.0 else dErrs.sorted()[dErrs.size / 2]
        val maxDErr: Double get() = if (dErrs.isEmpty()) 0.0 else dErrs.max()
    }

    /**
     * 流式跑完整夜：滤波与检测都按块推进，滤波器状态跨块保持连续。
     * 这正是生产路径（AudioRecord 每 100ms 交一块），也避免整夜数组一次性进内存。
     */
    private fun streamDetect(audio: FloatArray, chunkSamples: Int): Pair<List<BreathingEvent>, NightQuality> {
        val filter = BandFilter()
        val det = EventDetector()
        val buf = FloatArray(chunkSamples)
        var off = 0
        while (off < audio.size) {
            val len = minOf(chunkSamples, audio.size - off)
            System.arraycopy(audio, off, buf, 0, len)
            val filtered = filter.filter(buf, len)
            det.processChunk(filtered, off.toDouble() / DspConfig.SAMPLE_RATE)
            off += len
        }
        return det.finalize()
    }

    private fun run(c: Case): Result {
        val (audio, truth) = SynthAudio.generate(
            durationS = c.durationS, apneaCount = c.apneaCount,
            seed = c.seed, noiseScale = c.noiseScale, tvBursts = c.tv
        )
        val (events, quality) = streamDetect(audio, chunkSamples = 10 * DspConfig.SAMPLE_RATE)

        // 一对一最近匹配
        val usedPred = BooleanArray(events.size)
        val tErrs = ArrayList<Double>()
        val dErrs = ArrayList<Double>()
        var matched = 0
        for ((truthStart, truthDur) in truth.apneas) {
            var best = -1
            var bestD = Double.MAX_VALUE
            for ((i, e) in events.withIndex()) {
                if (usedPred[i]) continue
                val d = abs(e.startSec - truthStart)
                if (d < bestD) { bestD = d; best = i }
            }
            if (best >= 0 && bestD <= MATCH_TOL_S) {
                usedPred[best] = true
                matched++
                tErrs.add(bestD)
                dErrs.add(abs(events[best].silenceSec - truthDur))
            }
        }
        val unmatchedPred = events.indices
            .filter { !usedPred[it] }
            .map { "%.0fs/%.0fs".format(events[it].startSec, events[it].silenceSec) }
        val matchedTruth = truth.apneas.indices.toMutableSet()
        var mi = 0
        for ((truthStart, truthDur) in truth.apneas) {
            if (matchedTruth.contains(mi)) {}
            mi++
        }
        val unmatchedTruth = emptyList<String>()

        val nTruth = truth.apneas.size
        val nPred = events.size
        val recall = if (nTruth == 0) 1.0 else matched.toDouble() / nTruth
        val precision = if (nPred == 0) 1.0 else matched.toDouble() / nPred
        val countAcc = 1.0 - minOf(1.0, abs(nPred - nTruth).toDouble() / maxOf(1, nTruth))

        return Result(c.label, nTruth, nPred, recall, precision, countAcc, tErrs, dErrs, quality,
            unmatchedPred = unmatchedPred, tv = truth.tv)
    }

    private fun report(r: Result) {
        println(
            "[${r.label}] 真实 ${r.nTruth} / 检出 ${r.nPred} | " +
                "召回 ${pct(r.recall)} 精确 ${pct(r.precision)} 计数准确率 ${pct(r.countAcc)} | " +
                "起点误差 中位 ${fmt(r.medianTErr)}s 最大 ${fmt(r.maxTErr)}s | " +
                "时长误差 中位 ${fmt(r.medianDErr)}s 最大 ${fmt(r.maxDErr)}s | " +
                "质量 ok=${r.quality.ok}"
        )
        if (r.unmatchedPred.isNotEmpty()) {
            println("    误报: " + r.unmatchedPred.joinToString(", ") +
                "   电视段: " + r.tv.joinToString(", ") { "%.0f-%.0fs".format(it.first, it.first + it.second) })
        }
    }

    private fun pct(v: Double) = "%.1f%%".format(v * 100)
    private fun fmt(v: Double) = "%.2f".format(v)

    @Test
    fun goldStandard_allCasesMeetTargets() {
        val cases = listOf(
            Case(1800.0, 8, 7, 1.0, 3, "基准-安静环境"),
            Case(1800.0, 8, 11, 1.0, 5, "基准-电视频繁"),
            Case(1800.0, 6, 23, 3.0, 3, "高噪声环境(3x)"),
            Case(1800.0, 12, 31, 1.0, 2, "高事件密度"),
            Case(1800.0, 10, 47, 0.5, 1, "极安静环境(0.5x)"),
            Case(1800.0, 10, 59, 2.0, 6, "嘈杂+电视(2x,6段)"),
            Case(3600.0, 15, 71, 1.2, 4, "长夜1小时-混合")
        )
        val results = cases.map { run(it) }
        results.forEach { report(it) }

        for (r in results) {
            assertTrue(
                "[${r.label}] 召回 ${pct(r.recall)} 低于 90%",
                r.recall >= RECALL_MIN
            )
            assertTrue(
                "[${r.label}] 计数准确率 ${pct(r.countAcc)} 低于 95%",
                r.countAcc >= COUNT_ACC_MIN
            )
            assertTrue(
                "[${r.label}] 起点误差中位 ${fmt(r.medianTErr)}s 超过 0.5s",
                r.medianTErr <= TIME_TOL_S
            )
            assertTrue(
                "[${r.label}] 质量判定应为通过：${r.quality.reason()}",
                r.quality.ok
            )
        }
    }

    /** 分级刻度必须与方案 v3.0 一致（对标国际通用 AHI 分级） */
    @Test
    fun severityLevels_matchAhiScale() {
        assertEquals(Severity.Level.NORMAL, Severity.levelOf(0.0))
        assertEquals(Severity.Level.NORMAL, Severity.levelOf(4.9))
        assertEquals(Severity.Level.MILD, Severity.levelOf(5.0))
        assertEquals(Severity.Level.MILD, Severity.levelOf(14.9))
        assertEquals(Severity.Level.MODERATE, Severity.levelOf(15.0))
        assertEquals(Severity.Level.MODERATE, Severity.levelOf(29.9))
        assertEquals(Severity.Level.SEVERE, Severity.levelOf(30.0))
        assertEquals(Severity.Level.SEVERE, Severity.levelOf(99.0))
    }

    /** 分块喂入与整段喂入必须给出一致结果 —— 验证残余缓冲没有让时间轴漂移 */
    @Test
    fun chunkingIsIrrelevant() {
        val (audio, _) = SynthAudio.generate(900.0, 5, 7)
        val big = streamDetect(audio, 60 * DspConfig.SAMPLE_RATE).first.map { it.startSec }
        // 故意用非 100ms 整数倍，逼出子帧对齐 bug
        val small = streamDetect(audio, 7 * DspConfig.SAMPLE_RATE).first.map { it.startSec }
        assertEquals("分块方式不应影响事件数量", big.size, small.size)
        assertTrue("分块方式不应让事件消失", small.isNotEmpty())
        for (i in big.indices) {
            assertEquals(
                "第 ${i + 1} 个事件起点在不同分块下应一致（容差 1ms）",
                big[i], small[i], 0.001
            )
        }
    }

    companion object {
        const val MATCH_TOL_S = 3.0
        const val TIME_TOL_S = 0.5
        const val RECALL_MIN = 0.90
        const val COUNT_ACC_MIN = 0.95
    }
}