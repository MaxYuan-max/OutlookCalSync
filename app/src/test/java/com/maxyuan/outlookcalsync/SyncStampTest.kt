package com.maxyuan.outlookcalsync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class SyncStampTest {
    private fun ev(
        reminder: Int? = 15,
        lastModified: String = "2026-11-09T05:53:40Z",
        changeKey: String = "CK1",
    ) = OutlookEvent("id", "uid", "t", 0, 1, false, "", "", reminder, "busy", "accepted", lastModified, changeKey)

    // exception 实例: 通过主事件改提醒, lastModified 不刷新, 但 changeKey 会变
    @Test fun changeKeyChangeAloneChangesStamp() {
        assertNotEquals(syncStamp(ev(changeKey = "CK1"), true), syncStamp(ev(changeKey = "CK2"), true))
    }

    @Test fun lastModifiedAloneIsIgnoredWhenChangeKeyPresent() {
        assertEquals(
            syncStamp(ev(lastModified = "2026-11-09T05:53:40Z"), true),
            syncStamp(ev(lastModified = "2026-11-12T16:38:45Z"), true),
        )
    }

    @Test fun ringToggleChangesStamp() {
        assertNotEquals(syncStamp(ev(), true), syncStamp(ev(), false))
    }

    @Test fun identicalEventsHaveIdenticalStamp() {
        assertEquals(syncStamp(ev(), true), syncStamp(ev(), true))
    }

    // changeKey 缺失时的回退: 用 lastModified + 提醒分钟数
    @Test fun fallbackWhenChangeKeyMissing() {
        assertNotEquals(syncStamp(ev(15, changeKey = ""), true), syncStamp(ev(null, changeKey = ""), true))
        assertNotEquals(syncStamp(ev(15, changeKey = ""), true), syncStamp(ev(30, changeKey = ""), true))
        assertNotEquals(
            syncStamp(ev(15, "2026-11-09T05:53:40Z", ""), true),
            syncStamp(ev(15, "2026-11-12T16:38:45Z", ""), true),
        )
    }

    @Test fun fallbackAndChangeKeyStampsDiffer() {
        assertNotEquals(syncStamp(ev(changeKey = "CK1"), true), syncStamp(ev(changeKey = ""), true))
    }
}
