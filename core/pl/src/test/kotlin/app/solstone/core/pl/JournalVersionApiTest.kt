// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.pl

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class JournalVersionApiTest {
    @Test
    fun parsesValidVersionFromCurrent() {
        val json = """{"version":{"current":"1.2.3"}}"""
        assertEquals("1.2.3", parseJournalVersionCurrent(json))
    }

    @Test
    fun returnsNullWhenVersionObjectMissing() {
        val json = """{"status":"ok"}"""
        assertNull(parseJournalVersionCurrent(json))
    }

    @Test
    fun returnsNullWhenCurrentFieldMissing() {
        val json = """{"version":{"other":"val"}}"""
        assertNull(parseJournalVersionCurrent(json))
    }

    @Test
    fun returnsNullWhenCurrentIsNotString() {
        val json = """{"version":{"current":123}}"""
        assertNull(parseJournalVersionCurrent(json))
    }

    @Test
    fun rejectsControlCharactersAndEscapeSequences() {
        assertNull(sanitizeJournalVersion("1.2.3\r\n"))
        assertNull(sanitizeJournalVersion("1.2.3\t"))
        assertNull(sanitizeJournalVersion("1.2.3\u001B[31m"))
        assertNull(sanitizeJournalVersion("1.2.3\u0000"))
        assertNull(sanitizeJournalVersion("1.2.3\u007F"))
    }

    @Test
    fun returnsNullWhenSanitizedStringIsBlank() {
        val raw = "   "
        assertNull(sanitizeJournalVersion(raw))
    }

    @Test
    fun fetchJournalVersionReturnsParsedVersionOn200() {
        val fakeClient = object : PlHttpClient {
            override fun request(
                method: String,
                path: String,
                headers: Map<String, String>,
                body: ByteArray?,
            ): HttpResponse {
                assertEquals("GET", method)
                assertEquals("/api/system/status", path)
                assertEquals(mapOf("Cache-Control" to "no-cache"), headers)
                return HttpResponse(200, emptyMap(), """{"version":{"current":"2.0.0"}}""".toByteArray())
            }
        }

        assertEquals("2.0.0", fetchJournalVersion(fakeClient))
    }

    @Test
    fun fetchJournalVersionReturnsNullOnNon200() {
        val fakeClient = object : PlHttpClient {
            override fun request(
                method: String,
                path: String,
                headers: Map<String, String>,
                body: ByteArray?,
            ): HttpResponse = HttpResponse(500, emptyMap(), "server error".toByteArray())
        }

        assertNull(fetchJournalVersion(fakeClient))
    }

    @Test
    fun fetchJournalVersionReturnsNullOnException() {
        val fakeClient = object : PlHttpClient {
            override fun request(
                method: String,
                path: String,
                headers: Map<String, String>,
                body: ByteArray?,
            ): HttpResponse = throw RuntimeException("connection reset")
        }

        assertNull(fetchJournalVersion(fakeClient))
    }
}
