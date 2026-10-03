package com.sleepsentry.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * 合成睡眠音频生成器 —— 与 Python 版 dsp/synth.py 同一套信号模型。
 *
 * 用途：造"已知答案"的录音，用它把计数与计时精度在单元测试里钉死，
 * 不必等真实数据、不必先买检测仪就能发现移植走样或参数回归。
 *
 * 刻意包含几种现实里会遇到的干扰：
 *  · 鼾声之间有 3~9 秒自然间隙（静默会从"最后一个声音结束"就开始，不是从标注处）
 *  · 恢复性喘息更高频、更响、更短
 *  · 恒定底噪（风扇）+ 非平稳干扰（电视/人声，带说话起伏）
 *  · 电视段刻意避开暂停段 —— 否则等于"真值自己和自己矛盾"，测出的漏报是数据问题不是算法问题
 */
object SynthAudio {

    class Truth(
        val snores: List<Pair<Double, Double>>,
        val apneas: List<Pair<Double, Double>>,
        val tv: List<Pair<Double, Double>>,
        val durationSec: Double
    )

    private fun attackDecay(n: Int, attackS: Double, decayS: Double): FloatArray {
        val env = FloatArray(n)
        val a = max(1, (attackS * 16000).toInt())
        val tau = max(1.0, decayS * 16000)
        for (i in 0 until n) {
            var v = exp(-i / tau).toFloat()
            if (i < a) v *= (i.toFloat() / a)
            env[i] = v
        }
        return env
    }

    /** 尾部淡出到严格 0：否则指数尾巴会让"真值结束时刻"与"声学上真正没声了"不一致 */
    private fun fadeOut(sig: FloatArray, fadeS: Double = 0.12): FloatArray {
        val n = (fadeS * 16000).toInt()
        if (n <= 0 || sig.size < n) return sig
        for (k in 0 until n) sig[sig.size - n + k] *= (1.0 - k.toDouble() / n).toFloat()
        return sig
    }

    private fun snore(durS: Double, level: Double, rnd: Random): FloatArray {
        val n = (durS * 16000).toInt()
        val t = DoubleArray(n) { it / 16000.0 }
        val f0 = 95 + rnd.nextDouble() * 65
        val sig = DoubleArray(n)
        var k = 1
        while (k <= 8) {
            val f = f0 * k
            if (f > 8000) break
            val amp = 1.0 / k
            val ph = rnd.nextDouble() * 6.283185307
            for (i in 0 until n) sig[i] += amp * sin(2 * PI * f * t[i] + ph)
            k++
        }
        for (i in 0 until n) sig[i] += (rnd.nextDouble() - 0.5) * 0.7
        val env = attackDecay(n, 0.08, durS * 0.45)
        var peak = 0.0
        for (i in 0 until n) { sig[i] *= env[i]; peak = max(peak, kotlin.math.abs(sig[i])) }
        val out = FloatArray(n)
        for (i in 0 until n) out[i] = ((sig[i] / (peak + 1e-9)) * level).toFloat()
        return fadeOut(out)
    }

    private fun gasp(durS: Double, level: Double, rnd: Random): FloatArray {
        val n = (durS * 16000).toInt()
        val t = DoubleArray(n) { it / 16000.0 }
        val parts = doubleArrayOf(320.0, 560.0, 780.0, 1100.0)
        val amps = doubleArrayOf(1.0, 0.7, 0.4, 0.2)
        val sig = DoubleArray(n)
        for (p in parts.indices) {
            val f = parts[p]
            val ph = rnd.nextDouble() * 6.283185307
            for (i in 0 until n) {
                val vib = 1.0 + 0.05 * sin(2 * PI * 3 * t[i])
                sig[i] += amps[p] * sin(2 * PI * f * t[i] * vib + ph)
            }
        }
        for (i in 0 until n) sig[i] += (rnd.nextDouble() - 0.5) * 0.5
        val env = attackDecay(n, 0.05, durS * 0.4)
        var peak = 0.0
        for (i in 0 until n) { sig[i] *= env[i]; peak = max(peak, kotlin.math.abs(sig[i])) }
        val out = FloatArray(n)
        for (i in 0 until n) out[i] = ((sig[i] / (peak + 1e-9)) * level).toFloat()
        return fadeOut(out, 0.08)
    }

    private fun noiseInto(dst: FloatArray, from: Int, len: Int, rnd: Random, fan: Double, hiss: Double) {
        // 风扇：100/150/200Hz 恒定低频嗡鸣
        val ph1 = rnd.nextDouble() * 6.283185307
        val ph2 = rnd.nextDouble() * 6.283185307
        val ph3 = rnd.nextDouble() * 6.283185307
        var hPrev = 0.0
        for (i in 0 until len) {
            val t = i / 16000.0
            val f = (1.0 * sin(2 * PI * 100 * t + ph1) +
                    0.667 * sin(2 * PI * 150 * t + ph2) +
                    0.5 * sin(2 * PI * 200 * t + ph3)) / 3.0
            // 宽带嘶声做 6 点滑动平均（模拟麦克风频响）
            hPrev = hPrev * 5.0 / 6.0 + (rnd.nextDouble() - 0.5) / 6.0
            dst[from + i] += (f * fan + hPrev * hiss).toFloat()
        }
    }

