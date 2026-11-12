package com.maxyuan.outlookcalsync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class EventFilterTest {
    private val sh = ZoneId.of("Asia/Shanghai")

    private fun ev(
        hourLocal: Int, minute: Int = 0, response: String = "accepted",
        showAs: String = "busy", allDay: Boolean = false,
    ): OutlookEvent {
        val start = LocalDate.of(2026, 11, 13).atTime(hourLocal, minute).atZone(sh).toInstant().toEpochMilli()
        return OutlookEvent("id", "uid", "t", start, start + 3_600_000, allDay, "", "", null, showAs, response, "", "")
    }

    private fun rules(d: Boolean = false, o: Boolean = false, vararg w: String) =
        FilterRules(d, o, w.mapNotNull { DailyWindow.parse(it) })

    @Test fun declinedSkippedOnlyWhenEnabled() {
        assertEquals("declined", rules(d = true).reason(ev(10, response = "declined"), sh))
        assertNull(rules(d = false).reason(ev(10, response = "declined"), sh))
        assertNull(rules(d = true).reason(ev(10, response = "accepted"), sh))
        assertNull(rules(d = true).reason(ev(10, response = "notResponded"), sh))
    }

    @Test fun oofSkippedOnlyWhenEnabled() {
        assertEquals("oof", rules(o = true).reason(ev(10, showAs = "oof"), sh))
        assertNull(rules(o = false).reason(ev(10, showAs = "oof"), sh))
        assertNull(rules(o = true).reason(ev(10, showAs = "tentative"), sh))
    }

    @Test fun freeSkippedOnlyWhenEnabled() {
        assertEquals("free", FilterRules(false, false, emptyList(), skipFree = true).reason(ev(10, showAs = "free"), sh))
        assertNull(FilterRules(false, false, emptyList(), skipFree = false).reason(ev(10, showAs = "free"), sh))
        assertNull(FilterRules(false, false, emptyList(), skipFree = true).reason(ev(10, showAs = "busy"), sh))
        assertNull(FilterRules(false, false, emptyList(), skipFree = true).reason(ev(10, showAs = "tentative"), sh))
    }

    // 这里测的是 FilterRules 构造参数的默认值; App 里设置项的默认值(开)在 SecureStore
    @Test fun freeNotSkippedWhenRulesFlagOmitted() {
        assertNull(FilterRules(true, true, emptyList()).reason(ev(10, showAs = "free"), sh))
    }

    @Test fun freeAppliesToAllDayEventsToo() {
        assertEquals("free", FilterRules(false, false, emptyList(), skipFree = true).reason(ev(0, showAs = "free", allDay = true), sh))
    }

    @Test fun oofBeforeFreeBeforeWindow() {
        val r = FilterRules(false, true, listOf(DailyWindow.parse("12:00-13:00")!!), skipFree = true)
        assertEquals("oof", r.reason(ev(12, showAs = "oof"), sh))
        assertEquals("free", r.reason(ev(12, showAs = "free"), sh))
        assertEquals("window", r.reason(ev(12, showAs = "busy"), sh))
    }

    @Test fun windowIsHalfOpen() {
        val r = rules(w = arrayOf("12:00-13:00"))
        assertEquals("window", r.reason(ev(12, 0), sh))
        assertEquals("window", r.reason(ev(12, 59), sh))
        assertNull(r.reason(ev(11, 59), sh))
        assertNull(r.reason(ev(13, 0), sh))
    }

    @Test fun windowCrossingMidnight() {
        val r = rules(w = arrayOf("22:00-07:00"))
        assertEquals("window", r.reason(ev(23, 30), sh))
        assertEquals("window", r.reason(ev(0, 0), sh))
        assertEquals("window", r.reason(ev(6, 59), sh))
        assertNull(r.reason(ev(7, 0), sh))
        assertNull(r.reason(ev(12, 0), sh))
        assertNull(r.reason(ev(21, 59), sh))
    }

    @Test fun multipleWindowsAndAllDayIgnored() {
        val r = rules(w = arrayOf("12:00-13:00", "18:00-19:00"))
        assertEquals("window", r.reason(ev(18, 30), sh))
        assertNull(r.reason(ev(12, 0, allDay = true), sh))
    }

    @Test fun windowUsesGivenZoneNotUtc() {
        // 12:30 上海 = 04:30 UTC; 若误按 UTC 判断就不会命中
        assertEquals("window", rules(w = arrayOf("12:00-13:00")).reason(ev(12, 30), sh))
    }

    @Test fun parseWindow() {
        assertEquals(DailyWindow(LocalTime.of(9, 5), LocalTime.of(10, 0)), DailyWindow.parse(" 9:05 - 10:00 "))
        assertEquals(DailyWindow(LocalTime.of(12, 0), LocalTime.of(13, 0)), DailyWindow.parse("12:00~13:00"))
        assertNull(DailyWindow.parse("25:00-26:00"))
        assertNull(DailyWindow.parse("12:00"))
        assertNull(DailyWindow.parse("abc"))
        assertNull(DailyWindow.parse("12:60-13:00"))
    }

    @Test fun priorityDeclinedBeforeOofBeforeWindow() {
        val e = ev(12, response = "declined", showAs = "oof")
        assertEquals("declined", rules(true, true, "12:00-13:00").reason(e, sh))
        assertTrue(rules(false, false).reason(e, sh) == null)
    }
}
