package com.sleepsentry.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.sleepsentry.R
import com.sleepsentry.capture.MorningNotifier
import com.sleepsentry.capture.SentryService
import com.sleepsentry.store.NightRecord
import com.sleepsentry.util.Prefs
import kotlin.math.roundToInt

/** 状态页：当前在不在监听、开关、自检提示、昨晚速览 */
class StatusFragment : Fragment() {

    private lateinit var prefs: Prefs
    private lateinit var stateText: TextView
    private lateinit var liveText: TextView
    private lateinit var streakText: TextView
    private lateinit var warnText: TextView
    private lateinit var toggleBtn: Button
    private lateinit var quickText: TextView
    private lateinit var quickSub: TextView

    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            renderLive()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View =
        layoutInflater.inflate(R.layout.fragment_status, c, false)

    override fun onViewCreated(view: View, s: Bundle?) {
        prefs = Prefs(requireContext())
        stateText = view.findViewById(R.id.stateText)
        liveText = view.findViewById(R.id.liveText)
        streakText = view.findViewById(R.id.streakText)
        warnText = view.findViewById(R.id.warnText)
        toggleBtn = view.findViewById(R.id.toggleBtn)
        quickText = view.findViewById(R.id.quickText)
        quickSub = view.findViewById(R.id.quickSub)

        toggleBtn.setOnClickListener { (activity as? MainActivity)?.toggleSentry() }
        view.findViewById<View>(R.id.goReportBtn).setOnClickListener {
            (activity as? MainActivity)?.showTab(R.id.nav_report)
        }
    }

    override fun onResume() {
        super.onResume()
        render()
        handler.post(ticker)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(ticker)
    }

    fun render() {
        val ctx = context ?: return
        toggleBtn.text = getString(if (prefs.enabled) R.string.action_disable else R.string.action_enable)
        warnText.visibility = if (prefs.lastFailureReason.isBlank()) View.GONE else View.VISIBLE
        if (prefs.lastFailureReason.isNotBlank()) {
            warnText.text =
                "上次运行异常：${prefs.lastFailureReason}\n请检查：电池优化是否关闭、麦克风权限是否被收回。"
        }
        streakText.text = if (prefs.streakDays > 0) "已连续记录 ${prefs.streakDays} 晚" else ""

        val rec: NightRecord? = (activity as? MainActivity)?.store?.latest()
        if (rec == null) {
            quickText.text = "还没有记录"
            quickText.setTextColor(ContextCompat.getColor(ctx, R.color.text))
            quickSub.text = "插上充电器、放手机在床头，第二天早上这里会有内容。"
        } else {
            quickText.text = when {
                !rec.quality.ok -> "信号不足，未作判定"
                rec.events.isEmpty() -> "未检出疑似呼吸暂停"
                else -> "疑似呼吸暂停 ${rec.events.size} 次"
            }
            quickText.setTextColor(
                ContextCompat.getColor(ctx, colorOf(rec))
            )
            quickSub.text = buildString {
                append("${rec.date} · 有效记录 ${MorningNotifier.fmtDur(rec.recordedSec)}\n")
                if (rec.events.isNotEmpty()) {
                    append("每小时 ${"%.1f".format(rec.eventsPerHour)} 次 · ${rec.level.label}\n")
                    rec.worstEvent?.let { append("最长静默 ${it.silenceSec.roundToInt()} 秒") }
                } else {
                    append("鼾声 ${rec.peakCount} 次")
                }
            }
        }
        renderLive()
    }

    private fun colorOf(rec: NightRecord): Int = when {
        !rec.quality.ok -> R.color.textDim
        rec.events.isEmpty() -> R.color.good
        rec.eventsPerHour < 5 -> R.color.good
        rec.eventsPerHour < 15 -> R.color.warn
        else -> R.color.bad
    }

    private fun renderLive() {
        if (!isAdded) return
        val ctx = context ?: return
        stateText.text = when {
            SentryService.isRunning -> getString(R.string.state_armed)
            prefs.enabled -> getString(R.string.state_waiting)
            else -> getString(R.string.state_idle)
        }
        stateText.setTextColor(
            ContextCompat.getColor(ctx, if (SentryService.isRunning) R.color.good else R.color.brand)
        )
        val isRunning = SentryService.isRunning
        liveText.visibility = if (isRunning) View.VISIBLE else View.GONE
        liveText.text = if (isRunning) {
            "已监听 ${MorningNotifier.fmtDur(SentryService.liveRecordedSec)} · " +
                    "疑似事件 ${SentryService.liveEventCount} 次 · " +
                    "呼吸声 ${SentryService.liveSnoreCount} 次"
        } else ""
    }
}
