package com.sleepsentry.dsp

/**
 * 一次声音疑似静默事件。
 *
 * 判定链：静默段（无呼吸声，≥10s 且 ≤60s）→ 被一次显著高于周围安静的呼吸峰结束。
 * 这是麦克风声音特征筛查，不等同于临床呼吸暂停（临床需测量气流等生理信号）。
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
    val ok: Boolean,
    /** 原始 PCM 中接近满量程的样本比例；高比例表示录音削波，呼吸形态可能失真。 */
    val clippedFraction: Double = 0.0,
    /**
     * 响度对比度：最响 10% 与中位数之间的 dB 差。
     *
     * ⚠️ 这**不能**回答"录音里有没有可用的鼾声信号"，只作为诊断信息展示，不参与质量门限。
     *   实测依据：若用信噪比阈值划分两组来算可分性，是循环论证；
     *   改成按响度排序后，在真实低信噪比录音上量到 3.7~10dB，
     *   但那份录音的音频与临床鼾声标注相关系数只有 -0.012
     *   —— 那些"响的时刻"是关门声与脚步声，不是鼾声。
     *   单夜无标注数据无法自证"响的是什么"。
     */
    val loudnessContrastDb: Double = 0.0
) {
    fun reason(): String = when {
        durationSec < DspConfig.MIN_RECORD_S -> "有效记录不足 ${DspConfig.MIN_RECORD_S / 60} 分钟"
        clippedFraction > DspConfig.MAX_CLIPPED_FRACTION -> "录音波形削顶较多，声音细节可能失真；请降低录音音量或更换手机摆放位置"
        activeSnrMedianDb < DspConfig.MIN_ACTIVE_SNR_DB -> "环境噪声过大，鼾声被淹没"
        activeFraction < DspConfig.MIN_ACTIVE_FRAC -> "几乎全程无呼吸声，疑似无人或麦克风被遮挡"
        peakCount < DspConfig.MIN_PEAKS -> "检测到的呼吸声过少，数据不足"
        else -> "信号正常"
    }
}
