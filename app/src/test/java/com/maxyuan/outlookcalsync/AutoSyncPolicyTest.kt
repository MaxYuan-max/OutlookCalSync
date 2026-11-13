package com.maxyuan.outlookcalsync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoSyncPolicyTest {
    @Test fun noneIsNotCoercedToFifteenMinutes() {
        assertEquals(AutoSyncPolicy.NONE, AutoSyncPolicy.normalizeInterval(0))
        assertEquals(AutoSyncPolicy.NONE, AutoSyncPolicy.normalizeInterval(-1))
    }

    @Test fun positiveIntervalsRespectWorkManagerMinimum() {
        assertEquals(15, AutoSyncPolicy.normalizeInterval(1))
        assertEquals(15, AutoSyncPolicy.normalizeInterval(15))
        assertEquals(30, AutoSyncPolicy.normalizeInterval(30))
        assertEquals(1440, AutoSyncPolicy.normalizeInterval(1440))
    }

    @Test fun disabledLegacySettingDisplaysNone() {
        assertEquals(AutoSyncPolicy.NONE, AutoSyncPolicy.activeIntervalMinutes(false, 30))
    }

    @Test fun enabledExistingIntervalIsPreserved() {
        assertEquals(30, AutoSyncPolicy.activeIntervalMinutes(true, 30))
    }

    @Test fun zeroIntervalWinsEvenWithStaleEnabledFlag() {
        assertEquals(AutoSyncPolicy.NONE, AutoSyncPolicy.activeIntervalMinutes(true, 0))
        assertFalse(AutoSyncPolicy.shouldRetry(true, 0))
    }

    @Test fun automaticJobsDoNotRunInManualOnlyMode() {
        for (attempt in listOf(0, 1, 8)) {
            assertFalse(AutoSyncPolicy.shouldRun(true, attempt, false, 0))
            assertFalse(AutoSyncPolicy.shouldRun(true, attempt, false, 30))
        }
    }

    @Test fun explicitManualSyncStillRunsWithNoneSelected() {
        assertTrue(AutoSyncPolicy.shouldRun(false, 0, false, 0))
        assertTrue(AutoSyncPolicy.shouldRun(false, 0, false, 30))
    }

    @Test fun queuedManualRetriesDoNotRunWithNoneSelected() {
        assertFalse(AutoSyncPolicy.shouldRun(false, 1, false, 0))
        assertFalse(AutoSyncPolicy.shouldRun(false, 5, false, 30))
    }

    @Test fun failureDoesNotScheduleRetriesInManualOnlyMode() {
        assertFalse(AutoSyncPolicy.shouldRetry(false, 0))
        assertFalse(AutoSyncPolicy.shouldRetry(false, 30))
        assertTrue(AutoSyncPolicy.shouldRetry(true, 30))
    }

    @Test fun automaticAndRetryJobsRunAgainWhenEnabled() {
        assertTrue(AutoSyncPolicy.shouldRun(true, 0, true, 30))
        assertTrue(AutoSyncPolicy.shouldRun(true, 3, true, 30))
        assertTrue(AutoSyncPolicy.shouldRun(false, 1, true, 30))
    }
}
