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
import app.solstone.core.queue.QueueEvent
import app.solstone.core.sources.MAIN_STREAM
import app.solstone.platform.persistence.room.ConfirmedCopyFinisher
import app.solstone.platform.persistence.room.EventRow
import app.solstone.platform.persistence.room.SegmentDao
import app.solstone.platform.persistence.room.SegmentFileRow
import app.solstone.platform.persistence.room.SegmentRow
import app.solstone.platform.persistence.room.SyncStateRow
import java.nio.file.Files
import java.nio.file.Path
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
                    "GET $SEGMENTS_PATH/$WORK_TEST_DAY?source=audio",
                    "POST $INGEST_PATH",
                ),
                trace.client.requests.map { "${it.method} ${it.path}" },
            )
            assertV3(trace.client.requests[1])
            assertV3(trace.client.requests[2])
            assertNoLegacyHeaders(trace.client.requests)
            assertEquals(QueueState.EVICTED, trace.store.row(trace.segmentId).state)
            assertFalse(Files.exists(trace.segmentDir))
            assertEquals(null, trace.store.row(trace.segmentId).lastError)
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
            assertEquals(QueueState.SEALED, trace.store.row(trace.segmentId).state)
            assertEquals(0, trace.store.row(trace.segmentId).attemptCount)
            assertEquals(null, trace.store.row(trace.segmentId).lastAttemptAt)
            assertTrue(trace.store.events.isEmpty())
            assertEquals(
                listOf("GET /app/network/api/status", "GET $SEGMENTS_PATH/$WORK_TEST_DAY?source=audio"),
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
                assertEquals(QueueState.FAILED, trace.store.row(trace.segmentId).state)
                assertEquals(1, trace.store.row(trace.segmentId).attemptCount)
                assertEquals(status, trace.store.row(trace.segmentId).lastStatusCode)
                assertEquals("retry", trace.store.row(trace.segmentId).lastError)
                assertEquals(
                    listOf(
                        "GET /app/network/api/status",
                        "GET $SEGMENTS_PATH/$WORK_TEST_DAY?source=audio",
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
                assertEquals(QueueState.FAILED, trace.store.row(trace.segmentId).state)
                assertEquals(1, trace.store.row(trace.segmentId).attemptCount)
                assertEquals(status, trace.store.row(trace.segmentId).lastStatusCode)
                assertEquals("auth halted", trace.store.row(trace.segmentId).lastError)
                assertNoLegacyHeaders(trace.client.requests)
            }
        }
    }

    @Test
    fun openerTrustRefusalReturnsFailureWhileAvailabilityReturnsRetry() {
        val store = FakeDrainStore(segment("a"), files = mapOf("a" to listOf(file("a"))))

        val trustOutcome = syncWithTransport(
            transport = DIRECT,
            openClient = { throw javax.net.ssl.SSLPeerUnverifiedException("bad cert") },
            store = store,
            readPayload = { _, _ -> byteArrayOf(1) },
            host = "test-device",
            now = { NOW },
            log = { _, _ -> },
            finisher = dummyFinisher(),
        )
        assertEquals(SyncOutcome.FAILURE, trustOutcome)

        val availOutcome = syncWithTransport(
            transport = DIRECT,
            openClient = { throw java.net.ConnectException("connection refused") },
            store = store,
            readPayload = { _, _ -> byteArrayOf(1) },
            host = "test-device",
            now = { NOW },
            log = { _, _ -> },
            finisher = dummyFinisher(),
        )
        assertEquals(SyncOutcome.RETRY, availOutcome)
    }

    private fun runTrace(
        transport: SyncTransport,
        responses: List<HttpResponse>,
    ): Trace {
        val root = Files.createTempDirectory("sync-trace")
        val spool = root.resolve("spool")
        val segmentId = "$WORK_TEST_DAY/$MAIN_STREAM/a"
        val segmentDir = spool.resolve(segmentId)
        Files.createDirectories(segmentDir)
        Files.write(segmentDir.resolve("a.bin"), byteArrayOf(1))
        val manifestText = """
            solstone-bundle-manifest-v1
            day=$WORK_TEST_DAY
            segment=a
            startEpochMs=1
            endEpochMs=2
            zoneId=UTC
            utcOffsetSeconds=0
            [files]
            audio	a.bin	sha-a	1	application/octet-stream	1	2
            [gaps]
        """.trimIndent()
        Files.write(segmentDir.resolve("manifest"), manifestText.toByteArray())

        val segmentRow = segment(segmentId).copy(day = WORK_TEST_DAY, stream = MAIN_STREAM, segment = "a", dirSegment = "a")
        val fileRow = file(segmentId).copy(name = "a.bin", sha256 = "sha-a")
        val store = FakeDrainStore(
            segmentRow,
            files = mapOf(segmentId to listOf(fileRow)),
        )
        val dao = object : SegmentDao() {
            override fun insertSegment(segment: SegmentRow) = Unit
            override fun insertFiles(files: List<SegmentFileRow>) = Unit
            override fun insertEvents(events: List<EventRow>) = Unit
            override fun segmentsByState(state: QueueState): List<SegmentRow> = emptyList()
            override fun segmentsForDrain(stream: String): List<SegmentRow> = store.segmentsForDrain()
            override fun segmentsByDay(day: String): List<SegmentRow> = emptyList()
            override fun segmentById(id: String): SegmentRow? = store.rowOrNull(id)
            override fun duplicateBySha256(sha256: String): List<SegmentFileRow> = emptyList()
            override fun filesBySegmentId(segmentId: String): List<SegmentFileRow> = store.filesBySegmentId(segmentId)
            override fun recordAttempt(id: String, attempts: Int, at: Long): Int = store.recordAttempt(id, attempts, at)
            override fun recordUploaded(id: String): Int = store.recordUploaded(id)
            override fun recordFailure(id: String, code: Int?, error: String?): Int = store.recordFailure(id, code, error)
            override fun upsertSyncState(row: SyncStateRow) = store.upsertSyncState(row)
            override fun syncState(): SyncStateRow? = store.syncState()
            override fun pendingCount(stream: String): Int = store.pendingCount(stream)
            override fun pendingSourceIds(stream: String): List<String> = emptyList()
            override fun segmentState(id: String): QueueState? = store.rowOrNull(id)?.state
            override fun updateState(id: String, state: QueueState): Int {
                val event = when (state) {
                    QueueState.EVICTED -> QueueEvent.FINISH
                    QueueState.UPLOADING -> QueueEvent.START_UPLOAD
                    QueueState.UPLOADED -> QueueEvent.MARK_UPLOADED
                    QueueState.FAILED -> QueueEvent.MARK_FAILED
                    QueueState.SEALED -> QueueEvent.SEAL
                    QueueState.RECORDING -> error("illegal")
                }
                store.advanceState(id, event)
                return 1
            }
            override fun deleteFilesBySegmentId(segmentId: String): Int = 0
            override fun deleteFilesBySegmentIds(segmentIds: List<String>): Int = 0
            override fun deleteFilesBySource(sourceId: String): Int = 0
        }
        val finisher = ConfirmedCopyFinisher(spoolRoot = spool, dao = dao)
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
            finisher = finisher,
        )
        return Trace(outcome, store, client, openedTransports, segmentId, segmentDir)
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
        val segmentId: String,
        val segmentDir: Path,
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
            HttpResponse(
                200,
                emptyMap(),
                """{"status":"ok","segment":"srv-a","file_descriptors":[{"submitted":"a.bin","written":"a.bin","size":1,"sha256":"sha-a","disposition":"written"}]}""".toByteArray(),
            )
    }
}
