package com.maxyuan.outlookcalsync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val store = SecureStore(applicationContext)
        store.lastResult = "${now()} 同步中…"
        return try {
            val client = GraphClient(store)
            val token = client.accessToken()
            val today = LocalDate.now(ZoneOffset.UTC)
            val back = store.windowBackDays
            val ahead = store.windowAheadDays
            val all = client.fetchEvents(token, back, ahead)
            val rules = store.filterRules()
            val skipped = all.groupingBy { rules.reason(it) }.eachCount()
            val events = all.filter { rules.reason(it) == null }
            val winStart = today.minusDays(back.toLong()).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
            val winEnd = today.plusDays(ahead.toLong()).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
            val s = CalendarWriter(applicationContext, store).sync(events, winStart, winEnd)
            val filtered = skipped.filterKeys { it != null }
            val filterText = if (filtered.isEmpty()) "" else
                ", 已过滤 ${filtered.values.sum()} (拒绝 ${filtered["declined"] ?: 0}/外出 ${filtered["oof"] ?: 0}/" +
                    "空闲 ${filtered["free"] ?: 0}/时段 ${filtered["window"] ?: 0})"
            store.lastResult = "${now()} 成功: 写入 ${events.size} 条, 新增 ${s.added}, 更新 ${s.updated}, " +
                "删除 ${s.deleted}, 无变化 ${s.unchanged}$filterText"
            recordSuccess(store)
            Result.success()
        } catch (e: GraphException) {
            val msg = e.message ?: "未知错误"
            store.lastResult = "${now()} 失败: $msg"
            recordFailure(store, e.needsUser, msg)
            // 需要用户处理的错误重试没有意义; 暂时性错误交给退避重试
            if (e.needsUser) Result.failure() else Result.retry()
        } catch (e: SecurityException) {
            val msg = "没有日历权限, 请打开 App 授权"
            store.lastResult = "${now()} 失败: $msg"
            recordFailure(store, true, msg)
            Result.failure()
        } catch (e: Exception) {
            // 网络不通、超时等, 视为暂时性
            val msg = "${e.javaClass.simpleName}: ${e.message}"
            store.lastResult = "${now()} 失败: $msg"
            recordFailure(store, false, msg)
            Result.retry()
        }
    }

    private fun recordSuccess(store: SecureStore) {
        val (decision, next) = FailurePolicy.onSuccess(store.failureState)
        store.failureState = next
        if (decision is Decision.ClearNotification) Notifier.clear(applicationContext)
    }

    private fun recordFailure(store: SecureStore, needsUser: Boolean, msg: String) {
        val (decision, next) = FailurePolicy.onFailure(store.failureState, needsUser, msg)
        store.failureState = next
        if (decision is Decision.Notify) Notifier.showFailure(applicationContext, needsUser, msg)
    }

    private fun now() = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date())

    companion object {
        private const val PERIODIC = "outlook_periodic"
        private const val ONCE = "outlook_once"

        private val net = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun schedule(ctx: Context) {
            val store = SecureStore(ctx)
            store.autoSync = true
            val minutes = store.intervalMinutes.coerceAtLeast(15).toLong()
            val req = PeriodicWorkRequestBuilder<SyncWorker>(minutes, TimeUnit.MINUTES)
                .setConstraints(net)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(ctx)
                .enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, req)
        }

        fun syncNow(ctx: Context) {
            val req = OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(net).build()
            WorkManager.getInstance(ctx).enqueueUniqueWork(ONCE, ExistingWorkPolicy.REPLACE, req)
        }

        fun cancel(ctx: Context) {
            SecureStore(ctx).autoSync = false
            WorkManager.getInstance(ctx).cancelUniqueWork(PERIODIC)
        }
    }
}
