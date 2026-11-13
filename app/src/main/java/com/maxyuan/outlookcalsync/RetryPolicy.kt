package com.maxyuan.outlookcalsync

import java.io.IOException
import java.net.SocketTimeoutException

/**
 * 请求级重试: 只针对服务端暂时性错误。凭据/权限错误(4xx)重试没有意义, 交给上层处理。
 * 总等待有上限, 避免和 WorkManager 自己的退避叠加成"重试风暴"。
 */
object RetryPolicy {
    /**
     * 单个请求最多重试次数。服务端整体故障时(502/504 要等 20~30 秒才返回), 重试 3 次要耗 2 分钟还大概率不成功,
     * 既耗电又占着 WorkManager 任务。只重试 1 次应对偶发抖动, 其余交给下一个同步周期。
     */
    const val MAX_RETRIES_PER_REQUEST = 1
    private const val BASE_DELAY_MS = 2_000L
    private const val MAX_DELAY_MS = 30_000L

    /** 429 限流, 502/503/504 网关或服务暂时不可用。500 不重试: 没有证据说明它是暂时的。 */
    fun isRetryableStatus(code: Int) = code == 429 || code == 502 || code == 503 || code == 504

    /** 超时和连接类 IO 错误可重试; 其它异常(解析错误等)不可重试。 */
    fun isRetryableError(e: Throwable) = e is SocketTimeoutException || e is IOException

    /**
     * 第 [attempt] 次重试前的等待毫秒数 (attempt 从 1 开始)。
     * 指数退避 2/4/8 秒, 服务端给了 Retry-After 就以它为准, 都不超过 30 秒。jitter 在 [0,1) 内, 注入以便测试。
     */
    fun delayMs(attempt: Int, retryAfterSeconds: Long? = null, jitter: Double = 0.0): Long {
        if (retryAfterSeconds != null && retryAfterSeconds >= 0) {
            return (retryAfterSeconds * 1000).coerceAtMost(MAX_DELAY_MS)
        }
        val exp = BASE_DELAY_MS shl (attempt - 1).coerceIn(0, 10)
        val j = (exp * 0.25 * jitter).toLong()
        return (exp + j).coerceAtMost(MAX_DELAY_MS)
    }

    /** 解析 Retry-After 头, 只支持秒数写法; 日期写法或无效值返回 null。 */
    fun parseRetryAfter(value: String?): Long? = value?.trim()?.toLongOrNull()?.takeIf { it >= 0 }
}
