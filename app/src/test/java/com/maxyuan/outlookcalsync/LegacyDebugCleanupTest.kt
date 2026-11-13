package com.maxyuan.outlookcalsync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LegacyDebugCleanupTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun deletesOnlyLegacyLog() {
        val filesDir = temp.newFolder("files")
        val log = File(filesDir, "debug.log").apply { writeText("old diagnostics") }
        val other = File(filesDir, "other.txt").apply { writeText("keep") }
        val preferences = temp.newFolder("shared_prefs")
        val settings = File(preferences, "settings.xml").apply { writeText("credentials and settings") }

        assertTrue(LegacyDebugCleanup.deleteLog(filesDir))
        assertFalse(log.exists())
        assertEquals("keep", other.readText())
        assertEquals("credentials and settings", settings.readText())
    }

    @Test fun deletionIsIdempotentWhenLogIsAlreadyGone() {
        val filesDir = temp.newFolder("files")
        assertTrue(LegacyDebugCleanup.deleteLog(filesDir))
        assertTrue(LegacyDebugCleanup.deleteLog(filesDir))
    }

    @Test fun doesNotRecursivelyDeleteADirectoryCalledDebugLog() {
        val filesDir = temp.newFolder("files")
        val directory = File(filesDir, "debug.log").apply { mkdir() }
        val child = File(directory, "keep.txt").apply { writeText("keep") }
        assertFalse(LegacyDebugCleanup.deleteLog(filesDir))
        assertTrue(directory.isDirectory)
        assertEquals("keep", child.readText())
    }

    @Test fun legacyGraphResponseIsCompacted() {
        val old = "11-13 11:39:36 失败: 读取日历失败 (HTTP 503) 第 1 页: " +
            """{"error":{"code":"UnknownError","message":"user@example.invalid private event","innerError":{"request-id":"abc-123"}}}"""
        assertEquals(
            "11-13 11:39:36 失败: 读取日历失败 (HTTP 503) 第 1 页: UnknownError | request-id=abc-123",
            LegacyDebugCleanup.compactLastResult(old),
        )
    }

    @Test fun truncatedLegacyResponseUsesHeaderRequestId() {
        val old = "11-13 11:39:36 失败: 读取日历失败 (HTTP 502) 第 2 页: " +
            """{"error":{"code":"UnknownError","message":"private truncated | request-id=abc-123"""
        assertEquals(
            "11-13 11:39:36 失败: 读取日历失败 (HTTP 502) 第 2 页: UnknownError | request-id=abc-123",
            LegacyDebugCleanup.compactLastResult(old),
        )
    }

    @Test fun oldOauthDescriptionIsDropped() {
        val old = "11-13 11:39:36 失败: 换取 token 失败 (HTTP 400) invalid_grant: " +
            "user@example.invalid tenant-private | request-id=abc-123"
        assertEquals(
            "11-13 11:39:36 失败: 换取 token 失败 (HTTP 400): invalid_grant | request-id=abc-123",
            LegacyDebugCleanup.compactLastResult(old),
        )
    }

    @Test fun rawBodyWithoutStructuredCodeIsRemoved() {
        val old = "11-13 11:39:36 失败: 读取日历失败 (HTTP 500): <html>private response</html>"
        assertEquals("11-13 11:39:36 失败: 读取日历失败 (HTTP 500)", LegacyDebugCleanup.compactLastResult(old))
    }

    @Test fun compactingSafeStatusAgainPreservesIt() {
        val safe = "11-13 11:39:36 失败: 读取日历失败 (HTTP 503) 第 1 页: UnknownError | request-id=abc-123"
        assertEquals(safe, LegacyDebugCleanup.compactLastResult(safe))
    }

    @Test fun successAndCancellationStatusesArePreserved() {
        for (value in listOf("11-13 11:39:36 成功: 写入 60 条", "11-13 11:39:36 同步已取消", "", "11-13 11:39:36 失败: SocketTimeoutException: timeout")) {
            assertEquals(value, LegacyDebugCleanup.compactLastResult(value))
        }
    }
}
