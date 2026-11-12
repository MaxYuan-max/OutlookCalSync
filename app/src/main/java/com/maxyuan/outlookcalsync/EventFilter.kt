package com.maxyuan.outlookcalsync

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/** 每天的一个时间窗口, 事件开始时间落在 [start, end) 内就会被跳过。start > end 表示跨午夜。 */
data class DailyWindow(val start: LocalTime, val end: LocalTime) {
    fun contains(t: LocalTime) =
        if (start <= end) t >= start && t < end else t >= start || t < end

    override fun toString() = "%02d:%02d-%02d:%02d".format(start.hour, start.minute, end.hour, end.minute)

    companion object {
        /** 解析 "12:00-13:00" 这种写法, 格式不对返回 null。 */
        fun parse(s: String): DailyWindow? {
            val m = Regex("""^\s*(\d{1,2}):(\d{2})\s*[-~–]\s*(\d{1,2}):(\d{2})\s*$""").find(s) ?: return null
            val (h1, m1, h2, m2) = m.destructured.toList().map { it.toInt() }
            if (h1 !in 0..23 || h2 !in 0..23 || m1 !in 0..59 || m2 !in 0..59) return null
            return DailyWindow(LocalTime.of(h1, m1), LocalTime.of(h2, m2))
        }
    }
}

data class FilterRules(
    val skipDeclined: Boolean,
    val skipOof: Boolean,
    val skipWindows: List<DailyWindow>,
    val skipFree: Boolean = false,
) {
    /** 返回被跳过的原因, 不跳过则返回 null。全天事件不参与时间窗口过滤。 */
    fun reason(e: OutlookEvent, zone: ZoneId = ZoneId.systemDefault()): String? {
        if (skipDeclined && e.response == "declined") return "declined"
        if (skipOof && e.showAs == "oof") return "oof"
        if (skipFree && e.showAs == "free") return "free"
        if (!e.isAllDay && skipWindows.isNotEmpty()) {
            val local = Instant.ofEpochMilli(e.startMillis).atZone(zone).toLocalTime()
            if (skipWindows.any { it.contains(local) }) return "window"
        }
        return null
    }
}
