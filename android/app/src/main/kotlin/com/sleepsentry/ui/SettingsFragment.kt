package com.sleepsentry.ui

import android.app.TimePickerDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ProgressBar
import android.widget.Switch
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.sleepsentry.R
import com.sleepsentry.capture.MorningNotifier
import com.sleepsentry.store.NightStore
import com.sleepsentry.util.Prefs

/** 设置页：时段、存储配额、录音开关、数据清理、免责声明 */
class SettingsFragment : Fragment() {

    private lateinit var prefs: Prefs
    private lateinit var store: NightStore
    private lateinit var windowText: TextView
    private lateinit var morningText: TextView
    private lateinit var quotaText: TextView
    private lateinit var quotaUsage: TextView
    private lateinit var quotaProgress: ProgressBar
    private lateinit var fullAudioSwitch: Switch
    private lateinit var dataStat: TextView
    private lateinit var clearAudioBtn: Button
    private lateinit var clearAllBtn: Button

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View =
        layoutInflater.inflate(R.layout.fragment_settings, c, false)

    override fun onViewCreated(view: View, s: Bundle?) {
        prefs = Prefs(requireContext())
        store = NightStore(requireContext())
        windowText = view.findViewById(R.id.windowText)
        morningText = view.findViewById(R.id.morningText)
        quotaText = view.findViewById(R.id.quotaText)
        quotaUsage = view.findViewById(R.id.quotaUsage)
        quotaProgress = view.findViewById(R.id.quotaProgress)
        fullAudioSwitch = view.findViewById(R.id.fullAudioSwitch)
        dataStat = view.findViewById(R.id.dataStat)
        clearAudioBtn = view.findViewById(R.id.clearAudioBtn)
        clearAllBtn = view.findViewById(R.id.clearAllBtn)

        view.findViewById<View>(R.id.windowRow).setOnClickListener {
            pickTime(prefs.windowStartMin) { m -> prefs.windowStartMin = m; render() }
        }
        view.findViewById<View>(R.id.morningRow).setOnClickListener {
            pickTime(prefs.morningNotifyMin) { m ->
                prefs.morningNotifyMin = m
                MorningNotifier.schedule(requireContext(), m)
                render()
            }
        }
        view.findViewById<View>(R.id.quotaRow).setOnClickListener { pickQuota() }
        quotaText.setOnClickListener { pickQuota() }

        fullAudioSwitch.setOnCheckedChangeListener { _, v ->
            prefs.keepFullAudio = v
            if (v) (activity as? MainActivity)?.toast("整夜音频每晚约 110MB，受占用上限约束")
            render()
        }
        clearAudioBtn.setOnClickListener { confirmClearAudio() }
        clearAllBtn.setOnClickListener { confirmClearAll() }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        windowText.text = prefs.windowText()
        morningText.text = prefs.notifyText()
        quotaText.text = Prefs.quotaText(prefs.storageQuotaMb)
        if (fullAudioSwitch.isChecked != prefs.keepFullAudio) {
            fullAudioSwitch.isChecked = prefs.keepFullAudio
        }
        val rec = store.latest()
        quotaUsage.text = if (rec == null) {
            "还没有录音记录"
        } else {
            "最近一晚占用 ${Prefs.usageText(store.audioUsageBytes(rec.date))}" +
                if (prefs.keepFullAudio) " · 整夜留存已开" else ""
        }
        val latestUsage = rec?.let { store.audioUsageBytes(it.date) } ?: 0L
        val quotaBytes = prefs.storageQuotaMb.toLong() * 1024L * 1024L
        quotaProgress.progress = if (quotaBytes > 0L) {
            ((latestUsage * 100L) / quotaBytes).toInt().coerceIn(0, 100)
        } else 0
        val nights = store.list()
        val audioFiles = store.totalAudioBytes()
        dataStat.text = "已有 ${nights.size} 晚记录 · 音频共 ${Prefs.usageText(audioFiles)}"
    }

    private fun pickTime(currentMin: Int, onPicked: (Int) -> Unit) {
        TimePickerDialog(
            requireContext(), { _, h, m -> onPicked(h * 60 + m) },
            currentMin / 60, currentMin % 60, true
        ).show()
    }

    private fun pickQuota() {
        val opts = Prefs.QUOTA_OPTIONS_MB.map { Prefs.quotaText(it) }.toTypedArray()
        val cur = Prefs.QUOTA_OPTIONS_MB.indexOfFirst { it == prefs.storageQuotaMb }.let { if (it >= 0) it else 2 }
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("每晚录音占用上限")
            .setSingleChoiceItems(opts, cur) { d, which ->
                prefs.storageQuotaMb = Prefs.QUOTA_OPTIONS_MB[which]
                render()
                (activity as? MainActivity)?.toast(
                    "已设为 ${Prefs.quotaText(prefs.storageQuotaMb)}，超出后自动删最旧的录音"
                )
                d.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 主路径：只清音频，历史报告/日历/趋势全部保留 */
    private fun confirmClearAudio() {
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("清除所有录音文件？")
            .setMessage(
                "会删除全部音频文件并释放磁盘空间，不可恢复。\n\n" +
                    "历史报告、日历色块和趋势统计都会保留 —— 音频只是证据，统计才是长期积累的价值。"
            )
            .setPositiveButton("清除录音") { _, _ ->
                val (files, bytes) = store.clearAllAudio()
                render()
                (activity as? MainActivity)?.toast(
                    if (files == 0) "没有可删除的录音"
                    else "已清除 $files 个音频文件，释放 ${Prefs.usageText(bytes)}"
                )
                refreshStatus()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 彻底清除：音频 + 历史记录，谨慎使用 */
    private fun confirmClearAll() {
        androidx.appcompat.app.AlertDialog.Builder(requireContext())
            .setTitle("清空全部数据？")
            .setMessage("会删除全部音频文件和历史报告，日历与趋势会变空。不可恢复。")
            .setPositiveButton("全部清空") { _, _ ->
                store.clearEverything()
                prefs.streakDays = 0
                prefs.lastRunMillis = 0
                ReportSelection.date = null
                render()
                refreshStatus()
                (activity as? MainActivity)?.toast("已清空全部数据")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun refreshStatus() {
        (activity?.supportFragmentManager?.findFragmentByTag(R.id.nav_status.toString()) as? StatusFragment)
            ?.render()
    }
}
