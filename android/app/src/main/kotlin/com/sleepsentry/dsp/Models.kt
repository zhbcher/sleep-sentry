package com.sleepsentry.dsp

/**
 * 一次疑似呼吸暂停事件。
 *
 * 判定链：静默段（无呼吸声，≥10s 且 ≤60s）→ 被一次显著高于周围安静的呼吸峰结束。
 * 这就是临床对 apnea 的定义（≥10s 气流中断），只是我们用"声音消失"代替"气流中断"来观测。
 */
data class BreathingEvent(
    /** 静默开始时刻（相对该夜 0 点，秒） */
    val startSec: Double,
    /** 静默持续时长（秒） */
    val silenceSec: Double,
    /** 静默结束时刻 */
    val endSec: Double,
    /** 结束它的恢复性喘息时刻 */
    val recoverySec: Double,
    /** 恢复性喘息突出度（dB）—— 越大说明憋完那口气吸得越猛 */
    val recoveryProminenceDb: Double
)

/** 一次呼吸/鼾声峰 */
data class BreathPeak(
    val timeSec: Double,
    val db: Double,
    val prominenceDb: Double
)

/** 记录质量。质量不达标时报告必须说"信号不足，未判定"，而不是硬给结论。 */
data class NightQuality(
    val durationSec: Double,
    val activeSnrMedianDb: Double,
    val activeFraction: Double,
    val peakCount: Int,
    val ok: Boolean
) {
    fun reason(): String = when {
        durationSec < DspConfig.MIN_RECORD_S -> "有效记录不足 ${DspConfig.MIN_RECORD_S / 60} 分钟"
        activeSnrMedianDb < DspConfig.MIN_ACTIVE_SNR_DB -> "环境噪声过大，鼾声被淹没"
        activeFraction < DspConfig.MIN_ACTIVE_FRAC -> "几乎全程无呼吸声，疑似无人或麦克风被遮挡"
        peakCount < DspConfig.MIN_PEAKS -> "检测到的呼吸声过少，数据不足"
        else -> "信号正常"
    }
}

/**
 * 严重程度分级。
 *
 * 主指标是"每小时疑似事件次数"，对标国际通用的 AHI 分级刻度（用户和医生都认这个刻度），
 * 而不是按单次时长分级 —— 时长粒度太粗且非通用。
 *
 * 报告措辞必须带"疑似""声音推算"，绝不出现血氧/氧合指数（麦克风测不到）。
 */
object Severity {

    enum class Level(val perHourMax: Double, val label: String, val advice: String) {
        NORMAL(5.0, "在常见范围内", "保持观察即可"),
        MILD(15.0, "轻度", "建议留意，若长期如此可咨询呼吸科"),
        MODERATE(30.0, "中度", "建议到呼吸科或睡眠中心评估"),
        SEVERE(Double.MAX_VALUE, "重度", "建议尽快到睡眠中心做多导睡眠监测(PSG)")
    }

    fun levelOf(eventsPerHour: Double): Level = when {
        eventsPerHour < 5.0 -> Level.NORMAL
        eventsPerHour < 15.0 -> Level.MILD
        eventsPerHour < 30.0 -> Level.MODERATE
        else -> Level.SEVERE
    }
}