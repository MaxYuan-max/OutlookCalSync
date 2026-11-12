package com.maxyuan.outlookcalsync

/** 失败通知的决策状态, 持久化在 SecureStore 里。 */
data class FailureState(
    val consecutive: Int = 0,
    /** 已经通知过的失败原因 (key); 成功后清空。 */
    val notifiedKey: String = "",
)

sealed class Decision {
    data object Nothing : Decision()
    data class Notify(val newState: FailureState) : Decision()
    data object ClearNotification : Decision()
}

object FailurePolicy {
    /** 暂时性错误连续失败多少次才通知。 */
    const val TRANSIENT_THRESHOLD = 3

    /** 把错误归并成一个 key, 同一原因只通知一次。 */
    fun keyOf(needsUser: Boolean, message: String): String =
        (if (needsUser) "user:" else "transient:") + message.take(60)

    fun onSuccess(state: FailureState): Pair<Decision, FailureState> =
        (if (state.notifiedKey.isNotEmpty()) Decision.ClearNotification else Decision.Nothing) to FailureState()

    fun onFailure(state: FailureState, needsUser: Boolean, message: String): Pair<Decision, FailureState> {
        val count = state.consecutive + 1
        val key = keyOf(needsUser, message)
        val shouldNotify = needsUser || count >= TRANSIENT_THRESHOLD
        // 失败原因变了也算新问题, 重新通知
        val already = state.notifiedKey == key
        val next = FailureState(count, if (shouldNotify) key else state.notifiedKey)
        return if (shouldNotify && !already) Decision.Notify(next) to next else Decision.Nothing to next
    }
}
