package com.maxyuan.outlookcalsync

/** 0 表示仅手动同步，不能被 WorkManager 的 15 分钟下限改回定时任务。 */
object AutoSyncPolicy {
    const val NONE = 0
    private const val MIN_INTERVAL_MINUTES = 15

    fun normalizeInterval(minutes: Int): Int =
        if (minutes <= NONE) NONE else minutes.coerceAtLeast(MIN_INTERVAL_MINUTES)

    fun activeIntervalMinutes(autoSync: Boolean, intervalMinutes: Int): Int =
        if (autoSync) normalizeInterval(intervalMinutes) else NONE

    fun shouldRun(
        automatic: Boolean,
        attempt: Int,
        autoSync: Boolean,
        intervalMinutes: Int,
    ): Boolean =
        (!automatic && attempt == 0) || shouldRetry(autoSync, intervalMinutes)

    /** 仅手动模式下不把失败的一次性任务交给 WorkManager 自动重试。 */
    fun shouldRetry(autoSync: Boolean, intervalMinutes: Int): Boolean =
        activeIntervalMinutes(autoSync, intervalMinutes) != NONE
}
