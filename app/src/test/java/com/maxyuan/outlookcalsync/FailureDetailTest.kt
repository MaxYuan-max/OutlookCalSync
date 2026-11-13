package com.maxyuan.outlookcalsync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FailureDetailTest {
    @Test fun includesOnlyErrorCodeAndRequestId() {
        val d = failureDetail(HttpResult(500, """{"error":{"code":"ErrorInternalServerError","message":"private event title","innerError":{"date":"2026-11-13"}}}""", "abc-123"))
        assertEquals("ErrorInternalServerError | request-id=abc-123", d)
        assertFalse(d.contains("private event title"))
        assertFalse(d.contains("2026-11-13"))
    }

    @Test fun emptyBodyStillShowsRequestId() {
        assertEquals("request-id=abc", failureDetail(HttpResult(500, "", "abc")))
    }

    @Test fun emptyBodyAndNoRequestIdIsEmpty() {
        assertEquals("", failureDetail(HttpResult(500, "", "")))
    }

    @Test fun arbitraryBodyIsNotPersisted() {
        val d = failureDetail(HttpResult(500, "x".repeat(1000), ""))
        assertEquals("", d)
    }

    @Test fun whitespaceInResponseDoesNotAffectErrorCode() {
        val d = failureDetail(HttpResult(503, "{\n  \"error\": { \"code\": \"UnknownError\" }\n}", "abc-123"))
        assertEquals("UnknownError | request-id=abc-123", d)
        assertFalse(d.contains("\n"))
    }

    @Test fun oauthDescriptionAndCredentialsAreNotPersisted() {
        val d = failureDetail(HttpResult(400, """{"error":"invalid_grant","error_description":"user@example.invalid tenant-private","refresh_token":"test-secret","access_token":"test-access"}""", "abc-123"))
        assertEquals("invalid_grant | request-id=abc-123", d)
        for (secret in listOf("user@example.invalid", "tenant-private", "test-secret", "test-access")) {
            assertFalse(d.contains(secret))
        }
    }

    @Test fun successfulTokenResponseIsNeverCopiedIntoFailureDetail() {
        val d = failureDetail(HttpResult(200, """{"access_token":"test-access","refresh_token":"test-refresh"}""", "abc-123"))
        assertEquals("request-id=abc-123", d)
    }

    @Test fun invalidOrHtmlResponseStillPreservesRequestId() {
        assertEquals("request-id=abc-123", failureDetail(HttpResult(502, "<html>private response</html>", "abc-123")))
        assertEquals("request-id=abc-123", failureDetail(HttpResult(502, "{\"error\":", "abc-123")))
    }

    @Test fun invalidErrorCodesAreDropped() {
        for (code in listOf("user@example.invalid", "a".repeat(101), "some error", "")) {
            val body = """{"error":{"code":"$code"}}"""
            assertEquals("request-id=abc-123", failureDetail(HttpResult(500, body, "abc-123")))
        }
    }

    @Test fun invalidRequestIdsAreDropped() {
        for (requestId in listOf("user@example.invalid", "abc\nprivate", "x".repeat(129))) {
            assertEquals("UnknownError", failureDetail(HttpResult(503, """{"error":{"code":"UnknownError"}}""", requestId)))
        }
    }
}
