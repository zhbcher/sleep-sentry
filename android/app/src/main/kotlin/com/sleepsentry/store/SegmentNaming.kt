package com.sleepsentry.store

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 音频文件名规范。
 *
 * 单独抽成纯函数是因为这里出过一次真实缺陷：
 * 旧文件名只带递增序号（seg_2026-10-03_0001.pcm），带来两个问题：
 *  1. 事件要过约 5 秒才被确认，**序号 ≠ 发生时间**，顺序本身就对不上
 *  2. 序号从 9999 进位到 10000 时，字典序 `"10000" < "9999"`，
 *     按文件名排序会**整体倒过来** —— 这正是用户报告的"排序混乱"
 *
 * 现在文件名里带**事件发生的绝对时刻**（yyyyMMdd-HHmmss，定长），
 * 于是字典序 == 时间序，排序问题从根上消失。
 */
object SegmentNaming {

    private const val TS = "yyyyMMdd-HHmmss"
    // 日期段是 yyyy-MM-dd（带横线），时间戳段是定长 yyyyMMdd-HHmmss。
    // 早先误写成 "seg_(\\d{8})-(\\d{6})_"，永远匹配不上真实文件名，
    // 导致录音时刻一直静默退化成"文件修改时间"。
    private val RE = Regex("seg_\\d{4}-\\d{2}-\\d{2}_(\\d{8})-(\\d{6})_")

    fun segment(date: String, eventAbsMillis: Long, index: Int): String {
        val ts = SimpleDateFormat(TS, Locale.US).format(Date(eventAbsMillis))
        return "seg_${date}_${ts}_${"%04d".format(index)}.pcm"
    }

    fun fullAudio(date: String): String = "full_$date.pcm"

    /** 从片段文件名还原录音时刻；解析不了就退回文件修改时间 */
    fun parseRecordedAt(file: File): Long {
        val m = RE.find(file.name)
        if (m != null) {
            try {
                val d = SimpleDateFormat(TS, Locale.US)
                    .parse("${m.groupValues[1]}-${m.groupValues[2]}")
                if (d != null) return d.time
            } catch (_: Exception) {
            }
        }
        return file.lastModified()
    }
}
