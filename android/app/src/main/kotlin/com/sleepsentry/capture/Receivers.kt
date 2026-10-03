package com.sleepsentry.capture

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.Build
import androidx.core.app.NotificationCompat
import com.sleepsentry.R
import com.sleepsentry.store.NightStore
import com.sleepsentry.ui.MainActivity
import com.sleepsentry.util.Prefs
import java.util.Calendar

/**
 * 插上充电器即自动进入待命。
 *
 * 不用"App 自己后台唤醒"（安卓最不靠谱的做法），改用系统一定会送达的
 * ACTION_POWER_CONNECTED 广播 —— 这是"设一次、每晚零操作"的技术底座。
 */
class PowerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = Prefs(context)
        if (!prefs.enabled || !prefs.autoStartOnCharge) return

        when (intent.action) {
            Intent.ACTION_POWER_CONNECTED -> {
                if (isCharging(context) && prefs.isInWindow(System.currentTimeMillis())) {
                    SentryService.start(context)
                }
            }
            Intent.ACTION_POWER_DISCONNECTED -> {
                // 拔电后继续录会很快耗尽电量，直接停掉，用户可手动继续
                if (SentryService.isRunning) SentryService.stop(context)
            }
        }
    }

    private fun isCharging(ctx: Context): Boolean {
        val st = ctx.registerReceiver(
            null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        ) ?: return false
        val status = st.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        return status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
    }
}

/**
 * 开机后恢复能力。
 * 注意：Android 15 起禁止开机后启动麦克风类前台服务，因此这里只重建闹钟，
 * 真正的监听仍要等下一次插电广播 —— 这是系统硬限制，不是可以绕过的 bug。
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val prefs = Prefs(context)
        if (!prefs.enabled) return
        MorningNotifier.schedule(context, prefs.morningNotifyMin)
    }
}

/** 早晨闹钟：到点拉起汇总通知 */
class MorningAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        MorningNotifier.postIfRecordExists(context)
        Prefs(context).let { MorningNotifier.schedule(context, it.morningNotifyMin) }
    }
}

/**
 * 通知：次日汇总 + 断档自检。
 *
 * "断档必推送原因"是留存的关键 —— 用户不知道为什么没数据，最沉默的做法是让他自己猜，
 * 猜不到就卸载了。
 */
object MorningNotifier {

    const val CHANNEL_REPORT = "report"
    const val CHANNEL_ALERT = "alert"
    private const val REQ_ALARM = 7001
    private const val NOTIF_REPORT = 2001

    fun schedule(ctx: Context, minuteOfDay: Int) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, minuteOfDay / 60)
            set(Calendar.MINUTE, minuteOfDay % 60)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
        }
        val pi = alarmIntent(ctx)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi)
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi)
            }
        } catch (_: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi)
        }
    }

    private fun alarmIntent(ctx: Context): PendingIntent {
        val i = Intent(ctx, MorningAlarmReceiver::class.java)
        return PendingIntent.getBroadcast(
            ctx, REQ_ALARM, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun postIfRecordExists(ctx: Context) {
        val store = NightStore(ctx)
        val rec = store.latest() ?: return

        // 只推"昨夜"的，今早不该重复推前天的
        val startOfToday = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        if (rec.startMillis < startOfToday) return

        val mgr = ctx.getSystemService(NotificationManager::class.java) ?: return
        val title: String
        val body: String
        val channel: String
        when {
            !rec.quality.ok -> {
                channel = CHANNEL_ALERT
                title = "昨晚信号不足，未作判定"
                body = rec.quality.reason() + "。请检查：电池优化是否关闭 / 麦克风权限 / 手机是否被系统清理"
            }
            rec.events.isEmpty() -> {
                channel = CHANNEL_REPORT
                title = "昨晚未检出疑似呼吸暂停"
                body = "有效记录 ${fmtDur(rec.recordedSec)} · 鼾声 ${rec.peakCount} 次 · 环境正常"
            }
            else -> {
                channel = CHANNEL_REPORT
                title = "昨晚 ${rec.events.size} 次疑似呼吸暂停"
                val worst = rec.worstEvent
                body = buildString {
                    append("${fmtDur(rec.recordedSec)} · 每小时 ")
                    append("%.1f".format(rec.eventsPerHour)).append(" 次（${rec.level.label}）")
                    if (worst != null) append("\n最长一次静默 ${worst.silenceSec.toInt()} 秒")
                }
            }
        }
        notify(ctx, mgr, title, body, channel)
    }

    fun postRunFailure(ctx: Context, reason: String) {
        val mgr = ctx.getSystemService(NotificationManager::class.java) ?: return
        notify(ctx, mgr, "守夜中断", reason, CHANNEL_ALERT)
    }

    private fun notify(
        ctx: Context, mgr: NotificationManager,
        title: String, body: String, channel: String
    ) {
        val pi = PendingIntent.getActivity(
            ctx, 1,
            Intent(ctx, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n: Notification = NotificationCompat.Builder(ctx, channel)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        mgr.notify(NOTIF_REPORT, n)
    }

    fun fmtDur(sec: Double): String {
        val h = (sec / 3600).toInt()
        val m = ((sec % 3600) / 60).toInt()
        return if (h > 0) "${h} 小时 ${m} 分" else "${m} 分钟"
    }
}