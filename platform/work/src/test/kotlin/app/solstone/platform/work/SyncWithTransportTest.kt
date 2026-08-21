// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.work

import app.solstone.core.model.BundleFile
import app.solstone.core.model.QueueState
import app.solstone.core.observer.HEALTH_PATH
import app.solstone.core.observer.INGEST_PATH
import app.solstone.core.observer.OBSERVER_HANDLE_HEADER
import app.solstone.core.observer.PROTOCOL_VERSION_HEADER
import app.solstone.core.observer.REGISTER_PATH
import app.solstone.core.observer.SEGMENTS_PATH
import app.solstone.core.pl.BeaconState
import app.solstone.core.pl.DirectEndpoint
import app.solstone.core.pl.HttpResponse
import app.solstone.platform.persistence.room.SegmentRow
import app.solstone.platform.persistence.room.SyncStateRow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SyncWithTransportTest {
    @Test
    fun successDrainsWithoutRegistrationOrHealthForDirectAndRelay() {
        transports.forEach { transport ->
            val trace = runTrace(
                transport = transport,
                responses = listOf(statusOk(), uploadRequiredSegments(), ingestAccepted()),
            )

            assertEquals(SyncOutcome.SUCCESS, trace.outcome)
            assertEquals(listOf(transport), trace.openedTransports)
            assertEquals(
                listOf(
                    "GET /app/network/api/status",
                    "GET $SEGMENTS_PATH/$WORK_TEST_DAY",
                    "POST $INGEST_PATH",
                ),
                trace.client.requests.map { "${it.method} ${it.path}" },
            )
            assertV3(trace.client.requests[1])
            assertV3(trace.client.requests[2])
            assertNoLegacyHeaders(trace.client.requests)
            assertEquals(QueueState.UPLOADED, trace.store.row("a").state)
            assertEquals("srv-a", trace.store.row("a").serverKey)
            assertTrue(trace.client.closed)
            assertEquals(0, trace.loadBeaconCalls)
            assertEquals(0, trace.persistedBeaconStates.size)
        }
    }

    @Test
    fun reconcileUnavailableLeavesSegmentUnclaimedForDirectAndRelay() {
        transports.forEach { transport ->
            val trace = runTrace(
                transport = transport,
                responses = listOf(statusOk(), HttpResponse(503, emptyMap(), "unavailable".toByteArray())),
            )

            assertEquals(SyncOutcome.RETRY, trace.outcome)
            assertEquals(QueueState.SEALED, trace.store.row("a").state)
            assertEquals(0, trace.store.row("a").attemptCount)
            assertEquals(null, trace.store.row("a").lastAttemptAt)
            assertTrue(trace.store.events.isEmpty())
            assertEquals(
                listOf("GET /app/network/api/status", "GET $SEGMENTS_PATH/$WORK_TEST_DAY"),
                trace.client.requests.map { "${it.method} ${it.path}" },
            )
            assertFalse(trace.client.requests.any { it.path == INGEST_PATH || it.path == HEALTH_PATH })
            assertFalse(trace.client.requests.any { it.path == REGISTER_PATH })
            assertNoLegacyHeaders(trace.client.requests)
            assertEquals(0, trace.loadBeaconCalls)
            assertEquals(0, trace.persistedBeaconStates.size)
        }
    }

    @Test
    fun retryClassIngestRejectionRecordsFailureForDirectAndRelay() {
        transports.forEach { transport ->
            val trace = runTrace(
                transport = transport,
                responses = listOf(
                    statusOk(),
                    uploadRequiredSegments(),
                    HttpResponse(503, emptyMap(), "retry later".toByteArray()),
                ),
            )

            assertEquals(SyncOutcome.RETRY, trace.outcome)
            assertEquals(QueueState.FAILED, trace.store.row("a").state)
            assertEquals(1, trace.store.row("a").attemptCount)
            assertEquals(503, trace.store.row("a").lastStatusCode)
            assertEquals("retry", trace.store.row("a").lastError)
            assertEquals(
                listOf(
                    "GET /app/network/api/status",
                    "GET $SEGMENTS_PATH/$WORK_TEST_DAY",
                    "POST $INGEST_PATH",
                ),
                trace.client.requests.map { "${it.method} ${it.path}" },
            )
            assertFalse(trace.client.requests.any { it.path == HEALTH_PATH })
            assertFalse(trace.client.requests.any { it.path == REGISTER_PATH })
            assertNoLegacyHeaders(trace.client.requests)
            assertEquals(0, trace.loadBeaconCalls)
            assertEquals(0, trace.persistedBeaconStates.size)
        }
    }

    @Test
    fun legacyHandleEmitsHealthAfterSuccessfulDrainForDirectAndRelay() {
        transports.forEach { transport ->
            val trace = runTrace(
                transport = transport,
                responses = listOf(statusOk(), uploadRequiredSegments(), ingestAccepted(), healthOk()),
                existingHandle = HANDLE,
            )

            assertEquals(SyncOutcome.SUCCESS, trace.outcome)
            assertLegacyHealthAfterIngest(trace)
            assertEquals(1, trace.loadBeaconCalls)
            assertEquals(listOf(BeaconState(NOW, 0)), trace.persistedBeaconStates)
            assertEquals(0, trace.store.syncState()!!.pendingCount)
            assertEquals(NOW, trace.store.syncState()!!.lastSuccessAt)
        }
    }

    @Test
    fun legacyHandleEmitsHealthAfterRetryClassIngestFailure() {
        val trace = runTrace(
            transport = DIRECT,
            responses = listOf(
                statusOk(),
                uploadRequiredSegments(),
                HttpResponse(503, emptyMap(), "retry later".toByteArray()),
                healthOk(),
            ),
            existingHandle = HANDLE,
            initialSyncState = SyncStateRow(pendingCount = 1, lastSuccessAt = 123L, lastFailureAt = null),
        )

        assertEquals(SyncOutcome.RETRY, trace.outcome)
        assertLegacyHealthAfterIngest(trace)
        assertEquals(1, trace.loadBeaconCalls)
        assertEquals(listOf(BeaconState(NOW, 1)), trace.persistedBeaconStates)
        assertEquals(1, trace.store.syncState()!!.pendingCount)
        assertEquals(123L, trace.store.syncState()!!.lastSuccessAt)
        assertEquals(NOW, trace.store.syncState()!!.lastFailureAt)
    }

    @Test
    fun absentHandleNeverEmitsHealthAfterSuccessOrRetry() {
        listOf(
            listOf(statusOk(), uploadRequiredSegments(), ingestAccepted()) to SyncOutcome.SUCCESS,
            listOf(
                statusOk(),
                uploadRequiredSegments(),
                HttpResponse(503, emptyMap(), "retry later".toByteArray()),
            ) to SyncOutcome.RETRY,
        ).forEach { (responses, expectedOutcome) ->
            val trace = runTrace(transport = DIRECT, responses = responses)

            assertEquals(expectedOutcome, trace.outcome)
            assertFalse(trace.client.requests.any { it.path == HEALTH_PATH })
            assertFalse(trace.client.requests.any { it.path == REGISTER_PATH })
            assertEquals(0, trace.loadBeaconCalls)
            assertEquals(0, trace.persistedBeaconStates.size)
        }
    }

    private fun runTrace(
        transport: SyncTransport,
        responses: List<HttpResponse>,
        existingHandle: String? = null,
        initialSyncState: SyncStateRow? = null,
    ): Trace {
        val store = FakeDrainStore(
            segment("a"),
            files = mapOf("a" to listOf(file("a"))),
            syncState = initialSyncState,
        )
        val client = RecordingPlHttpClient(*responses.toTypedArray())
        val openedTransports = mutableListOf<SyncTransport>()
        val persistedBeaconStates = mutableListOf<BeaconState>()
        var loadBeaconCalls = 0
        val logs = mutableListOf<String>()

        val outcome = syncWithTransport(
            transport = transport,
            openClient = {
                openedTransports += it
                client
            },
            store = store,
            readPayload = { _: SegmentRow, _: BundleFile -> byteArrayOf(1) },
            existingHandle = existingHandle,
            loadBeaconState = {
                loadBeaconCalls += 1
                null
            },
            persistBeaconState = { persistedBeaconStates += it },
            host = "test-device",
            version = "0.1",
            streamType = "main",
            now = { NOW },
            log = { message, _ -> logs += message },
        )
        return Trace(outcome, store, client, openedTransports, loadBeaconCalls, persistedBeaconStates, logs)
    }

    private fun assertLegacyHealthAfterIngest(trace: Trace) {
        assertEquals(
            listOf(
                "GET /app/network/api/status",
                "GET $SEGMENTS_PATH/$WORK_TEST_DAY",
                "POST $INGEST_PATH",
                "POST $HEALTH_PATH",
            ),
            trace.client.requests.map { "${it.method} ${it.path}" },
        )
        val health = trace.client.requests.last()
        assertEquals(HANDLE, health.headers[OBSERVER_HANDLE_HEADER])
        assertEquals("2", health.headers[PROTOCOL_VERSION_HEADER])
        assertEquals(1, trace.client.requests.count { it.headers[PROTOCOL_VERSION_HEADER] == "2" })
        assertV3(trace.client.requests[1])
        assertV3(trace.client.requests[2])
        assertFalse(trace.client.requests.any { it.path == REGISTER_PATH })
        assertTrue(trace.client.closed)
    }

    private fun assertV3(request: RecordedRequest) {
        assertEquals("3", request.headers[PROTOCOL_VERSION_HEADER])
    }

    private fun assertNoLegacyHeaders(requests: List<RecordedRequest>) {
        assertFalse(requests.any { OBSERVER_HANDLE_HEADER in it.headers })
        assertFalse(requests.any { "Authorization" in it.headers })
        assertFalse(requests.any { it.path == REGISTER_PATH })
    }

    private data class Trace(
        val outcome: SyncOutcome,
        val store: FakeDrainStore,
        val client: RecordingPlHttpClient,
        val openedTransports: List<SyncTransport>,
        val loadBeaconCalls: Int,
        val persistedBeaconStates: List<BeaconState>,
        val logs: List<String>,
    )

    private companion object {
        const val NOW = 1_000_000L
        const val HANDLE = "stored-handle"
        val DIRECT = SyncTransport.Direct(DirectEndpoint("192.0.2.10", 7657))
        val RELAY = SyncTransport.Relay("https://link.solstone.app", "home", "device-token")
        val transports = listOf(DIRECT, RELAY)

        fun statusOk(): HttpResponse = HttpResponse(200, emptyMap(), ByteArray(0))

        fun uploadRequiredSegments(): HttpResponse = HttpResponse(
            200,
            emptyMap(),
            """{"items":[{"key":"remote","observed":true,"files":[]}],"total":1,"protocol_version":3}""".toByteArray(),
        )

        fun ingestAccepted(): HttpResponse =
            HttpResponse(200, emptyMap(), """{"status":"ok","segment":"srv-a"}""".toByteArray())

        fun healthOk(): HttpResponse = HttpResponse(200, emptyMap(), ByteArray(0))
    }
}
