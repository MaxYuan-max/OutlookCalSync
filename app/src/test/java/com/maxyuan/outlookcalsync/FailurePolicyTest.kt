package com.maxyuan.outlookcalsync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FailurePolicyTest {
    private fun fail(s: FailureState, user: Boolean, msg: String) = FailurePolicy.onFailure(s, user, msg)

    @Test fun userActionErrorNotifiesImmediately() {
        val (d, s) = fail(FailureState(), true, "invalid_grant")
        assertTrue(d is Decision.Notify)
        assertEquals(1, s.consecutive)
    }

    @Test fun transientNeedsThreeInARow() {
        var st = FailureState()
        val (d1, s1) = fail(st, false, "timeout"); st = s1
        assertEquals(Decision.Nothing, d1)
        val (d2, s2) = fail(st, false, "timeout"); st = s2
        assertEquals(Decision.Nothing, d2)
        val (d3, _) = fail(st, false, "timeout")
        assertTrue(d3 is Decision.Notify)
    }

    @Test fun sameReasonNotifiesOnlyOnce() {
        var st = FailureState()
        val (d1, s1) = fail(st, true, "invalid_grant"); st = s1
        assertTrue(d1 is Decision.Notify)
        repeat(5) {
            val (d, s) = fail(st, true, "invalid_grant"); st = s
            assertEquals(Decision.Nothing, d)
        }
    }

    @Test fun differentReasonNotifiesAgain() {
        var st = FailureState()
        st = (fail(st, true, "invalid_grant") as Pair).second
        val (d, _) = fail(st, true, "HTTP 403")
        assertTrue(d is Decision.Notify)
    }

    @Test fun successClearsAndResets() {
        var st = FailureState()
        st = fail(st, true, "invalid_grant").second
        val (d, s) = FailurePolicy.onSuccess(st)
        assertEquals(Decision.ClearNotification, d)
        assertEquals(FailureState(), s)
    }

    @Test fun successWithoutPriorNotificationDoesNothing() {
        val (d, s) = FailurePolicy.onSuccess(FailureState(consecutive = 2))
        assertEquals(Decision.Nothing, d)
        assertEquals(0, s.consecutive)
    }

    @Test fun failureAfterRecoveryNotifiesAgain() {
        var st = fail(FailureState(), true, "invalid_grant").second
        st = FailurePolicy.onSuccess(st).second
        assertTrue(fail(st, true, "invalid_grant").first is Decision.Notify)
    }

    @Test fun transientCounterDoesNotResetBetweenFailures() {
        var st = FailureState()
        repeat(2) { st = fail(st, false, "timeout").second }
        assertEquals(2, st.consecutive)
        assertTrue(fail(st, false, "timeout").first is Decision.Notify)
    }
}
