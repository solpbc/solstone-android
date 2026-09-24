// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.work

import app.solstone.core.model.BundleFile
import app.solstone.core.model.BundleManifest
import app.solstone.core.model.QueueState
import app.solstone.core.model.SegmentKey
import app.solstone.core.model.WireKeys
import app.solstone.core.observer.IngestDescriptors
import app.solstone.core.observer.IngestFileDescriptor
import app.solstone.core.observer.IngestOutcome
import app.solstone.core.observer.ReconcileAuthException
import app.solstone.core.observer.ReconcileUnavailableException
import app.solstone.core.observer.ReconcileVerdict
import app.solstone.core.observer.SegmentReconciler
import app.solstone.core.pl.HttpResponse
import app.solstone.core.pl.PlHttpClient
import app.solstone.core.queue.QueueEvent
import app.solstone.core.sources.MAIN_STREAM
import app.solstone.platform.persistence.room.ConfirmedCopyFinisher
import app.solstone.platform.persistence.room.EventRow
import app.solstone.platform.persistence.room.SegmentDao
import app.solstone.platform.persistence.room.SegmentFileRow
import app.solstone.platform.persistence.room.SegmentRow
import app.solstone.platform.persistence.room.SyncStateRow
import java.io.FileNotFoundException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SegmentDrainerTest {
    @Test
    fun drainsSealedUploadAndPersistsCleanSuccess() {
        val fixture = TestFixture()
        val (seg, dir) = fixture.createSegment("a")

        val report = drainSegments(
            store = fixture.store,
            reconcile = uploadAll,
            ingest = acceptedIngest("srv-a"),
            readPayload = readBytes,
            now = { NOW },
            log = fixture.store::log,
            finisher = fixture.finisher,
        )

        assertEquals(SyncOutcome.SUCCESS, report.workOutcome)
        assertTrue(report.cleanDrain)
        assertFalse(report.failedThisRun)
        assertEquals(QueueState.EVICTED, fixture.store.row(seg.id).state)
        assertFalse(Files.exists(dir))
        assertNull(fixture.store.row(seg.id).lastError)
        assertEquals(1, fixture.store.row(seg.id).attemptCount)
        assertEquals(0, fixture.store.syncState!!.pendingCount)
        assertEquals(NOW, fixture.store.syncState!!.lastSuccessAt)
    }

    @Test
    fun processedVerdictSkipsUploadAndMarksUploaded() {
        val fixture = TestFixture()
        val (seg, dir) = fixture.createSegment(
            "a",
            files = listOf(BundleFile("audio", "a.bin", "a".repeat(64), 3, "application/octet-stream", 1, 2)),
        )
        val fakeHttp = object : PlHttpClient {
            override fun request(
                method: String,
                path: String,
                headers: Map<String, String>,
                body: ByteArray?,
                maxResponseBytes: Int,
            ): HttpResponse = HttpResponse(
                200,
                emptyMap(),
                """{"items":[{"key":"a","files":[{"name":"a.bin","size":3,"sha256":"${"a".repeat(64)}","status":"processed"}]}],"total":1,"protocol_version":3}"""
                    .toByteArray(),
            )
        }
        var ingestCount = 0

        drainSegments(
            store = fixture.store,
            reconcile = { manifests, day -> SegmentReconciler(fakeHttp).diff(manifests, day) },
            ingest = { _, _ ->
                ingestCount += 1
                error("ingest must not be called for a held segment")
            },
            readPayload = readBytes,
            now = { NOW },
            log = fixture.store::log,
            finisher = fixture.finisher,
        )

        assertEquals(0, ingestCount)
        assertEquals(QueueState.EVICTED, fixture.store.row(seg.id).state)
        assertFalse(Files.exists(dir))
    }

    @Test
    fun recoversUploadingViaMarkFailedThenRetryThenUpload() {
        val fixture = TestFixture()
        val (seg, dir) = fixture.createSegment("a", state = QueueState.UPLOADING)

        drainSegments(
            store = fixture.store,
            reconcile = uploadAll,
            ingest = acceptedIngest("srv-a"),
            readPayload = readBytes,
            now = { NOW },
            log = fixture.store::log,
            finisher = fixture.finisher,
        )

        assertEquals(
            listOf(QueueEvent.MARK_FAILED, QueueEvent.RETRY, QueueEvent.MARK_UPLOADED, QueueEvent.FINISH),
            fixture.store.eventsFor(seg.id),
        )
        assertEquals(QueueState.EVICTED, fixture.store.row(seg.id).state)
        assertFalse(Files.exists(dir))
        assertNull(fixture.store.row(seg.id).lastError)
        assertEquals(1, fixture.store.row(seg.id).attemptCount)
    }

    @Test
    fun allHardFailuresReturnRetryNoCleanNoStamp() {
        val store = FakeDrainStore(
            segment("a", sealedAt = 1),
            segment("b", sealedAt = 2),
            files = mapOf("a" to listOf(file("a")), "b" to listOf(file("b"))),
            syncState = SyncStateRow(pendingCount = 2, lastSuccessAt = 123, lastFailureAt = null),
        )

        val report = drainSegments(
            store = store,
            reconcile = uploadAll,
            ingest = rejectedIngest(422),
            readPayload = readBytes,
            now = { NOW },
            log = store::log,
            finisher = dummyFinisher(),
        )

        assertEquals(SyncOutcome.RETRY, report.workOutcome)
        assertFalse(report.cleanDrain)
        assertTrue(report.failedThisRun)
        assertEquals(123, store.syncState!!.lastSuccessAt)
        assertEquals(QueueState.FAILED, store.row("a").state)
        assertEquals(QueueState.FAILED, store.row("b").state)
    }

    @Test
    fun typedProtocolFailuresRetainPayloadBytes() {
        listOf<List<IngestOutcome>>(
            listOf(IngestOutcome.Failed(null)),
            listOf(IngestOutcome.UnknownStatus("future")),
            listOf(IngestOutcome.MalformedResponse("invalid_json")),
        ).forEach { outcome ->
            val store = FakeDrainStore(segment("a"), files = mapOf("a" to listOf(file("a"))))
            val payload = byteArrayOf(1, 2, 3)
            var ingested: ByteArray? = null

            drainSegments(
                store = store,
                reconcile = uploadAll,
                ingest = { manifest, fileBytes ->
                    ingested = fileBytes(manifest.files.single())
                    outcome
                },
                readPayload = { _, _ -> payload },
                now = { NOW },
                log = store::log,
                finisher = dummyFinisher(),
            )

            assertContentEquals(byteArrayOf(1, 2, 3), requireNotNull(ingested))
            assertContentEquals(byteArrayOf(1, 2, 3), payload)
            assertEquals(QueueState.FAILED, store.row("a").state)
        }
    }

    @Test
    fun emptyQueueReturnsSuccessAndClean() {
        val store = FakeDrainStore(syncState = SyncStateRow(pendingCount = 0, lastSuccessAt = null, lastFailureAt = null))

        val report = drainSegments(
            store = store,
            reconcile = uploadAll,
            ingest = acceptedIngest("unused"),
            readPayload = readBytes,
            now = { NOW },
            log = store::log,
            finisher = dummyFinisher(),
        )

        assertEquals(SyncOutcome.SUCCESS, report.workOutcome)
        assertTrue(report.cleanDrain)
        assertFalse(report.failedThisRun)
        assertEquals(NOW, store.syncState!!.lastSuccessAt)
    }

    @Test
    fun idleBackfillInBackoffReturnsSuccessButNotClean() {
        val store = FakeDrainStore(
            segment(
                "failed",
                QueueState.FAILED,
                attemptCount = 1,
                lastAttemptAt = NOW - 1_000,
                lastStatusCode = 500,
            ),
            files = mapOf("failed" to listOf(file("failed"))),
            syncState = SyncStateRow(pendingCount = 1, lastSuccessAt = 100, lastFailureAt = 50),
        )

        val report = drainSegments(
            store = store,
            reconcile = uploadAll,
            ingest = acceptedIngest("unused"),
            readPayload = readBytes,
            now = { NOW },
            log = store::log,
            finisher = dummyFinisher(),
        )

        assertEquals(SyncOutcome.SUCCESS, report.workOutcome)
        assertFalse(report.cleanDrain)
        assertFalse(report.failedThisRun)
        assertEquals(100, store.syncState!!.lastSuccessAt)
        assertEquals(1, store.syncState!!.pendingCount)
        assertTrue(store.events.isEmpty())
    }

    @Test
    fun payloadMissingMarksSegmentFailedAndContinues() {
        val fixture = TestFixture()
        val (missing, _) = fixture.createSegment("missing", sealedAt = 1, writeDisk = false)
        val (ok, okDir) = fixture.createSegment("ok", sealedAt = 2, writeDisk = true)

        val report = drainSegments(
            store = fixture.store,
            reconcile = uploadAll,
            ingest = acceptedIngest("srv"),
            readPayload = { segment, _ ->
                if (segment.id == missing.id) throw FileNotFoundException("missing")
                byteArrayOf(1)
            },
            now = { NOW },
            log = fixture.store::log,
            finisher = fixture.finisher,
        )

        assertEquals(SyncOutcome.RETRY, report.workOutcome)
        assertTrue(report.failedThisRun)
        assertEquals(QueueState.FAILED, fixture.store.row(missing.id).state)
        assertEquals("payload missing", fixture.store.row(missing.id).lastError)
        assertEquals(QueueState.EVICTED, fixture.store.row(ok.id).state)
        assertFalse(Files.exists(okDir))
        assertTrue(fixture.store.logs.any { it.contains("payload missing ${missing.id}") })
    }

    @Test
    fun payloadPathViolationMarksFailedAndContinues() {
        val fixture = TestFixture()
        val (bad, _) = fixture.createSegment("bad", sealedAt = 1, writeDisk = false)
        val (ok, okDir) = fixture.createSegment("ok", sealedAt = 2, writeDisk = true)

        val report = drainSegments(
            store = fixture.store,
            reconcile = uploadAll,
            ingest = acceptedIngest("srv"),
            readPayload = { segment, _ ->
                if (segment.id == bad.id) throw IllegalArgumentException("bad path")
                byteArrayOf(1)
            },
            now = { NOW },
            log = fixture.store::log,
            finisher = fixture.finisher,
        )

        assertEquals(SyncOutcome.RETRY, report.workOutcome)
        assertTrue(report.failedThisRun)
        assertEquals(QueueState.FAILED, fixture.store.row(bad.id).state)
        assertEquals("payload unreadable", fixture.store.row(bad.id).lastError)
        assertEquals(QueueState.EVICTED, fixture.store.row(ok.id).state)
        assertFalse(Files.exists(okDir))
        assertTrue(fixture.store.logs.any { it.contains("payload unreadable ${bad.id}") })
    }

    @Test
    fun reconcileUnavailableLeavesDayDrainableAndRetries() {
        val store = FakeDrainStore(segment("a"), files = mapOf("a" to listOf(file("a"))))

        val report = drainSegments(
            store = store,
            reconcile = { _, _ -> throw ReconcileUnavailableException(500) },
            ingest = acceptedIngest("unused"),
            readPayload = readBytes,
            now = { NOW },
            log = store::log,
            finisher = dummyFinisher(),
        )

        assertEquals(SyncOutcome.RETRY, report.workOutcome)
        assertTrue(report.failedThisRun)
        assertEquals(QueueState.SEALED, store.row("a").state)
        assertTrue(store.events.isEmpty())
        assertTrue(store.logs.any { it.contains("reconcile unavailable day=$DAY") })
    }

    @Test
    fun reconcileAuthHaltsWithFailure() {
        val store = FakeDrainStore(segment("a"), files = mapOf("a" to listOf(file("a"))))

        val report = drainSegments(
            store = store,
            reconcile = { _, _ -> throw ReconcileAuthException(401) },
            ingest = acceptedIngest("unused"),
            readPayload = readBytes,
            now = { NOW },
            log = store::log,
            finisher = dummyFinisher(),
        )

        assertEquals(SyncOutcome.FAILURE, report.workOutcome)
        assertFalse(report.cleanDrain)
        assertTrue(report.failedThisRun)
        assertEquals(QueueState.SEALED, store.row("a").state)
        assertTrue(store.logs.any { it.contains("reconcile auth halt day=$DAY") })
    }

    @Test
    fun capLimitsAttemptsAndLeavesRemainderDueRetrying() {
        val fixture = TestFixture()
        val segments = (0 until 51).map { index ->
            fixture.createSegment("seg-$index", sealedAt = index.toLong()).first
        }
        var ingestCount = 0

        val report = drainSegments(
            store = fixture.store,
            reconcile = uploadAll,
            ingest = { manifest, fileBytes ->
                ingestCount += 1
                manifest.files.forEach { fileBytes(it) }
                listOf(
                    IngestOutcome.Accepted(
                        "srv-${manifest.key.segment}",
                        IngestDescriptors.Listed(
                            manifest.files.map {
                                IngestFileDescriptor(
                                    submitted = it.name,
                                    written = it.name,
                                    size = it.byteSize,
                                    sha256 = it.sha256,
                                    disposition = "written",
                                )
                            },
                        ),
                    ),
                )
            },
            readPayload = readBytes,
            now = { NOW },
            log = fixture.store::log,
            finisher = fixture.finisher,
        )

        assertEquals(50, ingestCount)
        assertEquals(SyncOutcome.RETRY, report.workOutcome)
        assertFalse(report.cleanDrain)
        assertFalse(report.failedThisRun)
        assertEquals(1, fixture.store.syncState!!.pendingCount)
    }

    @Test
    fun logsClaimPayloadAndReconcileCatches() {
        val claimStore = FakeDrainStore(segment("claim"), files = mapOf("claim" to listOf(file("claim"))))
        claimStore.failAdvanceFor += "claim"
        drainSegments(claimStore, uploadAll, acceptedIngest("unused"), readBytes, { NOW }, claimStore::log, dummyFinisher())

        val payloadStore = FakeDrainStore(segment("payload"), files = mapOf("payload" to listOf(file("payload"))))
        drainSegments(
            payloadStore,
            uploadAll,
            acceptedIngest("unused"),
            { _, _ -> throw FileNotFoundException("missing") },
            { NOW },
            payloadStore::log,
            dummyFinisher(),
        )

        val reconcileStore = FakeDrainStore(segment("reconcile"), files = mapOf("reconcile" to listOf(file("reconcile"))))
        drainSegments(
            reconcileStore,
            { _, _ -> throw ReconcileUnavailableException(500) },
            acceptedIngest("unused"),
            readBytes,
            { NOW },
            reconcileStore::log,
            dummyFinisher(),
        )

        assertTrue(claimStore.logs.any { it.contains("claim failed claim") })
        assertTrue(payloadStore.logs.any { it.contains("payload missing payload") })
        assertTrue(reconcileStore.logs.any { it.contains("reconcile unavailable day=$DAY") })
    }

    @Test
    fun multiSourceFirstInvalidSecondValidFailsSegment() {
        val audioFile = file("a").copy(name = "audio.bin", sourceId = "audio", sha256 = "sha-audio")
        val videoFile = file("a").copy(name = "video.bin", sourceId = "video", sha256 = "sha-video")
        val store = FakeDrainStore(segment("a"), files = mapOf("a" to listOf(audioFile, videoFile)))

        var postedSources = 0
        drainSegments(
            store = store,
            reconcile = uploadAll,
            ingest = { manifest, fileBytes ->
                manifest.files.groupBy(BundleFile::sourceId).map { (sourceId, files) ->
                    postedSources++
                    files.forEach { fileBytes(it) }
                    if (sourceId == "audio") {
                        IngestOutcome.Accepted("srv-a", IngestDescriptors.Absent)
                    } else {
                        IngestOutcome.Accepted(
                            "srv-a",
                            IngestDescriptors.Listed(
                                files.map { IngestFileDescriptor(it.name, it.name, it.byteSize, it.sha256, "written") },
                            ),
                        )
                    }
                }
            },
            readPayload = readBytes,
            now = { NOW },
            log = store::log,
            finisher = dummyFinisher(),
        )

        assertEquals(2, postedSources)
        assertEquals(QueueState.FAILED, store.row("a").state)
        assertEquals(408, store.row("a").lastStatusCode)
        assertEquals("custody_missing", store.row("a").lastError)
    }

    @Test
    fun multiSourceFirstValidSecondInvalidFailsSegment() {
        val audioFile = file("a").copy(name = "audio.bin", sourceId = "audio", sha256 = "sha-audio")
        val videoFile = file("a").copy(name = "video.bin", sourceId = "video", sha256 = "sha-video")
        val store = FakeDrainStore(segment("a"), files = mapOf("a" to listOf(audioFile, videoFile)))

        var postedSources = 0
        drainSegments(
            store = store,
            reconcile = uploadAll,
            ingest = { manifest, fileBytes ->
                manifest.files.groupBy(BundleFile::sourceId).map { (sourceId, files) ->
                    postedSources++
                    files.forEach { fileBytes(it) }
                    if (sourceId == "audio") {
                        IngestOutcome.Accepted(
                            "srv-a",
                            IngestDescriptors.Listed(
                                files.map { IngestFileDescriptor(it.name, it.name, it.byteSize, it.sha256, "written") },
                            ),
                        )
                    } else {
                        IngestOutcome.Accepted(
                            "srv-a",
                            IngestDescriptors.Listed(
                                files.map { IngestFileDescriptor(it.name, it.name, it.byteSize, it.sha256, "received_not_written") },
                            ),
                        )
                    }
                }
            },
            readPayload = readBytes,
            now = { NOW },
            log = store::log,
            finisher = dummyFinisher(),
        )

        assertEquals(2, postedSources)
        assertEquals(QueueState.FAILED, store.row("a").state)
        assertEquals(422, store.row("a").lastStatusCode)
        assertEquals("custody_not_written", store.row("a").lastError)
    }

    @Test
    fun multiSource3SourcesAllMustSucceed() {
        val fixture = TestFixture()
        val audio = BundleFile("audio", "audio.bin", "sha-audio", 1, "audio/mp4", 1, 2)
        val video = BundleFile("video", "video.bin", "sha-video", 1, "video/mp4", 1, 2)
        val motion = BundleFile("motion", "motion.bin", "sha-motion", 1, "application/octet-stream", 1, 2)
        val (seg, dir) = fixture.createSegment("a", files = listOf(audio, video, motion))

        val report = drainSegments(
            store = fixture.store,
            reconcile = uploadAll,
            ingest = acceptedIngest("srv-a"),
            readPayload = readBytes,
            now = { NOW },
            log = fixture.store::log,
            finisher = fixture.finisher,
        )

        assertEquals(SyncOutcome.SUCCESS, report.workOutcome)
        assertEquals(QueueState.EVICTED, fixture.store.row(seg.id).state)
        assertFalse(Files.exists(dir))
        assertNull(fixture.store.row(seg.id).lastError)
    }

    @Test
    fun multiSourceFirstValidSecond503ReturnsRetry() {
        val audio = file("a").copy(name = "audio.bin", sourceId = "audio", sha256 = "sha-audio")
        val video = file("a").copy(name = "video.bin", sourceId = "video", sha256 = "sha-video")
        val store = FakeDrainStore(segment("a"), files = mapOf("a" to listOf(audio, video)))

        val report = drainSegments(
            store = store,
            reconcile = uploadAll,
            ingest = { manifest, fileBytes ->
                manifest.files.groupBy(BundleFile::sourceId).map { (sourceId, files) ->
                    files.forEach { fileBytes(it) }
                    if (sourceId == "audio") {
                        IngestOutcome.Accepted(
                            "srv-a",
                            IngestDescriptors.Listed(
                                files.map { IngestFileDescriptor(it.name, it.name, it.byteSize, it.sha256, "written") },
                            ),
                        )
                    } else {
                        IngestOutcome.Rejected(503, "temporary_unavailable")
                    }
                }
            },
            readPayload = readBytes,
            now = { NOW },
            log = store::log,
            finisher = dummyFinisher(),
        )

        assertEquals(SyncOutcome.RETRY, report.workOutcome)
        assertEquals(QueueState.FAILED, store.row("a").state)
        assertEquals(503, store.row("a").lastStatusCode)
        assertEquals("retry", store.row("a").lastError)
    }

    @Test
    fun multiSourceHaltSeverityOverHardFail() {
        val audio = file("a").copy(name = "audio.bin", sourceId = "audio", sha256 = "sha-audio")
        val video = file("a").copy(name = "video.bin", sourceId = "video", sha256 = "sha-video")
        val store = FakeDrainStore(segment("a"), files = mapOf("a" to listOf(audio, video)))

        val report = drainSegments(
            store = store,
            reconcile = uploadAll,
            ingest = { _, _ ->
                listOf(
                    IngestOutcome.Rejected(401, "unauthorized"),
                )
            },
            readPayload = readBytes,
            now = { NOW },
            log = store::log,
            finisher = dummyFinisher(),
        )

        assertEquals(SyncOutcome.FAILURE, report.workOutcome)
        assertEquals(QueueState.FAILED, store.row("a").state)
        assertEquals(401, store.row("a").lastStatusCode)
        assertEquals("auth halted", store.row("a").lastError)
    }

    @Test
    fun multiSourceHardFailSeverityOverRetry() {
        val audio = file("a").copy(name = "audio.bin", sourceId = "audio", sha256 = "sha-audio")
        val video = file("a").copy(name = "video.bin", sourceId = "video", sha256 = "sha-video")
        val store = FakeDrainStore(segment("a"), files = mapOf("a" to listOf(audio, video)))

        drainSegments(
            store = store,
            reconcile = uploadAll,
            ingest = { manifest, fileBytes ->
                manifest.files.groupBy(BundleFile::sourceId).map { (sourceId, files) ->
                    files.forEach { fileBytes(it) }
                    if (sourceId == "audio") {
                        IngestOutcome.Accepted(
                            "srv-a",
                            IngestDescriptors.Listed(
                                files.map { IngestFileDescriptor(it.name, it.name, it.byteSize, it.sha256, "received_not_written") },
                            ),
                        )
                    } else {
                        IngestOutcome.Rejected(503, "retry")
                    }
                }
            },
            readPayload = readBytes,
            now = { NOW },
            log = store::log,
            finisher = dummyFinisher(),
        )

        assertEquals(QueueState.FAILED, store.row("a").state)
        assertEquals(422, store.row("a").lastStatusCode)
        assertEquals("custody_not_written", store.row("a").lastError)
    }

    @Test
    fun drainPersistsCustodyNotWrittenBackoff422() {
        val store = FakeDrainStore(segment("a"), files = mapOf("a" to listOf(file("a"))))

        drainSegments(
            store = store,
            reconcile = uploadAll,
            ingest = { manifest, fileBytes ->
                manifest.files.forEach { fileBytes(it) }
                listOf(
                    IngestOutcome.Accepted(
                        "srv-a",
                        IngestDescriptors.Listed(
                            listOf(IngestFileDescriptor("a.bin", "a.bin", 1L, "sha-a", "received_not_written")),
                        ),
                    ),
                )
            },
            readPayload = readBytes,
            now = { NOW },
            log = store::log,
            finisher = dummyFinisher(),
        )

        val row = store.row("a")
        assertEquals(QueueState.FAILED, row.state)
        assertEquals(422, row.lastStatusCode)
        assertEquals("custody_not_written", row.lastError)
        assertEquals(1, row.attemptCount)

        val notDue = selectDrainSegments(store.segmentsForDrain(), NOW + 60 * 60_000L)
        assertTrue(notDue.isEmpty())

        val due = selectDrainSegments(store.segmentsForDrain(), NOW + 120 * 60_000L)
        assertEquals(listOf("a"), due.map { it.id })
    }

    @Test
    fun drainPersistsCustodyMismatchBackoff429() {
        val store = FakeDrainStore(segment("a"), files = mapOf("a" to listOf(file("a"))))

        drainSegments(
            store = store,
            reconcile = uploadAll,
            ingest = { manifest, fileBytes ->
                manifest.files.forEach { fileBytes(it) }
                listOf(
                    IngestOutcome.Accepted(
                        "srv-a",
                        IngestDescriptors.Listed(
                            listOf(IngestFileDescriptor("a.bin", "a.bin", 999L, "sha-a", "written")),
                        ),
                    ),
                )
            },
            readPayload = readBytes,
            now = { NOW },
            log = store::log,
            finisher = dummyFinisher(),
        )

        val row = store.row("a")
        assertEquals(QueueState.FAILED, row.state)
        assertEquals(429, row.lastStatusCode)
        assertEquals("custody_mismatch", row.lastError)
        assertEquals(1, row.attemptCount)

        val notDue = selectDrainSegments(store.segmentsForDrain(), NOW + 10 * 60_000L)
        assertTrue(notDue.isEmpty())

        val due = selectDrainSegments(store.segmentsForDrain(), NOW + 15 * 60_000L)
        assertEquals(listOf("a"), due.map { it.id })
    }

    @Test
    fun drainPersistsRemovedInJournalNoRetry() {
        val fixture = TestFixture()
        val (seg, dir) = fixture.createSegment("a")

        val report = drainSegments(
            store = fixture.store,
            reconcile = uploadAll,
            ingest = { manifest, fileBytes ->
                manifest.files.forEach { fileBytes(it) }
                listOf(IngestOutcome.Rejected(500, """{"status":"error","reason_code":"segment_removed"}"""))
            },
            readPayload = readBytes,
            now = { NOW },
            log = fixture.store::log,
            finisher = fixture.finisher,
        )

        val row = fixture.store.row(seg.id)
        assertEquals(QueueState.EVICTED, row.state)
        assertNull(row.lastError)
        assertFalse(Files.exists(dir))
        assertTrue(Files.exists(fixture.spool.resolve(DAY).resolve(MAIN_STREAM)))
        assertFalse(report.failedThisRun)
        assertTrue(report.cleanDrain)
        assertEquals(SyncOutcome.SUCCESS, report.workOutcome)
    }

    @Test
    fun segmentRemovedLoneRowResultsInCleanDrainAndLogged() {
        val fixture = TestFixture()
        val (seg, dir) = fixture.createSegment("a")

        val report = drainSegments(
            store = fixture.store,
            reconcile = uploadAll,
            ingest = { manifest, fileBytes ->
                manifest.files.forEach { fileBytes(it) }
                listOf(IngestOutcome.Rejected(500, """{"status":"error","reason_code":"segment_removed"}"""))
            },
            readPayload = readBytes,
            now = { NOW },
            log = fixture.store::log,
            finisher = fixture.finisher,
        )

        assertEquals(SyncOutcome.SUCCESS, report.workOutcome)
        assertTrue(report.cleanDrain)
        assertFalse(report.failedThisRun)
        assertEquals(0, fixture.store.pendingCount(MAIN_STREAM))
        assertEquals(QueueState.EVICTED, fixture.store.row(seg.id).state)
        assertFalse(Files.exists(dir))
        assertFalse(fixture.store.logs.any { it.contains("segment removed") })
    }

    @Test
    fun segmentRemovedMixedWith503ReturnsRetry() {
        val fixture = TestFixture()
        val (segA, dirA) = fixture.createSegment("a", sealedAt = 1)
        val (segB, dirB) = fixture.createSegment("b", sealedAt = 2)

        val report = drainSegments(
            store = fixture.store,
            reconcile = uploadAll,
            ingest = { manifest, fileBytes ->
                manifest.files.forEach { fileBytes(it) }
                if (manifest.key.segment == "a") {
                    listOf(IngestOutcome.Rejected(500, """{"status":"error","reason_code":"segment_removed"}"""))
                } else {
                    listOf(IngestOutcome.Rejected(503, "temporary_unavailable"))
                }
            },
            readPayload = readBytes,
            now = { NOW },
            log = fixture.store::log,
            finisher = fixture.finisher,
        )

        assertEquals(SyncOutcome.RETRY, report.workOutcome)
        assertFalse(report.cleanDrain)
        assertTrue(report.failedThisRun)
        assertEquals(QueueState.EVICTED, fixture.store.row(segA.id).state)
        assertFalse(Files.exists(dirA))
        assertEquals(QueueState.FAILED, fixture.store.row(segB.id).state)
        assertEquals("retry", fixture.store.row(segB.id).lastError)
        assertTrue(Files.exists(dirB))
    }

    @Test
    fun receiptForEverySourceWithAlreadyHeldRemovesBeforeReturn() {
        val fixture = TestFixture()
        val f1 = BundleFile("audio", "audio.bin", "sha-1", 1, "audio/mp4", 1, 2)
        val f2 = BundleFile("video", "video.bin", "sha-2", 2, "video/mp4", 1, 2)
        val (seg, dir) = fixture.createSegment("a", files = listOf(f1, f2))

        val report = drainSegments(
            store = fixture.store,
            reconcile = uploadAll,
            ingest = { manifest, fileBytes ->
                manifest.files.groupBy(BundleFile::sourceId).map { (sourceId, files) ->
                    files.forEach { fileBytes(it) }
                    val disp = if (sourceId == "audio") "already_held" else "written"
                    val desc = files.map { IngestFileDescriptor(it.name, it.name, it.byteSize, it.sha256, disp) }
                    IngestOutcome.Accepted("srv-a", IngestDescriptors.Listed(desc))
                }
            },
            readPayload = readBytes,
            now = { NOW },
            log = fixture.store::log,
            finisher = fixture.finisher,
        )

        assertEquals(SyncOutcome.SUCCESS, report.workOutcome)
        assertEquals(QueueState.EVICTED, fixture.store.row(seg.id).state)
        assertFalse(Files.exists(dir))
    }

    @Test
    fun oneSourceReceiptedAndAnotherSegmentRemovedRemovesWithoutError() {
        val fixture = TestFixture()
        val f1 = BundleFile("audio", "audio.bin", "sha-1", 1, "audio/mp4", 1, 2)
        val f2 = BundleFile("video", "video.bin", "sha-2", 2, "video/mp4", 1, 2)
        val (seg, dir) = fixture.createSegment("a", files = listOf(f1, f2))

        val report = drainSegments(
            store = fixture.store,
            reconcile = uploadAll,
            ingest = { manifest, fileBytes ->
                manifest.files.groupBy(BundleFile::sourceId).map { (sourceId, files) ->
                    files.forEach { fileBytes(it) }
                    if (sourceId == "audio") {
                        val desc = files.map { IngestFileDescriptor(it.name, it.name, it.byteSize, it.sha256, "written") }
                        IngestOutcome.Accepted("srv-a", IngestDescriptors.Listed(desc))
                    } else {
                        IngestOutcome.Rejected(500, """{"status":"error","reason_code":"segment_removed"}""")
                    }
                }
            },
            readPayload = readBytes,
            now = { NOW },
            log = fixture.store::log,
            finisher = fixture.finisher,
        )

        assertEquals(SyncOutcome.SUCCESS, report.workOutcome)
        assertEquals(QueueState.EVICTED, fixture.store.row(seg.id).state)
        assertNull(fixture.store.row(seg.id).lastError)
        assertFalse(Files.exists(dir))
    }

    @Test
    fun oneSourceReceiptedAndAnotherRetryKeepsDirectoryAndNotEvicted() {
        val fixture = TestFixture()
        val f1 = BundleFile("audio", "audio.bin", "sha-1", 1, "audio/mp4", 1, 2)
        val f2 = BundleFile("video", "video.bin", "sha-2", 2, "video/mp4", 1, 2)
        val (seg, dir) = fixture.createSegment("a", files = listOf(f1, f2))

        val report = drainSegments(
            store = fixture.store,
            reconcile = uploadAll,
            ingest = { manifest, fileBytes ->
                manifest.files.groupBy(BundleFile::sourceId).map { (sourceId, files) ->
                    files.forEach { fileBytes(it) }
                    if (sourceId == "audio") {
                        val desc = files.map { IngestFileDescriptor(it.name, it.name, it.byteSize, it.sha256, "written") }
                        IngestOutcome.Accepted("srv-a", IngestDescriptors.Listed(desc))
                    } else {
                        IngestOutcome.Rejected(503, "temporary_unavailable")
                    }
                }
            },
            readPayload = readBytes,
            now = { NOW },
            log = fixture.store::log,
            finisher = fixture.finisher,
        )

        assertEquals(SyncOutcome.RETRY, report.workOutcome)
        assertEquals(QueueState.FAILED, fixture.store.row(seg.id).state)
        assertEquals(503, fixture.store.row(seg.id).lastStatusCode)
        assertEquals("retry", fixture.store.row(seg.id).lastError)
        assertTrue(Files.exists(dir))
    }

    @Test
    fun oneSourceReceiptedAndAnotherInvalidReceiptKeepsDirectory() {
        val fixture = TestFixture()
        val f1 = BundleFile("audio", "audio.bin", "sha-1", 1, "audio/mp4", 1, 2)
        val f2 = BundleFile("video", "video.bin", "sha-2", 2, "video/mp4", 1, 2)
        val (seg, dir) = fixture.createSegment("a", files = listOf(f1, f2))

        val report = drainSegments(
            store = fixture.store,
            reconcile = uploadAll,
            ingest = { manifest, fileBytes ->
                manifest.files.groupBy(BundleFile::sourceId).map { (sourceId, files) ->
                    files.forEach { fileBytes(it) }
                    if (sourceId == "audio") {
                        val desc = files.map { IngestFileDescriptor(it.name, it.name, it.byteSize, it.sha256, "written") }
                        IngestOutcome.Accepted("srv-a", IngestDescriptors.Listed(desc))
                    } else {
                        IngestOutcome.Accepted("srv-a", IngestDescriptors.NotAList)
                    }
                }
            },
            readPayload = readBytes,
            now = { NOW },
            log = fixture.store::log,
            finisher = fixture.finisher,
        )

        assertEquals(SyncOutcome.RETRY, report.workOutcome)
        assertEquals(QueueState.FAILED, fixture.store.row(seg.id).state)
        assertEquals(429, fixture.store.row(seg.id).lastStatusCode)
        assertEquals("custody_mismatch", fixture.store.row(seg.id).lastError)
        assertTrue(Files.exists(dir))
    }

    @Test
    fun http500WithoutReasonCodeSegmentRemovedRetriesAndKeepsDirectory() {
        val fixture = TestFixture()
        val (seg, dir) = fixture.createSegment("a")

        val report = drainSegments(
            store = fixture.store,
            reconcile = uploadAll,
            ingest = { manifest, fileBytes ->
                manifest.files.forEach { fileBytes(it) }
                listOf(IngestOutcome.Rejected(500, """{"error":"internal_server_error"}"""))
            },
            readPayload = readBytes,
            now = { NOW },
            log = fixture.store::log,
            finisher = fixture.finisher,
        )

        assertEquals(SyncOutcome.RETRY, report.workOutcome)
        assertEquals(QueueState.FAILED, fixture.store.row(seg.id).state)
        assertEquals(500, fixture.store.row(seg.id).lastStatusCode)
        assertEquals("retry", fixture.store.row(seg.id).lastError)
        assertTrue(Files.exists(dir))
    }

    @Test
    fun listingDifferentShaOrStatusReuploads() {
        val fixture = TestFixture()
        val sha = "a".repeat(64)
        val (seg, dir) = fixture.createSegment(
            "a",
            files = listOf(BundleFile("audio", "a.bin", sha, 3, "application/octet-stream", 1, 2)),
        )

        val fakeHttp = object : PlHttpClient {
            override fun request(
                method: String,
                path: String,
                headers: Map<String, String>,
                body: ByteArray?,
                maxResponseBytes: Int,
            ): HttpResponse = HttpResponse(
                200,
                emptyMap(),
                """{"items":[{"key":"a","files":[{"name":"a.bin","size":3,"sha256":"${"b".repeat(64)}","status":"processed"}]}],"total":1,"protocol_version":3}"""
                    .toByteArray(),
            )
        }
        var ingestCount = 0

        val report = drainSegments(
            store = fixture.store,
            reconcile = { manifests, day -> SegmentReconciler(fakeHttp).diff(manifests, day) },
            ingest = { manifest, fileBytes ->
                ingestCount++
                manifest.files.forEach { fileBytes(it) }
                listOf(
                    IngestOutcome.Accepted(
                        "srv-a",
                        IngestDescriptors.Listed(listOf(IngestFileDescriptor("a.bin", "a.bin", 3L, sha, "written"))),
                    ),
                )
            },
            readPayload = readBytes,
            now = { NOW },
            log = fixture.store::log,
            finisher = fixture.finisher,
        )

        assertEquals(1, ingestCount)
        assertEquals(SyncOutcome.SUCCESS, report.workOutcome)
        assertEquals(QueueState.EVICTED, fixture.store.row(seg.id).state)
        assertFalse(Files.exists(dir))
    }

    @Test
    fun recordUploadedHookCallsFinishPassWithoutThrow() {
        val root = Files.createTempDirectory("hook-test")
        val spool = root.resolve("spool")
        val segmentId = "$DAY/$MAIN_STREAM/a"
        val segmentDir = spool.resolve(segmentId)
        Files.createDirectories(segmentDir)
        Files.write(segmentDir.resolve("a.bin"), byteArrayOf(1))

        val manifestText = """
            solstone-bundle-manifest-v1
            day=$DAY
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

        val segmentRow = segment(segmentId, QueueState.SEALED).copy(day = DAY, stream = MAIN_STREAM, segment = "a", dirSegment = "a")
        val store = FakeDrainStore(segmentRow, files = mapOf(segmentId to listOf(file(segmentId))))

        var finisherRef: ConfirmedCopyFinisher? = null
        val dao = object : SegmentDao() {
            override fun insertSegment(segment: SegmentRow) = Unit
            override fun insertFiles(files: List<SegmentFileRow>) = Unit
            override fun insertEvents(events: List<EventRow>) = Unit
            override fun segmentsByState(state: QueueState): List<SegmentRow> =
                store.segmentsForDrain().filter { it.state == state }
            override fun segmentsForDrain(stream: String): List<SegmentRow> = store.segmentsForDrain()
            override fun segmentsByDay(day: String): List<SegmentRow> = emptyList()
            override fun segmentById(id: String): SegmentRow? = store.rowOrNull(id)
            override fun duplicateBySha256(sha256: String): List<SegmentFileRow> = emptyList()
            override fun filesBySegmentId(segmentId: String): List<SegmentFileRow> = store.filesBySegmentId(segmentId)
            override fun recordAttempt(id: String, attempts: Int, at: Long): Int = store.recordAttempt(id, attempts, at)
            override fun recordUploaded(id: String): Int {
                val res = store.recordUploaded(id)
                finisherRef?.finishPass()
                return res
            }
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
        finisherRef = finisher

        val report = drainSegments(
            store = store,
            reconcile = uploadAll,
            ingest = acceptedIngest("srv-a"),
            readPayload = readBytes,
            now = { NOW },
            log = store::log,
            finisher = finisher,
        )

        assertEquals(SyncOutcome.SUCCESS, report.workOutcome)
        assertTrue(report.cleanDrain)
        assertEquals(QueueState.EVICTED, store.row(segmentId).state)
        assertFalse(Files.exists(segmentDir))
    }

    @Test
    fun conflictVariantsAllResultInHardFailure409() {
        listOf(
            "content_conflict",
            "pairing_identity_unavailable",
            "not-json",
            null,
        ).forEach { reason ->
            val store = FakeDrainStore(segment("a"), files = mapOf("a" to listOf(file("a"))))

            drainSegments(
                store = store,
                reconcile = uploadAll,
                ingest = { manifest, fileBytes ->
                    manifest.files.forEach { fileBytes(it) }
                    listOf(IngestOutcome.Rejected(409, reason ?: ""))
                },
                readPayload = readBytes,
                now = { NOW },
                log = store::log,
                finisher = dummyFinisher(),
            )

            assertEquals(QueueState.FAILED, store.row("a").state)
            assertEquals(409, store.row("a").lastStatusCode)
            assertEquals("hard failure", store.row("a").lastError)
        }
    }

    @Test
    fun custodyMissingFollowedByProvenHeldSecondDrainSkipsIngest() {
        val fixture = TestFixture()
        val sha = "a".repeat(64)
        val (seg, dir) = fixture.createSegment(
            "a",
            files = listOf(BundleFile("audio", "a.bin", sha, 3, "application/octet-stream", 1, 2)),
        )

        // First drain: ingest succeeds with missing descriptors -> fails with custody_missing (408)
        val report1 = drainSegments(
            store = fixture.store,
            reconcile = uploadAll,
            ingest = { manifest, fileBytes ->
                manifest.files.forEach { fileBytes(it) }
                listOf(IngestOutcome.Accepted("srv-a", IngestDescriptors.Absent))
            },
            readPayload = readBytes,
            now = { NOW },
            log = fixture.store::log,
            finisher = fixture.finisher,
        )

        assertEquals(SyncOutcome.RETRY, report1.workOutcome)
        assertEquals(QueueState.FAILED, fixture.store.row(seg.id).state)
        assertEquals(408, fixture.store.row(seg.id).lastStatusCode)
        assertEquals("custody_missing", fixture.store.row(seg.id).lastError)
        assertEquals(1, fixture.store.row(seg.id).attemptCount)
        assertTrue(Files.exists(dir))

        // Second drain after 15 minutes: Reconciler finds file processed on server -> skips ingest
        val fakeHttp = object : PlHttpClient {
            override fun request(
                method: String,
                path: String,
                headers: Map<String, String>,
                body: ByteArray?,
                maxResponseBytes: Int,
            ): HttpResponse = HttpResponse(
                200,
                emptyMap(),
                """{"items":[{"key":"a","files":[{"name":"a.bin","size":3,"sha256":"$sha","status":"processed"}]}],"total":1,"protocol_version":3}"""
                    .toByteArray(),
            )
        }
        var secondIngestCount = 0

        val report2 = drainSegments(
            store = fixture.store,
            reconcile = { manifests, day -> SegmentReconciler(fakeHttp).diff(manifests, day) },
            ingest = { _, _ ->
                secondIngestCount++
                error("ingest should not be called")
            },
            readPayload = readBytes,
            now = { NOW + 15 * 60_000L },
            log = fixture.store::log,
            finisher = fixture.finisher,
        )

        assertEquals(0, secondIngestCount)
        assertEquals(SyncOutcome.SUCCESS, report2.workOutcome)
        assertEquals(QueueState.EVICTED, fixture.store.row(seg.id).state)
        assertNull(fixture.store.row(seg.id).lastError)
        assertFalse(Files.exists(dir))
    }

    @Test
    fun custodyMissingFollowedByUnprovenSecondDrainReuploads() {
        val fixture = TestFixture()
        val sha = "a".repeat(64)
        val (seg, dir) = fixture.createSegment(
            "a",
            files = listOf(BundleFile("audio", "a.bin", sha, 3, "application/octet-stream", 1, 2)),
        )

        // First drain fails with custody_missing
        drainSegments(
            store = fixture.store,
            reconcile = uploadAll,
            ingest = { manifest, fileBytes ->
                manifest.files.forEach { fileBytes(it) }
                listOf(IngestOutcome.Accepted("srv-a", IngestDescriptors.Absent))
            },
            readPayload = readBytes,
            now = { NOW },
            log = fixture.store::log,
            finisher = fixture.finisher,
        )

        // Second drain: server listing has different sha -> reconciler demands upload -> ingest succeeds with valid descriptors
        val fakeHttp = object : PlHttpClient {
            override fun request(
                method: String,
                path: String,
                headers: Map<String, String>,
                body: ByteArray?,
                maxResponseBytes: Int,
            ): HttpResponse = HttpResponse(
                200,
                emptyMap(),
                """{"items":[{"key":"a","files":[{"name":"a.bin","size":3,"sha256":"${"b".repeat(64)}","status":"processed"}]}],"total":1,"protocol_version":3}"""
                    .toByteArray(),
            )
        }
        var secondIngestCount = 0

        val report2 = drainSegments(
            store = fixture.store,
            reconcile = { manifests, day -> SegmentReconciler(fakeHttp).diff(manifests, day) },
            ingest = { manifest, fileBytes ->
                secondIngestCount++
                manifest.files.forEach { fileBytes(it) }
                listOf(
                    IngestOutcome.Accepted(
                        "srv-a",
                        IngestDescriptors.Listed(
                            listOf(IngestFileDescriptor("a.bin", "a.bin", 3L, sha, "written")),
                        ),
                    ),
                )
            },
            readPayload = readBytes,
            now = { NOW + 15 * 60_000L },
            log = fixture.store::log,
            finisher = fixture.finisher,
        )

        assertEquals(1, secondIngestCount)
        assertEquals(SyncOutcome.SUCCESS, report2.workOutcome)
        assertEquals(QueueState.EVICTED, fixture.store.row(seg.id).state)
        assertNull(fixture.store.row(seg.id).lastError)
        assertFalse(Files.exists(dir))
    }

    @Test
    fun drainFinishesConfirmedCopySynchronously() {
        val fixture = TestFixture()
        val (seg, dir) = fixture.createSegment("a")

        val report = drainSegments(
            store = fixture.store,
            reconcile = uploadAll,
            ingest = acceptedIngest("srv-a"),
            readPayload = readBytes,
            now = { NOW },
            log = fixture.store::log,
            finisher = fixture.finisher,
        )

        assertEquals(SyncOutcome.SUCCESS, report.workOutcome)
        assertTrue(report.cleanDrain)
        assertEquals(QueueState.EVICTED, fixture.store.row(seg.id).state)
        assertFalse(Files.exists(dir))
    }

    @Test
    fun drainDrainsDaysInAscendingOrderAcrossDaylightTransitions() {
        val day1 = "20260307"
        val day2 = "20260308"
        val day3 = "20260309"
        val seg1 = segment("seg-1", sealedAt = 100).copy(day = day1)
        val seg2 = segment("seg-2", sealedAt = 200).copy(day = day2)
        val seg3 = segment("seg-3", sealedAt = 300).copy(day = day3)

        val store = FakeDrainStore(
            rows = listOf(seg2, seg3, seg1),
            files = mapOf("seg-1" to listOf(file("seg-1")), "seg-2" to listOf(file("seg-2")), "seg-3" to listOf(file("seg-3"))),
        )
        val drainedDays = mutableListOf<String>()

        drainSegments(
            store = store,
            reconcile = { manifests, day ->
                drainedDays.add(day)
                manifests.map { ReconcileVerdict(it.key, needsUpload = true) }
            },
            ingest = acceptedIngest("srv"),
            readPayload = readBytes,
            now = { NOW },
            log = store::log,
            finisher = dummyFinisher(),
        )

        assertEquals(listOf(day1, day2, day3), drainedDays)
    }

    private class TestFixture(
        val root: Path = Files.createTempDirectory("drain-fixture"),
    ) {
        val spool: Path = root.resolve("spool")
        val store = FakeDrainStore()
        val dao = object : SegmentDao() {
            override fun insertSegment(segment: SegmentRow) = Unit
            override fun insertFiles(files: List<SegmentFileRow>) = Unit
            override fun insertEvents(events: List<EventRow>) = Unit
            override fun segmentsByState(state: QueueState): List<SegmentRow> =
                store.segmentsForDrain().filter { it.state == state }
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

        fun createSegment(
            leaf: String,
            day: String = DAY,
            stream: String = MAIN_STREAM,
            state: QueueState = QueueState.SEALED,
            files: List<BundleFile> = listOf(BundleFile("audio", "$leaf.bin", "sha-$leaf", 1, "application/octet-stream", 1, 2)),
            sealedAt: Long = 1,
            attemptCount: Int = 0,
            lastAttemptAt: Long? = null,
            lastStatusCode: Int? = null,
            lastError: String? = null,
            writeDisk: Boolean = true,
        ): Pair<SegmentRow, Path> {
            val segmentId = "$day/$stream/$leaf"
            val segmentDir = spool.resolve(segmentId)
            if (writeDisk) {
                Files.createDirectories(segmentDir)
                files.forEach { file ->
                    Files.write(segmentDir.resolve(file.name), byteArrayOf(1))
                }
                val manifestText = buildString {
                    appendLine("solstone-bundle-manifest-v1")
                    appendLine("day=$day")
                    appendLine("segment=$leaf")
                    appendLine("startEpochMs=1")
                    appendLine("endEpochMs=2")
                    appendLine("zoneId=UTC")
                    appendLine("utcOffsetSeconds=0")
                    appendLine("[files]")
                    files.forEach { file ->
                        appendLine("${file.sourceId}\t${file.name}\t${file.sha256}\t${file.byteSize}\t${file.mediaType}\t${file.captureStartEpochMs}\t${file.captureEndEpochMs}")
                    }
                    appendLine("[gaps]")
                }
                Files.write(segmentDir.resolve("manifest"), manifestText.toByteArray())
            }
            val row = SegmentRow(
                id = segmentId,
                day = day,
                stream = stream,
                segment = leaf,
                dirSegment = leaf,
                state = state,
                byteSize = files.sumOf { it.byteSize },
                sealedAt = sealedAt,
                homeInstanceId = null,
                observerHandle = null,
                attemptCount = attemptCount,
                lastStatusCode = lastStatusCode,
                lastAttemptAt = lastAttemptAt,
                lastError = lastError,
            )
            val fileRows = files.map { file ->
                SegmentFileRow(
                    segmentId = segmentId,
                    sourceId = file.sourceId,
                    name = file.name,
                    sha256 = file.sha256,
                    byteSize = file.byteSize,
                    mediaType = file.mediaType,
                    captureStartEpochMs = file.captureStartEpochMs,
                    captureEndEpochMs = file.captureEndEpochMs,
                )
            }
            store.add(row, fileRows)
            return row to segmentDir
        }
    }

    private companion object {
        const val DAY = "20260617"
        const val NOW = 1_000_000L

        val uploadAll: (List<BundleManifest>, String) -> List<ReconcileVerdict> = { manifests, _ ->
            manifests.map { ReconcileVerdict(it.key, needsUpload = true) }
        }

        val readBytes: (SegmentRow, BundleFile) -> ByteArray = { _, _ -> byteArrayOf(1) }

        fun acceptedIngest(serverKey: String): (BundleManifest, (BundleFile) -> ByteArray) -> List<IngestOutcome> =
            { manifest, fileBytes ->
                manifest.files.groupBy(BundleFile::sourceId).map { (_, files) ->
                    files.forEach { fileBytes(it) }
                    val descriptors = files.map { file ->
                        IngestFileDescriptor(
                            submitted = file.name,
                            written = file.name,
                            size = file.byteSize,
                            sha256 = file.sha256,
                            disposition = "written",
                        )
                    }
                    IngestOutcome.Accepted(serverKey, IngestDescriptors.Listed(descriptors))
                }
            }

        fun rejectedIngest(status: Int, reasonCode: String = "rejected"): (BundleManifest, (BundleFile) -> ByteArray) -> List<IngestOutcome> =
            { manifest, fileBytes ->
                manifest.files.groupBy(BundleFile::sourceId).map { (_, files) ->
                    files.forEach { fileBytes(it) }
                    IngestOutcome.Rejected(status, reasonCode)
                }
            }
    }
}
