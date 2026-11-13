package com.maxyuan.outlookcalsync

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
import androidx.work.workDataOf
import org.json.JSONException
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        // 手动和周期任务共享本地日历及令牌缓存，整个同步串行执行。
        val syncContext = currentCoroutineContext()
        syncLock.withLock {
            syncContext.ensureActive()
            val store = SecureStore(applicationContext)
            // 老版本的任务没有标记，保守地按自动任务处理，防止升级后旧重试继续运行。
            val automatic = inputData.getBoolean(AUTOMATIC, true)
            if (!AutoSyncPolicy.shouldRun(automatic, runAttemptCount, store.autoSync, store.intervalMinutes)) {
                return@withLock Result.success()
            }
            runSync(store) {
                syncContext.ensureActive()
                if (isStopped) throw CancellationException("同步任务已取消")
            }
        }
    }

    private fun runSync(store: SecureStore, checkCancelled: () -> Unit): Result {
        store.lastResult = "${now()} 同步中…"
        return try {
            checkCancelled()
            val client = GraphClient(store, checkCancelled)
            val token = client.accessToken()
            checkCancelled()
            val today = LocalDate.now(ZoneOffset.UTC)
            val back = store.windowBackDays
            val ahead = store.windowAheadDays
            val all = client.fetchEvents(token, back, ahead)
            val rules = store.filterRules()
            val skipped = all.groupingBy { rules.reason(it) }.eachCount()
            val events = all.filter { rules.reason(it) == null }
            val winStart = today.minusDays(back.toLong()).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
            val winEnd = today.plusDays(ahead.toLong()).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
            checkCancelled()
            val s = CalendarWriter(applicationContext, store).sync(events, winStart, winEnd)
            val filtered = skipped.filterKeys { it != null }
            val filterText = if (filtered.isEmpty()) "" else
                ", 已过滤 ${filtered.values.sum()} (拒绝 ${filtered["declined"] ?: 0}/外出 ${filtered["oof"] ?: 0}/" +
                    "空闲 ${filtered["free"] ?: 0}/时段 ${filtered["window"] ?: 0})"
            store.lastResult = "${now()} 成功: 写入 ${events.size} 条, 新增 ${s.added}, 更新 ${s.updated}, " +
                "删除 ${s.deleted}, 无变化 ${s.unchanged}$filterText"
            recordSuccess(store)
            Result.success()
        } catch (e: CancellationException) {
            store.lastResult = "${now()} 同步已取消"
            throw e
        } catch (e: GraphException) {
            val msg = e.message ?: "未知错误"
            store.lastResult = "${now()} 失败: $msg"
            recordFailure(store, e.needsUser, msg)
            // 需要用户处理的错误重试没有意义; 暂时性错误交给退避重试
            if (e.needsUser) Result.failure() else retryIfEnabled(store)
        } catch (e: SecurityException) {
            val msg = "没有日历权限, 请打开 App 授权"
            store.lastResult = "${now()} 失败: $msg"
            recordFailure(store, true, msg)
            Result.failure()
        } catch (e: Exception) {
            // 网络不通、超时等, 视为暂时性
            // JSON 解析异常可能在 message 里带原始响应，不持久化其内容。
            val msg = if (e is JSONException) "服务返回的数据格式异常" else "${e.javaClass.simpleName}: ${e.message}"
            store.lastResult = "${now()} 失败: $msg"
            recordFailure(store, false, msg)
            retryIfEnabled(store)
        }
    }

    private fun retryIfEnabled(store: SecureStore): Result =
        if (AutoSyncPolicy.shouldRetry(store.autoSync, store.intervalMinutes)) Result.retry() else Result.failure()

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
        private val syncLock = Mutex()
        private const val PERIODIC = "outlook_periodic"
        private const val ONCE = "outlook_once"
        private const val AUTOMATIC = "automatic"

        private val net = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun setInterval(ctx: Context, minutes: Int) {
            if (AutoSyncPolicy.normalizeInterval(minutes) == AutoSyncPolicy.NONE) {
                cancel(ctx)
            } else {
                SecureStore(ctx).setSyncInterval(minutes)
                schedule(ctx)
            }
        }

        fun schedule(ctx: Context) {
            val store = SecureStore(ctx)
            val minutes = AutoSyncPolicy.activeIntervalMinutes(store.autoSync, store.intervalMinutes)
            if (minutes == AutoSyncPolicy.NONE) {
                cancel(ctx)
                return
            }
            val req = PeriodicWorkRequestBuilder<SyncWorker>(minutes.toLong(), TimeUnit.MINUTES)
                .setConstraints(net)
                .setInputData(workDataOf(AUTOMATIC to true))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(ctx)
                .enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, req)
        }

        fun syncNow(ctx: Context) {
            val req = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(net)
                .setInputData(workDataOf(AUTOMATIC to false))
                .build()
            WorkManager.getInstance(ctx).enqueueUniqueWork(ONCE, ExistingWorkPolicy.REPLACE, req)
        }

        fun cancel(ctx: Context) {
            SecureStore(ctx).setSyncInterval(AutoSyncPolicy.NONE)
            val wm = WorkManager.getInstance(ctx)
            wm.cancelUniqueWork(PERIODIC)
            wm.cancelUniqueWork(ONCE)
        }
    }
}
