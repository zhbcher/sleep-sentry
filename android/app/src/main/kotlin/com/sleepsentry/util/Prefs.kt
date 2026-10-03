package com.sleepsentry.util

import android.content.Context
import android.content.SharedPreferences
import java.util.Calendar

/**
 * 配置。守夜模式的目标是"设一次，每晚零操作"，所以这里的项越少越好。
 */
class Prefs(private val ctx: Context) {

    private val sp: SharedPreferences =
        ctx.getSharedPreferences("sleepsentry", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = sp.getBoolean(KEY_ENABLED, false)
        set(v) = sp.edit().putBoolean(KEY_ENABLED, v).apply()

    /** 每天自动进入待命的起始时刻（从 00:00 起的分钟数） */
    var windowStartMin: Int
        get() = sp.getInt(KEY_WIN_START, DEFAULT_START_MIN)
        set(v) = sp.edit().putInt(KEY_WIN_START, v).apply()

    var windowEndMin: Int
        get() = sp.getInt(KEY_WIN_END, DEFAULT_END_MIN)
        set(v) = sp.edit().putInt(KEY_WIN_END, v).apply()

    /** 早晨汇总推送时刻 */
    var morningNotifyMin: Int
        get() = sp.getInt(KEY_MORNING, DEFAULT_MORNING_MIN)
        set(v) = sp.edit().putInt(KEY_MORNING, v).apply()

    /** 是否插上充电器就自动开始 */
    var autoStartOnCharge: Boolean
        get() = sp.getBoolean(KEY_AUTO_CHARGE, true)
        set(v) = sp.edit().putBoolean(KEY_AUTO_CHARGE, v).apply()

    /** 是否保留整夜音频（关闭时只留事件片段，约 3MB/晚） */
    var keepFullAudio: Boolean
        get() = sp.getBoolean(KEY_FULL_AUDIO, false)
        set(v) = sp.edit().putBoolean(KEY_FULL_AUDIO, v).apply()

    /** 最近一次运行的健康状态，供 UI 展示与"为什么没数据"的自检 */
    var lastFailureReason: String
        get() = sp.getString(KEY_LAST_FAIL, "").orEmpty()
        set(v) = sp.edit().putString(KEY_LAST_FAIL, v).apply()

    var lastRunMillis: Long
        get() = sp.getLong(KEY_LAST_RUN, 0L)
        set(v) = sp.edit().putLong(KEY_LAST_RUN, v).apply()

    /** 连续成功记录天数（留存钩子） */
    var streakDays: Int
        get() = sp.getInt(KEY_STREAK, 0)
        set(v) = sp.edit().putInt(KEY_STREAK, v).apply()

    fun isInWindow(nowMs: Long): Boolean {
        val cal = Calendar.getInstance()
        cal.timeInMillis = nowMs
        val nowMin = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val s = windowStartMin
        val e = windowEndMin
        return if (s <= e) nowMin in s until e else (nowMin >= s || nowMin < e)
    }

    fun windowText(): String = "${fmt(windowStartMin)} – ${fmt(windowEndMin)}"

    fun notifyText(): String = fmt(morningNotifyMin)

    companion object {
        private const val KEY_ENABLED = "enabled"
        private const val KEY_WIN_START = "win_start"
        private const val KEY_WIN_END = "win_end"
        private const val KEY_MORNING = "morning"
        private const val KEY_AUTO_CHARGE = "auto_charge"
        private const val KEY_FULL_AUDIO = "full_audio"
        private const val KEY_LAST_FAIL = "last_fail"
        private const val KEY_LAST_RUN = "last_run"
        private const val KEY_STREAK = "streak"

        const val DEFAULT_START_MIN = 22 * 60 + 30
        const val DEFAULT_END_MIN = 7 * 60 + 30
        const val DEFAULT_MORNING_MIN = 8 * 60

        fun fmt(min: Int): String = "%02d:%02d".format(min / 60, min % 60)
    }
}