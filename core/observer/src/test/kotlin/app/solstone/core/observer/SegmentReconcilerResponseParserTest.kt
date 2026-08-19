// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.observer

import app.solstone.core.pl.HttpResponse
import app.solstone.core.pl.PlHttpClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame

class SegmentReconcilerResponseParserTest {
    private val success = HttpResponse(
        200,
        emptyMap(),
        """{"items":[{"key":"seg-1","files":[{"name":"a.txt","sha256":"abc","status":"present","submitted_name":"source.txt"}]}]}"""
            .toByteArray(),
    )

    @Test
    fun validResponseParsesIdenticallyThroughFetchAndDirectEntryPoint() {
        val client = RecordingClient(success)
        val reconciler = SegmentReconciler(client, "observer")

        val direct = reconciler.parseFetchResponse(success)
        val fetched = reconciler.fetch("20260729")

        assertEquals(direct, fetched)
        assertEquals("seg-1", direct.single().key)
        assertEquals("source.txt", direct.single().files.single().submittedName)
        assertEquals("/app/devices/ingest/segments/20260729", client.path)
    }

    @Test
    fun parserErrorsRemainWrappedAsUnavailable200() {
        val reconciler = SegmentReconciler(RecordingClient(success), "observer")

        val error = assertFailsWith<ReconcileUnavailableException> {
            reconciler.parseFetchResponse(HttpResponse(200, emptyMap(), """{"items":"wrong"}""".toByteArray()))
        }

        assertEquals(200, error.status)
        assertIs<IllegalArgumentException>(error.cause)
    }

    @Test
    fun authStatusesRemainAuthErrors() {
        val reconciler = SegmentReconciler(RecordingClient(success), "observer")

        listOf(401, 403).forEach { status ->
            assertEquals(
                status,
                assertFailsWith<ReconcileAuthException> {
                    reconciler.parseFetchResponse(HttpResponse(status, emptyMap(), ByteArray(0)))
                }.status,
            )
        }
    }

    @Test
    fun allOtherStatusesRemainUnavailable() {
        val reconciler = SegmentReconciler(RecordingClient(success), "observer")
        val response = HttpResponse(503, emptyMap(), ByteArray(0))

        val direct = assertFailsWith<ReconcileUnavailableException> {
            reconciler.parseFetchResponse(response)
        }
        val client = RecordingClient(response)
        val fetched = assertFailsWith<ReconcileUnavailableException> {
            SegmentReconciler(client, "observer").fetch("20260729")
        }

        assertEquals(503, direct.status)
        assertEquals(503, fetched.status)
        assertSame(response, client.response)
    }

    private class RecordingClient(val response: HttpResponse) : PlHttpClient {
        var path: String? = null

        override fun request(
            method: String,
            path: String,
            headers: Map<String, String>,
            body: ByteArray?,
        ): HttpResponse {
            this.path = path
            return response
        }
    }
}
