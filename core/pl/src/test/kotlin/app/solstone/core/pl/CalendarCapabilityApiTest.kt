// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.pl

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CalendarCapabilityApiTest {
    @Test
    fun aListWithCalendarNotesIsAParsedYes() {
        val read = parseCalendarCapability(200, """{"capabilities":["segments","calendar_notes"]}""")
        assertEquals(CalendarCapabilityRead.Parsed(true), read)
    }

    @Test
    fun aListWithoutCalendarNotesIsAParsedNo() {
        val read = parseCalendarCapability(200, """{"capabilities":["segments"]}""")
        val parsed = assertIs<CalendarCapabilityRead.Parsed>(read)
        assertFalse(parsed.calendarNotes)
    }

    @Test
    fun aNon200UnparsableBodyOrMissingListIsAFailedRead() {
        assertEquals(CalendarCapabilityRead.Failed, parseCalendarCapability(503, """{"capabilities":["calendar_notes"]}"""))
        assertEquals(CalendarCapabilityRead.Failed, parseCalendarCapability(200, "not-json"))
        assertEquals(CalendarCapabilityRead.Parsed(false), parseCalendarCapability(200, """{"status":"ok"}"""))
        assertEquals(CalendarCapabilityRead.Failed, parseCalendarCapability(200, """{"capabilities":"calendar_notes"}"""))
    }

    @Test
    fun fetchReadsSystemStatusAndDoesNotTreatAFailedReadAsANo() {
        val client = ScriptedClient(
            HttpResponse(200, emptyMap(), """{"capabilities":["calendar_notes"]}""".toByteArray()),
        )
        val read = fetchCalendarCapability(client)
        assertTrue(assertIs<CalendarCapabilityRead.Parsed>(read).calendarNotes)
        assertEquals("GET", client.method)
        assertEquals("/api/system/status", client.path)
        assertEquals("no-cache", client.headers["Cache-Control"])
    }

    private class ScriptedClient(private val response: HttpResponse) : PlHttpClient {
        var method: String? = null
        var path: String? = null
        var headers: Map<String, String> = emptyMap()

        override fun request(
            method: String,
            path: String,
            headers: Map<String, String>,
            body: ByteArray?,
            maxResponseBytes: Int,
        ): HttpResponse {
            this.method = method
            this.path = path
            this.headers = headers
            return response
        }
    }
}
