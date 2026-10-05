package com.sleepsentry.store

/**
 * 存储配额的纯逻辑（可 JVM 单元测试，不依赖 Android）。
 *
 * 为什么单独抽出来：配额淘汰是"删哪些文件"的决策，
 * 顺序错一次就会把用户想留的片段删掉、或者该删的留着占空间。
 * 这种错误很难靠肉眼发现，必须用测试钉死。
 */
object StorageQuota {

    /** 一个音频片段的信息 */
    data class Item(
        val name: String,
        val bytes: Long,
        /** 录音发生的绝对时刻（毫秒）。用于确定"谁更旧"。 */
        val recordedAtMillis: Long,
        /** 是否为整夜音频。整夜音频很大，但用户是主动开的，优先保留片段。 */
        val isFullAudio: Boolean = false
    )

    /** 一次淘汰的结果 */
    data class Plan(
        val keep: List<String>,
        val delete: List<String>,
        val totalBytesAfter: Long
    ) {
        val deletedBytes: Long get() = 0   // 由调用方统计
    }

    /**
     * 算出在配额内该保留哪些文件。
     *
     * 规则：
     *  1. 按"录音时刻"从旧到新排序（不是按文件名 —— 文件名排序在序号进位时会倒序）
     *  2. 超配额时，先删最旧的事件片段
     *  3. 整夜音频最后才动：它是用户主动开的开关，且单文件巨大，
     *     先动它会让"开了整夜留存却一晚都留不下"，体验最差
     *  4. 若只删片段仍超配额，再按旧到新删整夜音频
     *  5. quotaBytes ≤ 0 表示不限，全部保留
     */
    fun plan(items: List<Item>, quotaBytes: Long): Plan {
        if (items.isEmpty()) return Plan(emptyList(), emptyList(), 0L)
        if (quotaBytes <= 0) {
            return Plan(items.map { it.name }, emptyList(), items.sumOf { it.bytes })
        }

        val chronological = items.sortedBy { it.recordedAtMillis }
        var total = chronological.sumOf { it.bytes }
        val deleted = ArrayList<String>()

        // 第一轮：删最旧的事件片段
        val segments = chronological.filter { !it.isFullAudio }
        for (s in segments) {
            if (total <= quotaBytes) break
            if (deleted.contains(s.name)) continue
            deleted.add(s.name)
            total -= s.bytes
        }
        // 第二轮：仍超配额才动整夜音频
        if (total > quotaBytes) {
            for (s in chronological.filter { it.isFullAudio }) {
                if (total <= quotaBytes) break
                if (deleted.contains(s.name)) continue
                deleted.add(s.name)
                total -= s.bytes
            }
        }

        val deleteSet = deleted.toSet()
        return Plan(
            keep = chronological.filter { it.name !in deleteSet }.map { it.name },
            delete = deleted,
            totalBytesAfter = total
        )
    }
}

/**
 * 每小时分桶的纯逻辑（可 JVM 单元测试）。
 *
 * 睡眠往往跨午夜（23:00 → 07:00），所以必须用**绝对时刻**取小时，
 * 不能用"距入睡第几小时"，否则柱子会错位。
 */
object HourlyBuckets {

    const val HOURS = 24

    /**
     * @param eventOffsetsSec 每个事件相对该夜 0 点的秒数
     * @param nightStartMillis 该夜录音的起始绝对时刻
     * @return 长度 24 的数组，下标为 0..23 点，值为该小时内的疑似事件数
     */
    fun count(eventOffsetsSec: List<Double>, nightStartMillis: Long): IntArray {
        val out = IntArray(HOURS)
        val cal = java.util.Calendar.getInstance()
        for (off in eventOffsetsSec) {
            val absMs = nightStartMillis + (off * 1000.0).toLong()
            cal.timeInMillis = absMs
            // 必须用**本地**钟点。直接用 absMs / 3600000 % 24 拿到的是 UTC 钟点，
            // 对北京时间用户，凌晨 3 点的事件会被归到前一天 19 点，整张图全错。
            // 用 Calendar 还能正确处理夏令时切换。
            val hour = cal.get(java.util.Calendar.HOUR_OF_DAY)
            if (hour in 0 until HOURS) out[hour]++
        }
        return out
    }

    /** 取某个绝对时刻的本地钟点（0..23），供 UI 与分桶保持一致 */
    fun localHour(absMillis: Long): Int {
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = absMillis
        return cal.get(java.util.Calendar.HOUR_OF_DAY)
    }

    /**
     * 录音实际覆盖的首尾钟点（本地时区）。
     *
     * 注意：**end < start 表示跨了午夜**，这是正常的（23 点睡到次日 6 点），
     * 不要再把它强行拉回 23 —— 那是改成 UTC 口径时留下的错误修正。
     * 需要画连续底纹的图表请自行处理跨午夜（拆成两段画）。
     */
    fun coveredRange(
        recordedSec: Double,
        nightStartMillis: Long
    ): IntArray {
        if (recordedSec <= 0) return intArrayOf(0, HOURS - 1)
        val startHour = localHour(nightStartMillis)
        val endMs = nightStartMillis + (recordedSec * 1000.0).toLong()
        return intArrayOf(startHour, localHour(endMs))
    }

    /** 该钟点是否落在当晚录音覆盖范围内（跨午夜安全） */
    fun isCovered(range: IntArray, hour: Int): Boolean {
        val s = range[0].coerceIn(0, HOURS - 1)
        val e = range[1].coerceIn(0, HOURS - 1)
        return if (s <= e) hour in s..e else (hour >= s || hour <= e)
    }
}