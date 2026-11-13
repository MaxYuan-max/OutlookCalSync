package com.maxyuan.outlookcalsync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

class RetryPolicyTest {
    @Test fun retriesOnlyTransientStatuses() {
        for (c in listOf(429, 502, 503, 504)) assertTrue("$c", RetryPolicy.isRetryableStatus(c))
        for (c in listOf(200, 400, 401, 403, 404, 408, 500)) assertFalse("$c", RetryPolicy.isRetryableStatus(c))
    }

    @Test fun retriesTimeoutAndIoButNotOtherErrors() {
        assertTrue(RetryPolicy.isRetryableError(SocketTimeoutException("t")))
        assertTrue(RetryPolicy.isRetryableError(IOException("x")))
        assertFalse(RetryPolicy.isRetryableError(IllegalStateException("x")))
        assertFalse(RetryPolicy.isRetryableError(org.json.JSONException("x")))
    }

    @Test fun exponentialBackoffWithoutJitter() {
        assertEquals(2_000, RetryPolicy.delayMs(1))
        assertEquals(4_000, RetryPolicy.delayMs(2))
        assertEquals(8_000, RetryPolicy.delayMs(3))
    }

    @Test fun jitterAddsUpToQuarter() {
        assertEquals(2_500, RetryPolicy.delayMs(1, jitter = 1.0))
        assertEquals(2_000, RetryPolicy.delayMs(1, jitter = 0.0))
    }

    @Test fun delayIsCapped() {
        assertEquals(30_000, RetryPolicy.delayMs(10))
        assertEquals(30_000, RetryPolicy.delayMs(3, retryAfterSeconds = 120))
    }

    @Test fun retryAfterOverridesBackoff() {
        assertEquals(5_000, RetryPolicy.delayMs(1, retryAfterSeconds = 5))
        assertEquals(0, RetryPolicy.delayMs(1, retryAfterSeconds = 0))
    }

    @Test fun parseRetryAfter() {
        assertEquals(7L, RetryPolicy.parseRetryAfter(" 7 "))
        assertNull(RetryPolicy.parseRetryAfter("Wed, 21 Oct 2026 07:28:00 GMT"))
        assertNull(RetryPolicy.parseRetryAfter("-3"))
        assertNull(RetryPolicy.parseRetryAfter(null))
        assertNull(RetryPolicy.parseRetryAfter("abc"))
    }

    @Test fun perRequestRetriesAreKeptSmall() {
        assertEquals(1, RetryPolicy.MAX_RETRIES_PER_REQUEST)
    }
}
