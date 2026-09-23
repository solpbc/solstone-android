// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.work

import app.solstone.core.model.BundleFile
import app.solstone.core.model.BundleManifest
import app.solstone.core.model.QueueState
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
import app.solstone.platform.persistence.room.SegmentFileRow
import app.solstone.platform.persistence.room.SegmentRow
import app.solstone.platform.persistence.room.SyncStateRow
import java.io.FileNotFoundException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SegmentDrainerTest {
    @Test
    fun drainsSealedUploadAndPersistsCleanSuccess() {
        val store = FakeDrainStore(segment("a"), files = mapOf("a" to listOf(file("a"))))

        val report = drainSegments(
            store = store,
            reconcile = uploadAll,
            ingest = acceptedIngest("srv-a"),
            readPayload = readBytes,
            now = { NOW },
            log = store::log,
        )

        assertEquals(SyncOutcome.SUCCESS, report.workOutcome)
        assertTrue(report.cleanDrain)
        assertFalse(report.failedThisRun)
        assertEquals(QueueState.UPLOADED, store.row("a").state)
        assertNull(store.row("a").lastError)
        assertEquals(1, store.row("a").attemptCount)
        assertEquals(0, store.syncState!!.pendingCount)
        assertEquals(NOW, store.syncState!!.lastSuccessAt)
    }

    @Test
    fun processedVerdictSkipsUploadAndMarksUploaded() {
        val store = FakeDrainStore(
            segment("a"),
            files = mapOf("a" to listOf(file("a").copy(sha256 = "a".repeat(64), byteSize = 3))),
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
            store = store,
            reconcile = { manifests, day -> SegmentReconciler(fakeHttp).diff(manifests, day) },
            ingest = { _, _ ->
                ingestCount += 1
                error("ingest must not be called for a held segment")
            },
            readPayload = readBytes,
            now = { NOW },
            log = store::log,
        )

        assertEquals(0, ingestCount)
        assertEquals(QueueState.UPLOADED, store.row("a").state)
    }

    @Test
    fun recoversUploadingViaMarkFailedThenRetryThenUpload() {
        val store = FakeDrainStore(segment("a", QueueState.UPLOADING), files = mapOf("a" to listOf(file("a"))))

        drainSegments(
            store = store,
            reconcile = uploadAll,
            ingest = acceptedIngest("srv-a"),
            readPayload = readBytes,
            now = { NOW },
            log = store::log,
        )

        assertEquals(
            listOf(QueueEvent.MARK_FAILED, QueueEvent.RETRY, QueueEvent.MARK_UPLOADED),
            store.eventsFor("a"),
        )
        assertEquals(QueueState.UPLOADED, store.row("a").state)
        assertNull(store.row("a").lastError)
        assertEquals(1, store.row("a").attemptCount)
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
        val store = FakeDrainStore(
            segment("missing", sealedAt = 1),
            segment("ok", sealedAt = 2),
            files = mapOf("missing" to listOf(file("missing")), "ok" to listOf(file("ok"))),
        )

        val report = drainSegments(
            store = store,
            reconcile = uploadAll,
            ingest = acceptedIngest("srv"),
            readPayload = { segment, _ ->
                if (segment.id == "missing") throw FileNotFoundException("missing")
                byteArrayOf(1)
            },
            now = { NOW },
            log = store::log,
        )

        assertEquals(SyncOutcome.RETRY, report.workOutcome)
        assertTrue(report.failedThisRun)
        assertEquals(QueueState.FAILED, store.row("missing").state)
        assertEquals("payload missing", store.row("missing").lastError)
        assertEquals(QueueState.UPLOADED, store.row("ok").state)
        assertTrue(store.logs.any { it.contains("payload missing missing") })
    }

    @Test
    fun payloadPathViolationMarksFailedAndContinues() {
        val store = FakeDrainStore(
            segment("bad", sealedAt = 1),
            segment("ok", sealedAt = 2),
            files = mapOf("bad" to listOf(file("bad")), "ok" to listOf(file("ok"))),
        )

        val report = drainSegments(
            store = store,
            reconcile = uploadAll,
            ingest = acceptedIngest("srv"),
            readPayload = { segment, _ ->
                if (segment.id == "bad") throw IllegalArgumentException("bad path")
                byteArrayOf(1)
            },
            now = { NOW },
            log = store::log,
        )

        assertEquals(SyncOutcome.RETRY, report.workOutcome)
        assertTrue(report.failedThisRun)
        assertEquals(QueueState.FAILED, store.row("bad").state)
        assertEquals("payload unreadable", store.row("bad").lastError)
        assertEquals(QueueState.UPLOADED, store.row("ok").state)
        assertTrue(store.logs.any { it.contains("payload unreadable bad") })
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
        )

        assertEquals(SyncOutcome.FAILURE, report.workOutcome)
        assertFalse(report.cleanDrain)
        assertTrue(report.failedThisRun)
        assertEquals(QueueState.SEALED, store.row("a").state)
        assertTrue(store.logs.any { it.contains("reconcile auth halt day=$DAY") })
    }

    @Test
    fun capLimitsAttemptsAndLeavesRemainderDueRetrying() {
        val segments = (0 until 51).map { index -> segment("seg-$index", sealedAt = index.toLong()) }
        val store = FakeDrainStore(
            rows = segments,
            files = segments.associate { it.id to listOf(file(it.id)) },
        )
        var ingestCount = 0

        val report = drainSegments(
            store = store,
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
            log = store::log,
        )

        assertEquals(50, ingestCount)
        assertEquals(SyncOutcome.RETRY, report.workOutcome)
        assertFalse(report.cleanDrain)
        assertFalse(report.failedThisRun)
        assertEquals(1, store.syncState!!.pendingCount)
    }

    @Test
    fun logsClaimPayloadAndReconcileCatches() {
        val claimStore = FakeDrainStore(segment("claim"), files = mapOf("claim" to listOf(file("claim"))))
        claimStore.failAdvanceFor += "claim"
        drainSegments(claimStore, uploadAll, acceptedIngest("unused"), readBytes, { NOW }, claimStore::log)

        val payloadStore = FakeDrainStore(segment("payload"), files = mapOf("payload" to listOf(file("payload"))))
        drainSegments(
            payloadStore,
            uploadAll,
            acceptedIngest("unused"),
            { _, _ -> throw FileNotFoundException("missing") },
            { NOW },
            payloadStore::log,
        )

        val reconcileStore = FakeDrainStore(segment("reconcile"), files = mapOf("reconcile" to listOf(file("reconcile"))))
        drainSegments(
            reconcileStore,
            { _, _ -> throw ReconcileUnavailableException(500) },
            acceptedIngest("unused"),
            readBytes,
            { NOW },
            reconcileStore::log,
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
        )

        assertEquals(2, postedSources)
        assertEquals(QueueState.FAILED, store.row("a").state)
        assertEquals(422, store.row("a").lastStatusCode)
        assertEquals("custody_not_written", store.row("a").lastError)
    }

    @Test
    fun multiSource3SourcesAllMustSucceed() {
        val audio = file("a").copy(name = "audio.bin", sourceId = "audio", sha256 = "sha-audio")
        val video = file("a").copy(name = "video.bin", sourceId = "video", sha256 = "sha-video")
        val motion = file("a").copy(name = "motion.bin", sourceId = "motion", sha256 = "sha-motion")
        val store = FakeDrainStore(segment("a"), files = mapOf("a" to listOf(audio, video, motion)))

        val report = drainSegments(
            store = store,
            reconcile = uploadAll,
            ingest = acceptedIngest("srv-a"),
            readPayload = readBytes,
            now = { NOW },
            log = store::log,
        )

        assertEquals(SyncOutcome.SUCCESS, report.workOutcome)
        assertEquals(QueueState.UPLOADED, store.row("a").state)
        assertNull(store.row("a").lastError)
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
            ingest = { manifest, fileBytes ->
                listOf(
                    IngestOutcome.Rejected(401, "unauthorized"),
                )
            },
            readPayload = readBytes,
            now = { NOW },
            log = store::log,
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
        )

        val row = store.row("a")
        assertEquals(QueueState.FAILED, row.state)
        assertEquals(422, row.lastStatusCode)
        assertEquals("custody_not_written", row.lastError)
        assertEquals(1, row.attemptCount)

        // HARD_FAIL ladder backoff for attempt 1 is 120 minutes (2 hours)
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
        )

        val row = store.row("a")
        assertEquals(QueueState.FAILED, row.state)
        assertEquals(429, row.lastStatusCode)
        assertEquals("custody_mismatch", row.lastError)
        assertEquals(1, row.attemptCount)

        // RETRY ladder backoff for attempt 1 is 15 minutes
        val notDue = selectDrainSegments(store.segmentsForDrain(), NOW + 10 * 60_000L)
        assertTrue(notDue.isEmpty())

        val due = selectDrainSegments(store.segmentsForDrain(), NOW + 15 * 60_000L)
        assertEquals(listOf("a"), due.map { it.id })
    }

    @Test
    fun drainPersistsRemovedInJournalNoRetry() {
        val store = FakeDrainStore(segment("a"), files = mapOf("a" to listOf(file("a"))))

        drainSegments(
            store = store,
            reconcile = uploadAll,
            ingest = { manifest, fileBytes ->
                manifest.files.forEach { fileBytes(it) }
                listOf(IngestOutcome.Rejected(500, """{"status":"error","reason_code":"segment_removed"}"""))
            },
            readPayload = readBytes,
            now = { NOW },
            log = store::log,
        )

        val row = store.row("a")
        assertEquals(QueueState.FAILED, row.state)
        assertEquals(500, row.lastStatusCode)
        assertEquals("removed_in_journal", row.lastError)

        val due = selectDrainSegments(store.segmentsForDrain(), NOW + 1_000_000_000L)
        assertTrue(due.isEmpty())
    }

    @Test
    fun segmentRemovedLoneRowResultsInCleanDrainAndLogged() {
        val store = FakeDrainStore(segment("a"), files = mapOf("a" to listOf(file("a"))))

        val report = drainSegments(
            store = store,
            reconcile = uploadAll,
            ingest = { manifest, fileBytes ->
                manifest.files.forEach { fileBytes(it) }
                listOf(IngestOutcome.Rejected(500, """{"status":"error","reason_code":"segment_removed"}"""))
            },
            readPayload = readBytes,
            now = { NOW },
            log = store::log,
        )

        assertEquals(SyncOutcome.SUCCESS, report.workOutcome)
        assertTrue(report.cleanDrain)
        assertFalse(report.failedThisRun)
        assertEquals(0, store.pendingCount(MAIN_STREAM))
        assertTrue(store.logs.any { it.contains("segment removed a") })
    }

    @Test
    fun segmentRemovedMixedWith503ReturnsRetry() {
        val store = FakeDrainStore(
            segment("a", sealedAt = 1),
            segment("b", sealedAt = 2),
            files = mapOf("a" to listOf(file("a")), "b" to listOf(file("b"))),
        )

        val report = drainSegments(
            store = store,
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
            log = store::log,
        )

        assertEquals(SyncOutcome.RETRY, report.workOutcome)
        assertFalse(report.cleanDrain)
        assertTrue(report.failedThisRun)
        assertEquals("removed_in_journal", store.row("a").lastError)
        assertEquals("retry", store.row("b").lastError)
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
            )

            assertEquals(QueueState.FAILED, store.row("a").state)
            assertEquals(409, store.row("a").lastStatusCode)
            assertEquals("hard failure", store.row("a").lastError)
        }
    }

    @Test
    fun custodyMissingFollowedByProvenHeldSecondDrainSkipsIngest() {
        val sha = "a".repeat(64)
        val fileA = file("a").copy(sha256 = sha, byteSize = 3)
        val store = FakeDrainStore(segment("a"), files = mapOf("a" to listOf(fileA)))

        // First drain: ingest succeeds with missing descriptors -> fails with custody_missing (408)
        val report1 = drainSegments(
            store = store,
            reconcile = uploadAll,
            ingest = { manifest, fileBytes ->
                manifest.files.forEach { fileBytes(it) }
                listOf(IngestOutcome.Accepted("srv-a", IngestDescriptors.Absent))
            },
            readPayload = readBytes,
            now = { NOW },
            log = store::log,
        )

        assertEquals(SyncOutcome.RETRY, report1.workOutcome)
        assertEquals(QueueState.FAILED, store.row("a").state)
        assertEquals(408, store.row("a").lastStatusCode)
        assertEquals("custody_missing", store.row("a").lastError)
        assertEquals(1, store.row("a").attemptCount)

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
            store = store,
            reconcile = { manifests, day -> SegmentReconciler(fakeHttp).diff(manifests, day) },
            ingest = { _, _ ->
                secondIngestCount++
                error("ingest should not be called")
            },
            readPayload = readBytes,
            now = { NOW + 15 * 60_000L },
            log = store::log,
        )

        assertEquals(0, secondIngestCount)
        assertEquals(SyncOutcome.SUCCESS, report2.workOutcome)
        assertEquals(QueueState.UPLOADED, store.row("a").state)
        assertEquals("custody_missing", store.row("a").lastError)
    }

    @Test
    fun custodyMissingFollowedByUnprovenSecondDrainReuploads() {
        val sha = "a".repeat(64)
        val fileA = file("a").copy(sha256 = sha, byteSize = 3)
        val store = FakeDrainStore(segment("a"), files = mapOf("a" to listOf(fileA)))

        // First drain fails with custody_missing
        drainSegments(
            store = store,
            reconcile = uploadAll,
            ingest = { manifest, fileBytes ->
                manifest.files.forEach { fileBytes(it) }
                listOf(IngestOutcome.Accepted("srv-a", IngestDescriptors.Absent))
            },
            readPayload = readBytes,
            now = { NOW },
            log = store::log,
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
            store = store,
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
            log = store::log,
        )

        assertEquals(1, secondIngestCount)
        assertEquals(SyncOutcome.SUCCESS, report2.workOutcome)
        assertEquals(QueueState.UPLOADED, store.row("a").state)
        assertNull(store.row("a").lastError)
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
