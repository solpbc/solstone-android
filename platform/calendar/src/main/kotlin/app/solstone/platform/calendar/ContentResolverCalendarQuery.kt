// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.calendar

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.provider.CalendarContract

/** Binds [CalendarQuerySurface] to [ContentResolver.query]. Never calls `CalendarContract.Instances.query`. */
class ContentResolverCalendarQuery(
    private val resolver: ContentResolver,
) : CalendarQuerySurface {
    override fun query(query: CalendarQuery): CalendarCursor? {
        val cursor = resolver.query(
            calendarQueryUri(query),
            query.projection,
            query.selection,
            query.selectionArgs,
            query.sortOrder,
        ) ?: return null
        return AndroidCalendarCursor(cursor)
    }
}

/** The instances window is two path segments on [CalendarContract.Instances.CONTENT_URI]. */
internal fun calendarQueryUri(query: CalendarQuery): Uri = when (query.target) {
    CalendarQueryTarget.CALENDARS -> CalendarContract.Calendars.CONTENT_URI
    CalendarQueryTarget.EVENTS -> CalendarContract.Events.CONTENT_URI
    CalendarQueryTarget.ATTENDEES -> CalendarContract.Attendees.CONTENT_URI
    CalendarQueryTarget.INSTANCES -> CalendarContract.Instances.CONTENT_URI.buildUpon()
        .appendPath((query.begin ?: 0L).toString())
        .appendPath((query.end ?: 0L).toString())
        .build()
}

private class AndroidCalendarCursor(
    private val cursor: Cursor,
) : CalendarCursor {
    override fun moveToNext(): Boolean = cursor.moveToNext()
    override fun column(name: String): Int = cursor.getColumnIndex(name)
    override fun string(index: Int): String? = cursor.getString(index)
    override fun long(index: Int): Long = cursor.getLong(index)
    override fun int(index: Int): Int = cursor.getInt(index)
    override fun isNull(index: Int): Boolean = cursor.isNull(index)
    override fun close() = cursor.close()
}
