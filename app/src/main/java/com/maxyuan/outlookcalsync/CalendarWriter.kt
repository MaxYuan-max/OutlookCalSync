package com.maxyuan.outlookcalsync

import android.content.ContentProviderOperation
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Reminders
import java.util.TimeZone

data class SyncStats(val added: Int, val updated: Int, val deleted: Int, val unchanged: Int)

/**
 * Writes Outlook events into a dedicated local calendar. Only events that belong to this calendar
 * are ever touched, so other calendars on the device are never modified.
 */
class CalendarWriter(context: Context, private val store: SecureStore) {
    private val cr: ContentResolver = context.contentResolver

    private fun asSyncAdapter(uri: Uri): Uri = uri.buildUpon()
        .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
        .appendQueryParameter(Calendars.ACCOUNT_NAME, ACCOUNT_NAME)
        .appendQueryParameter(Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
        .build()

    fun ensureCalendar(): Long {
        val saved = store.calendarId
        if (saved >= 0 && exists(saved)) return saved

        cr.query(
            Calendars.CONTENT_URI, arrayOf(Calendars._ID),
            "${Calendars.ACCOUNT_NAME}=? AND ${Calendars.ACCOUNT_TYPE}=? AND ${Calendars.NAME}=?",
            arrayOf(ACCOUNT_NAME, CalendarContract.ACCOUNT_TYPE_LOCAL, CAL_NAME), null
        )?.use { if (it.moveToFirst()) return it.getLong(0).also { id -> store.calendarId = id } }

        val v = ContentValues().apply {
            put(Calendars.ACCOUNT_NAME, ACCOUNT_NAME)
            put(Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
            put(Calendars.NAME, CAL_NAME)
            put(Calendars.CALENDAR_DISPLAY_NAME, "Outlook")
            put(Calendars.CALENDAR_COLOR, Color.rgb(0, 120, 212))
            put(Calendars.CALENDAR_ACCESS_LEVEL, Calendars.CAL_ACCESS_OWNER)
            put(Calendars.OWNER_ACCOUNT, ACCOUNT_NAME)
            put(Calendars.VISIBLE, 1)
            put(Calendars.SYNC_EVENTS, 1)
            put(Calendars.CALENDAR_TIME_ZONE, TimeZone.getDefault().id)
        }
        val uri = cr.insert(asSyncAdapter(Calendars.CONTENT_URI), v)
            ?: error("无法创建本地日历 (系统拒绝)")
        return ContentUris.parseId(uri).also { store.calendarId = it }
    }

    private fun exists(id: Long): Boolean =
        cr.query(ContentUris.withAppendedId(Calendars.CONTENT_URI, id), arrayOf(Calendars._ID), null, null, null)
            ?.use { it.moveToFirst() } ?: false

    /** Key is stable per occurrence: series UID + start time (recurring instances share iCalUId). */
    private fun keyOf(e: OutlookEvent) = "${e.iCalUId}|${e.startMillis}"

    private data class Existing(val eventId: Long, val stamp: String)

    private fun loadExisting(calId: Long): Map<String, Existing> {
        val map = HashMap<String, Existing>()
        cr.query(
            Events.CONTENT_URI, arrayOf(Events._ID, Events.SYNC_DATA1, Events.SYNC_DATA2),
            "${Events.CALENDAR_ID}=? AND ${Events.DELETED}=0", arrayOf(calId.toString()), null
        )?.use {
            while (it.moveToNext()) {
                val k = it.getString(1) ?: continue
                map[k] = Existing(it.getLong(0), it.getString(2) ?: "")
            }
        }
        return map
    }

    fun sync(events: List<OutlookEvent>, windowStart: Long, windowEnd: Long): SyncStats {
        val calId = ensureCalendar()
        val existing = loadExisting(calId)
        val incoming = events.associateBy(::keyOf)

        val ops = ArrayList<ContentProviderOperation>()
        var added = 0; var updated = 0; var unchanged = 0; var deleted = 0
        val evUri = asSyncAdapter(Events.CONTENT_URI)
        val remUri = asSyncAdapter(Reminders.CONTENT_URI)

        for ((key, e) in incoming) {
            val old = existing[key]
            if (old != null && old.stamp == syncStamp(e, ringEnabled)) { unchanged++; continue }

            val values = toValues(calId, key, e)
            if (old == null) {
                val idx = ops.size
                ops += ContentProviderOperation.newInsert(evUri).withValues(values).build()
                e.reminderMinutes?.let {
                    ops += ContentProviderOperation.newInsert(remUri)
                        .withValueBackReference(Reminders.EVENT_ID, idx)
                        .withValue(Reminders.MINUTES, it)
                        .withValue(Reminders.METHOD, Reminders.METHOD_ALERT).build()
                }
                added++
            } else {
                ops += ContentProviderOperation.newUpdate(
                    ContentUris.withAppendedId(evUri, old.eventId)).withValues(values).build()
                ops += ContentProviderOperation.newDelete(remUri)
                    .withSelection("${Reminders.EVENT_ID}=?", arrayOf(old.eventId.toString())).build()
                e.reminderMinutes?.let {
                    ops += ContentProviderOperation.newInsert(remUri)
                        .withValue(Reminders.EVENT_ID, old.eventId)
                        .withValue(Reminders.MINUTES, it)
                        .withValue(Reminders.METHOD, Reminders.METHOD_ALERT).build()
                }
                updated++
            }
        }

        // Remove only events that fall inside the fetched window but are gone from Outlook.
        for ((key, old) in existing) {
            if (key in incoming) continue
            val start = key.substringAfterLast('|').toLongOrNull() ?: continue
            if (start < windowStart || start >= windowEnd) continue
            ops += ContentProviderOperation.newDelete(
                ContentUris.withAppendedId(evUri, old.eventId)).build()
            deleted++
        }

        ops.chunked(400).forEach { cr.applyBatch(CalendarContract.AUTHORITY, ArrayList(it)) }
        return SyncStats(added, updated, deleted, unchanged)
    }

    private fun toValues(calId: Long, key: String, e: OutlookEvent) = ContentValues().apply {
        put(Events.CALENDAR_ID, calId)
        put(Events.TITLE, e.subject)
        put(Events.EVENT_LOCATION, e.location)
        put(Events.DESCRIPTION, e.description)
        put(Events.AVAILABILITY,
            if (e.showAs == "free") Events.AVAILABILITY_FREE else Events.AVAILABILITY_BUSY)
        put(Events.HAS_ALARM, if (e.reminderMinutes != null) 1 else 0)
        // ColorOS 日历的"响铃"开关, 在 events 表里是私有列 force_reminder (1=响铃, 0=普通通知)。
        // 其它系统没有这一列, 写入会失败, 所以只在开启且确有提醒时才写。
        if (ringEnabled) put(FORCE_REMINDER, if (e.reminderMinutes != null) 1 else 0)
        put(Events.SYNC_DATA1, key)
        put(Events.SYNC_DATA2, syncStamp(e, ringEnabled))
        if (e.isAllDay) {
            put(Events.ALL_DAY, 1)
            put(Events.EVENT_TIMEZONE, "UTC")
            put(Events.DTSTART, e.startMillis)
            put(Events.DTEND, e.endMillis)
        } else {
            put(Events.ALL_DAY, 0)
            put(Events.EVENT_TIMEZONE, "UTC")
            put(Events.DTSTART, e.startMillis)
            put(Events.DTEND, e.endMillis)
        }
    }

    private val ringEnabled get() = store.ringReminder

    private companion object {
        const val FORCE_REMINDER = "force_reminder"
        const val ACCOUNT_NAME = "OutlookSync"
        const val CAL_NAME = "outlook_sync"
    }
}
