package com.maxyuan.outlookcalsync

/**
 * 判断事件是否需要重写的指纹。用 changeKey 而不是 lastModifiedDateTime:
 * 改整个系列的提醒时, 已变成 exception 的实例内容会变, 但它们的 lastModifiedDateTime 不会刷新,
 * 而 changeKey 会变 (在真实日历数据里验证过)。
 * 响铃开关是本地设置, 不来自 Outlook, 单独带上, 这样切换开关后已有事件也会被重写。
 * changeKey 缺失时退回 lastModified + 提醒分钟数。
 */
fun syncStamp(e: OutlookEvent, ring: Boolean): String {
    val base = if (e.changeKey.isNotEmpty()) "ck=${e.changeKey}"
    else "lm=${e.lastModified}|rem=${e.reminderMinutes ?: "off"}"
    return "$base|ring=${if (ring) 1 else 0}"
}