    fun generate(
        durationS: Double,
        apneaCount: Int,
        seed: Int,
        snoreGapRange: Pair<Double, Double> = 3.0 to 9.0,
        snoreDurRange: Pair<Double, Double> = 1.5 to 5.0,
        snoreLevelRange: Pair<Double, Double> = 0.05 to 0.35,
        apneaSilenceRange: Pair<Double, Double> = 12.0 to 50.0,
        noiseScale: Double = 1.0,
        tvBursts: Int = 3
    ): Pair<FloatArray, Truth> {
        val rnd = Random(seed)
        val n = (durationS * 16000).toInt()
        val audio = FloatArray(n)
        noiseInto(audio, 0, n, rnd, 0.006 * noiseScale, 0.0015 * noiseScale)

        val snores = ArrayList<Pair<Double, Double>>()
        val apneas = ArrayList<Pair<Double, Double>>()

        val settleEnd = min(durationS * 0.03, 300.0)
        var t = settleEnd
        var lastSoundEnd = settleEnd
        val limit = min(apneaCount, max(0, ((durationS - settleEnd) / 60.0).toInt()))

        while (t < durationS - 60 && apneas.size < limit) {
            val burst = 3 + rnd.nextInt(9)
            repeat(burst) {
                if (t >= durationS - 60) return@repeat
                val dur = snoreDurRange.first + rnd.nextDouble() * (snoreDurRange.second - snoreDurRange.first)
                val lvl = snoreLevelRange.first + rnd.nextDouble() * (snoreLevelRange.second - snoreLevelRange.first)
                val seg = snore(dur, lvl, rnd)
                val i = (t * 16000).toInt()
                val j = min(n, i + seg.size)
                for (k in i until j) audio[k] += seg[k - i]
                snores.add(t to dur)
                t += dur
                lastSoundEnd = t
                t += snoreGapRange.first + rnd.nextDouble() * (snoreGapRange.second - snoreGapRange.first)
            }
            // 呼吸暂停：静默从"最后一个声音结束"开始
            val sil = apneaSilenceRange.first + rnd.nextDouble() * (apneaSilenceRange.second - apneaSilenceRange.first)
            val silStart = lastSoundEnd
            val silEnd = t + sil
            apneas.add(silStart to (silEnd - silStart))
            t = silEnd
            // 恢复性喘息
            val gdur = 0.4 + rnd.nextDouble() * 1.1
            val glvl = 0.25 + rnd.nextDouble() * 0.35
            val seg = gasp(gdur, glvl, rnd)
            val i = (t * 16000).toInt()
            val j = min(n, i + seg.size)
            for (k in i until j) audio[k] += seg[k - i]
            t += gdur
            lastSoundEnd = t
            t += 1.0 + rnd.nextDouble() * 3.0
        }

        // 电视/人声：非平稳（说话有起伏），且必须避开暂停段
        apneas.sortBy { it.first }
        val tv = ArrayList<Pair<Double, Double>>()
        var tries = 0
        while (tv.size < tvBursts && tries < 500) {
            tries++
            val start = settleEnd + rnd.nextDouble() * max(1.0, durationS - 200.0 - settleEnd)
            val dur = 30.0 + rnd.nextDouble() * 90.0
            val overlaps = apneas.any { !(start + dur + 6.0 < it.first || start - 6.0 > it.first + it.second) }
            if (overlaps) continue
            // 真实房间里的电视是连续背景音，不会围着人的呼吸开关。
            // 必须落在有鼾声的活动段里，否则会造出现实中不存在的"两段电视之间夹一段纯安静"。
            val snoreHits = snores.count { it.first < start + dur && it.first + it.second > start }
            if (snoreHits < 3) continue
            // 电视段之间也不能互相重叠：两段叠在一起会造出内部的安静空档，
            // 现实中不存在这种场景，测出的误报是数据的问题不是算法的问题。
            val overlapsTv = tv.any { !(start + dur + 10.0 < it.first || start - 10.0 > it.first + it.second) }
            if (overlapsTv) continue
            val i = (start * 16000).toInt()
            val len = min(n - i, (dur * 16000).toInt())
            if (len <= 0) continue
            var prev = 0.0
            val seg = FloatArray(len)
            for (j in 0 until len) {
                prev = prev * 19.0 / 20.0 + (rnd.nextDouble() - 0.5) / 20.0
                seg[j] = prev.toFloat()
            }
            // 说话起伏
            val chunk = (0.8 * 16000).toInt()
            var p = 0
            while (p < len) {
                val amp = 0.55 + 0.85 * rnd.nextDouble()
                val e = min(len, p + chunk)
                for (j in p until e) seg[j] *= amp.toFloat()
                p = e
            }
            for (j in 0 until len) audio[i + j] += seg[j]
            tv.add(start to dur)
        }

        // 安全归一化
        var peak = 0f
        for (x in audio) peak = max(peak, kotlin.math.abs(x))
        if (peak > 0.98f) for (k in audio.indices) audio[k] = audio[k] / peak * 0.98f

        return audio to Truth(snores, apneas, tv, durationS)
    }
}