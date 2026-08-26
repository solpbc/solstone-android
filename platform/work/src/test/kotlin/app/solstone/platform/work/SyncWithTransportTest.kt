// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.work

import app.solstone.core.model.BundleFile
import app.solstone.core.model.QueueState
import app.solstone.core.observer.INGEST_PATH
import app.solstone.core.observer.PROTOCOL_VERSION_HEADER
import app.solstone.core.observer.SEGMENTS_PATH
import app.solstone.core.pl.DirectEndpoint
import app.solstone.core.pl.HttpResponse
import app.solstone.platform.persistence.room.SegmentRow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SyncWithTransportTest {
    @Test
    fun pairedTransportDrainsWithoutLegacyRegistrationHealthOrObserverHandleHeader() {
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
            assertNoLegacyHeaders(trace.client.requests)
        }
    }

    @Test
    fun retryClassIngestRejectionsReturnRetryFor5xx408425And429ForDirectAndRelay() {
        listOf(503, 408, 425, 429).forEach { status ->
            transports.forEach { transport ->
                val trace = runTrace(
                    transport = transport,
                    responses = listOf(
                        statusOk(),
                        uploadRequiredSegments(),
                        HttpResponse(status, emptyMap(), "retry later".toByteArray()),
                    ),
                )

                assertEquals(SyncOutcome.RETRY, trace.outcome)
                assertEquals(QueueState.FAILED, trace.store.row("a").state)
                assertEquals(1, trace.store.row("a").attemptCount)
                assertEquals(status, trace.store.row("a").lastStatusCode)
                assertEquals("retry", trace.store.row("a").lastError)
                assertEquals(
                    listOf(
                        "GET /app/network/api/status",
                        "GET $SEGMENTS_PATH/$WORK_TEST_DAY",
                        "POST $INGEST_PATH",
                    ),
                    trace.client.requests.map { "${it.method} ${it.path}" },
                )
                assertNoLegacyHeaders(trace.client.requests)
            }
        }
    }

    @Test
    fun authIngestRejectionsHaltFor401And403ForDirectAndRelay() {
        listOf(401, 403).forEach { status ->
            transports.forEach { transport ->
                val trace = runTrace(
                    transport = transport,
                    responses = listOf(
                        statusOk(),
                        uploadRequiredSegments(),
                        HttpResponse(status, emptyMap(), "not authorized".toByteArray()),
                    ),
                )

                assertEquals(SyncOutcome.FAILURE, trace.outcome)
                assertEquals(QueueState.FAILED, trace.store.row("a").state)
                assertEquals(1, trace.store.row("a").attemptCount)
                assertEquals(status, trace.store.row("a").lastStatusCode)
                assertEquals("auth halted", trace.store.row("a").lastError)
                assertNoLegacyHeaders(trace.client.requests)
            }
        }
    }

    private fun runTrace(
        transport: SyncTransport,
        responses: List<HttpResponse>,
    ): Trace {
        val store = FakeDrainStore(
            segment("a"),
            files = mapOf("a" to listOf(file("a"))),
        )
        val client = RecordingPlHttpClient(*responses.toTypedArray())
        val openedTransports = mutableListOf<SyncTransport>()

        val outcome = syncWithTransport(
            transport = transport,
            openClient = {
                openedTransports += it
                client
            },
            store = store,
            readPayload = { _: SegmentRow, _: BundleFile -> byteArrayOf(1) },
            host = "test-device",
            now = { NOW },
            log = { _, _ -> },
        )
        return Trace(outcome, store, client, openedTransports)
    }

    private fun assertV3(request: RecordedRequest) {
        assertEquals("3", request.headers[PROTOCOL_VERSION_HEADER])
    }

    private fun assertNoLegacyHeaders(requests: List<RecordedRequest>) {
        assertFalse(requests.any { "X-Solstone-Observer" in it.headers })
        assertFalse(requests.any { "Authorization" in it.headers })
    }

    private data class Trace(
        val outcome: SyncOutcome,
        val store: FakeDrainStore,
        val client: RecordingPlHttpClient,
        val openedTransports: List<SyncTransport>,
    )

    private companion object {
        const val NOW = 1_000_000L
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
    }
}
