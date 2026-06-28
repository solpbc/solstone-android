// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.work

import app.solstone.core.pl.BeaconState
import app.solstone.core.pl.HttpResponse
import app.solstone.core.pl.PlHttpClient
import app.solstone.core.pl.parseJson
import app.solstone.platform.persistence.room.SyncStateRow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BeaconDecisionsTest {
    @Test
    fun nextRecentErrorCountResetsIncrementsAndClamps() {
        assertEquals(0, nextRecentErrorCount(7, SyncOutcome.SUCCESS))
        assertEquals(8, nextRecentErrorCount(7, SyncOutcome.RETRY))
        assertEquals(8, nextRecentErrorCount(7, SyncOutcome.FAILURE))
        assertEquals(99, nextRecentErrorCount(99, SyncOutcome.RETRY))
    }

    @Test
    fun redactErrorReasonNormalizesAndBoundsReason() {
        assertNull(redactErrorReason(null))
        assertNull(redactErrorReason(" \n\t "))
        assertEquals("retry after control chars", redactErrorReason("retry\nafter\t\u0001  control   chars"))
        assertEquals("safe reason", redactErrorReason("safe reason"))
        assertEquals(200, redactErrorReason("x".repeat(250))!!.length)
    }

    @Test
    fun advanceLastSuccessOnlyAdvancesSuccessfulDrain() {
        assertEquals(2000L, advanceLastSuccess(prior = 1000, drainSucceeded = true, now = 2000))
        assertEquals(1000L, advanceLastSuccess(prior = 1000, drainSucceeded = false, now = 2000))
        assertEquals(2000L, advanceLastSuccess(prior = null, drainSucceeded = true, now = 2000))
    }

    @Test
    fun buildObserverHealthComputesAndBoundsFields() {
        val health = buildObserverHealth(
            name = "observer-handle",
            streamType = "glasses",
            version = "0.1",
            startedAt = 1000,
            now = 6500,
            lastSuccessAt = null,
            pendingCount = 4,
            recentErrorCount = 150,
            rawErrorReason = "retry\n500",
        )

        assertEquals("observer-handle", health.name)
        assertEquals("glasses", health.streamType)
        assertEquals("0.1", health.version)
        assertEquals(5L, health.uptime)
        assertNull(health.lastSuccessfulSync)
        assertEquals(4, health.pendingQueueDepth)
        assertEquals(99, health.recentErrorCount)
        assertEquals("retry 500", health.lastErrorReason)
    }

    @Test
    fun emitObserverHealthDeliversAndPersistsNextState() {
        val client = RecordingPlHttpClient(HttpResponse(200, emptyMap(), ByteArray(0)))
        var persisted: BeaconState? = null

        val result = emitObserverHealth(
            client = client,
            priorState = BeaconState(startedAt = 1000, recentErrorCount = 1),
            persist = { persisted = it },
            streamType = "phone",
            handle = "observer-handle",
            version = "0.1",
            now = 6500,
            syncRow = SyncStateRow(pendingCount = 7, lastSuccessAt = 2000, lastFailureAt = null),
            outcome = SyncOutcome.RETRY,
            rawErrorReason = "retry (500)",
        )

        assertEquals(BeaconEmitResult.DELIVERED, result)
        assertEquals(BeaconState(startedAt = 1000, recentErrorCount = 2), persisted)
        val body = parseJson(client.lastRequest().body!!.toString(Charsets.UTF_8)) as Map<*, *>
        assertEquals(
            setOf(
                "name",
                "stream_type",
                "version",
                "uptime",
                "last_successful_sync",
                "pending_queue_depth",
                "recent_error_count",
                "last_error_reason",
            ),
            body.keys,
        )
        assertEquals("phone", body["stream_type"])
        assertEquals(5.0, body["uptime"])
        assertEquals(2000.0, body["last_successful_sync"])
        assertEquals(7.0, body["pending_queue_depth"])
        assertEquals(2.0, body["recent_error_count"])
        assertEquals("retry (500)", body["last_error_reason"])
    }

    @Test
    fun emitObserverHealthInitializesStartedAtWhenStateIsAbsent() {
        val client = RecordingPlHttpClient(HttpResponse(200, emptyMap(), ByteArray(0)))
        var persisted: BeaconState? = null

        val result = emitObserverHealth(
            client = client,
            priorState = null,
            persist = { persisted = it },
            streamType = "watch",
            handle = "observer-handle",
            version = "0.1",
            now = 5000,
            syncRow = null,
            outcome = SyncOutcome.SUCCESS,
            rawErrorReason = null,
        )

        assertEquals(BeaconEmitResult.DELIVERED, result)
        assertEquals(BeaconState(startedAt = 5000, recentErrorCount = 0), persisted)
        val body = parseJson(client.lastRequest().body!!.toString(Charsets.UTF_8)) as Map<*, *>
        assertEquals(0.0, body["uptime"])
        assertEquals(0.0, body["pending_queue_depth"])
        assertEquals(null, body["last_successful_sync"])
    }

    @Test
    fun emitObserverHealthSwallowsClientFailure() {
        val result = emitObserverHealth(
            client = ThrowingPlHttpClient(),
            priorState = null,
            persist = {},
            streamType = "phone",
            handle = "observer-handle",
            version = "0.1",
            now = 5000,
            syncRow = null,
            outcome = SyncOutcome.SUCCESS,
            rawErrorReason = null,
        )

        assertEquals(BeaconEmitResult.FAILED, result)
    }

    private class RecordingPlHttpClient(private val response: HttpResponse) : PlHttpClient {
        private val requests = mutableListOf<RecordedRequest>()

        fun lastRequest(): RecordedRequest = requests.last()

        override fun request(method: String, path: String, headers: Map<String, String>, body: ByteArray?): HttpResponse {
            requests += RecordedRequest(method, path, headers, body)
            return response
        }
    }

    private class ThrowingPlHttpClient : PlHttpClient {
        override fun request(method: String, path: String, headers: Map<String, String>, body: ByteArray?): HttpResponse {
            throw java.io.IOException("network")
        }
    }

    private data class RecordedRequest(
        val method: String,
        val path: String,
        val headers: Map<String, String>,
        val body: ByteArray?,
    )

}
