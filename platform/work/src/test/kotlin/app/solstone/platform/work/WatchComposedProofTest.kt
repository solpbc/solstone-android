// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.work

import app.solstone.core.model.BundleFile
import app.solstone.core.model.BundleManifest
import app.solstone.core.model.QueueState
import app.solstone.core.model.SegmentKey
import app.solstone.core.model.WireKeys
import app.solstone.core.observer.IngestOutcome
import app.solstone.core.observer.ObserverIngestClient
import app.solstone.core.observer.SegmentReconciler
import app.solstone.core.pl.HttpResponse
import app.solstone.core.segment.SealedSegment
import app.solstone.core.segment.SegmentPayload
import app.solstone.core.segment.wireKeys
import app.solstone.core.sources.MAIN_STREAM
import app.solstone.core.sources.PayloadRef
import app.solstone.core.sources.WATCH_STREAM
import app.solstone.core.spool.FileSpoolWriter
import app.solstone.core.spool.PayloadBytesProvider
import app.solstone.core.watch.courier.CourierBundleHeader
import app.solstone.core.watch.courier.CourierFilePayload
import app.solstone.core.watch.courier.WatchCourierCodec
import app.solstone.core.watch.courier.WatchCourierProtocol
import app.solstone.core.watch.courier.WatchLocationFix
import app.solstone.core.watch.courier.WatchLocationRecord
import app.solstone.platform.persistence.room.ConfirmedCopyFinisher
import app.solstone.platform.persistence.room.EventRow
import app.solstone.platform.persistence.room.SegmentDao
import app.solstone.platform.persistence.room.SegmentFileRow
import app.solstone.platform.persistence.room.SegmentRow
import app.solstone.platform.persistence.room.SyncStateRow
import app.solstone.platform.persistence.room.WatchBundleReceiptRow
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WatchComposedProofTest {

    private class InMemorySegmentDao : SegmentDao() {
        val segments = mutableMapOf<String, SegmentRow>()
        val files = mutableMapOf<String, MutableList<SegmentFileRow>>()
        val receipts = mutableMapOf<String, WatchBundleReceiptRow>()

        override fun insertReceipt(receipt: WatchBundleReceiptRow) {
            receipts[receipt.bundleId] = receipt
        }

        override fun receiptByBundleId(bundleId: String): WatchBundleReceiptRow? = receipts[bundleId]

        override fun insertSegment(segment: SegmentRow) {
            segments[segment.id] = segment
        }

        override fun insertFiles(files: List<SegmentFileRow>) {
            files.forEach { f ->
                this.files.getOrPut(f.segmentId) { mutableListOf() }.add(f)
            }
        }

        override fun insertEvents(events: List<EventRow>) = Unit

        override fun segmentsByState(state: QueueState): List<SegmentRow> =
            segments.values.filter { it.state == state }

        override fun segmentsForDrain(stream: String): List<SegmentRow> =
            segments.values.filter {
                it.stream == stream &&
                    (it.state == QueueState.SEALED || it.state == QueueState.UPLOADING || it.state == QueueState.FAILED)
            }.sortedWith(compareBy({ it.sealedAt }, { it.id }))

        override fun segmentsByDay(day: String): List<SegmentRow> =
            segments.values.filter { it.day == day }

        override fun segmentById(id: String): SegmentRow? = segments[id]

        override fun duplicateBySha256(sha256: String): List<SegmentFileRow> =
            files.values.flatten().filter { it.sha256 == sha256 }

        override fun filesBySegmentId(segmentId: String): List<SegmentFileRow> =
            files[segmentId].orEmpty()

        override fun recordAttempt(id: String, attempts: Int, at: Long, homeInstanceId: String?): Int {
            val cur = segments[id] ?: return 0
            segments[id] = cur.copy(attemptCount = attempts, lastAttemptAt = at, homeInstanceId = homeInstanceId)
            return 1
        }

        override fun recordUploaded(id: String): Int {
            val cur = segments[id] ?: return 0
            segments[id] = cur.copy(lastError = null)
            return 1
        }

        override fun recordFailure(id: String, code: Int?, error: String?): Int {
            val cur = segments[id] ?: return 0
            segments[id] = cur.copy(lastStatusCode = code, lastError = error)
            return 1
        }

        override fun upsertSyncState(row: SyncStateRow) = Unit
        override fun syncState(): SyncStateRow? = null
        override fun pendingCount(stream: String): Int =
            segments.values.count {
                it.stream == stream &&
                    (it.state == QueueState.SEALED || it.state == QueueState.UPLOADING || it.state == QueueState.FAILED) &&
                    it.lastError != "removed_in_journal"
            }

        override fun pendingSourceIds(stream: String): List<String> = emptyList()
        override fun segmentState(id: String): QueueState? = segments[id]?.state
        override fun updateState(id: String, state: QueueState): Int {
            val cur = segments[id] ?: return 0
            segments[id] = cur.copy(state = state)
            return 1
        }

        override fun deleteFilesBySegmentId(segmentId: String): Int {
            files.remove(segmentId)
            return 1
        }

        override fun deleteFilesBySegmentIds(segmentIds: List<String>): Int {
            segmentIds.forEach { files.remove(it) }
            return 1
        }
    }

    private companion object {
        fun statusOk(): HttpResponse = HttpResponse(200, emptyMap(), ByteArray(0))

        fun uploadRequiredSegments(): HttpResponse = HttpResponse(
            200,
            emptyMap(),
            """{"items":[{"key":"remote-non-match","observed":true,"files":[]}],"total":1,"protocol_version":3}""".toByteArray(),
        )

        fun ingestAcceptedFor(files: List<CourierFilePayload>): HttpResponse {
            val descriptors = files.joinToString(",") { f ->
                """{"submitted":"${f.name}","written":"${f.name}","size":${f.byteSize},"sha256":"${f.sha256}","disposition":"written"}"""
            }
            return HttpResponse(
                200,
                emptyMap(),
                """{"status":"ok","segment":"srv-1","file_descriptors":[$descriptors]}""".toByteArray(),
            )
        }
    }

    @Test
    fun sameDayAndSameKeyIngestDoesNotDropWatchBundle() {
        val root = Files.createTempDirectory("watch-composed-proof-same-key")
        val spoolDir = root.resolve("spool")
        val stagingDir = root.resolve("staging")
        Files.createDirectories(spoolDir)
        Files.createDirectories(stagingDir)

        val dao = InMemorySegmentDao()
        val publisher = WatchCustodyPublisher(spoolDir, stagingDir, dao) { 1000L }

        // Both phone audio and watch bundle share this exact day and HHMMSS_LEN
        val sharedDay = "20261109"
        val sharedKey = "100000_300"
        val sharedStartMs = Instant.parse("2026-11-09T17:00:00Z").toEpochMilli()
        val sharedEndMs = Instant.parse("2026-11-09T17:05:00Z").toEpochMilli()
        val sharedZone = "America/Denver"
        val sharedOffset = -25200

        // 1. Phone audio fixture
        val expectedPhoneFileBytes = byteArrayOf(11, 22, 33, 44)
        val phoneSpoolWriter = FileSpoolWriter(spoolDir)
        val phoneKeys = WireKeys(
            day = sharedDay,
            segment = sharedKey,
            startEpochMs = sharedStartMs,
            endEpochMs = sharedEndMs,
            zoneId = sharedZone,
            utcOffsetSeconds = sharedOffset,
        )
        val phonePayload = SegmentPayload(
            sourceId = "audio",
            ref = PayloadRef("audio.m4a", "audio/mp4", expectedPhoneFileBytes.size.toLong(), "phone-sha"),
            captureStartEpochMs = sharedStartMs,
            captureEndEpochMs = sharedEndMs,
        )
        val phoneSealedSegment = SealedSegment(
            stream = MAIN_STREAM,
            key = SegmentKey(sharedDay, sharedKey),
            wireKeys = phoneKeys,
            payloads = listOf(phonePayload),
            gaps = emptyList(),
        )
        val phoneSealResult = phoneSpoolWriter.seal(phoneSealedSegment, object : PayloadBytesProvider {
            override fun open(payload: SegmentPayload): InputStream = ByteArrayInputStream(expectedPhoneFileBytes)
            override fun release(payload: SegmentPayload) {}
        })
        val phoneSegmentId = "$sharedDay/$MAIN_STREAM/$sharedKey"
        dao.insertSegment(
            SegmentRow(
                id = phoneSegmentId,
                day = sharedDay,
                stream = MAIN_STREAM,
                segment = sharedKey,
                dirSegment = sharedKey,
                state = QueueState.SEALED,
                byteSize = expectedPhoneFileBytes.size.toLong(),
                sealedAt = 1000L,
                homeInstanceId = null,
                observerHandle = null,
            ),
        )
        dao.insertFiles(
            listOf(
                SegmentFileRow(
                    segmentId = phoneSegmentId,
                    sourceId = "audio",
                    name = "audio.m4a",
                    sha256 = WatchCourierCodec.sha256Hex(expectedPhoneFileBytes),
                    byteSize = expectedPhoneFileBytes.size.toLong(),
                    mediaType = "audio/mp4",
                    captureStartEpochMs = sharedStartMs,
                    captureEndEpochMs = sharedEndMs,
                ),
            ),
        )

        // 2. Watch audio bundle fixture
        val expectedWatchFileBytes = byteArrayOf(55, 66, 77, 88)
        val watchHeader = CourierBundleHeader(
            protocolVersion = WatchCourierProtocol.PROTOCOL_VERSION,
            bundleId = "watch-bundle-proof-1",
            day = sharedDay,
            segment = sharedKey,
            startEpochMs = sharedStartMs,
            endEpochMs = sharedEndMs,
            zoneId = sharedZone,
            utcOffsetSeconds = sharedOffset,
            sealGeneration = 1L,
        )
        val watchPayloads = listOf(
            CourierFilePayload(
                name = WatchCourierProtocol.FILE_NAME_AUDIO,
                mediaType = WatchCourierProtocol.MEDIA_TYPE_AUDIO,
                sha256 = WatchCourierCodec.sha256Hex(expectedWatchFileBytes),
                byteSize = expectedWatchFileBytes.size.toLong(),
                bytes = expectedWatchFileBytes,
            ),
        )
        val watchBaos = ByteArrayOutputStream()
        WatchCourierCodec.encodeBundle(watchHeader, watchPayloads, watchBaos)

        var syncEnqueuedCount = 0
        val publishResult = publisher.publish(ByteArrayInputStream(watchBaos.toByteArray())) {
            syncEnqueuedCount++
        }
        assertTrue(publishResult is PublishBundleResult.Ack)
        assertEquals(1, syncEnqueuedCount)
        assertEquals(sharedStartMs, dao.segmentsForDrain(WATCH_STREAM).single().sealedAt)

        // 3. Capturing HTTP client following SyncWithTransportTest
        val client = RecordingPlHttpClient(
            uploadRequiredSegments(),
            HttpResponse(
                200,
                emptyMap(),
                """{"status":"ok","segment":"srv-1","file_descriptors":[{"submitted":"audio.m4a","written":"audio.m4a","size":4,"sha256":"0000","disposition":"written"}]}""".toByteArray(),
            ),
            uploadRequiredSegments(),
            ingestAcceptedFor(watchPayloads),
        )

        val store = RoomDrainStore(dao)
        val finisher = ConfirmedCopyFinisher(spoolRoot = spoolDir, dao = dao)

        drainSegments(
            store = store,
            reconcile = SegmentReconciler(client)::diff,
            ingest = { manifest, fileBytes ->
                ObserverIngestClient(client) { "boundary123" }.ingest(
                    manifest = manifest,
                    fileBytes = fileBytes,
                    host = "test-phone",
                    platform = "android",
                )
            },
            readPayload = { segment, file ->
                val dir = spoolDir.resolve(segment.day).resolve(segment.stream).resolve(segment.dirSegment)
                Files.readAllBytes(dir.resolve(file.name))
            },
            readStoredZone = { segment -> readStoredZoneFor(spoolDir.toFile(), segment) },
            now = { 2000L },
            log = { _, _ -> },
            finisher = finisher,
        )

        // Assert that reconciliation did not drop the watch bundle and both were uploaded
        val ingestRequests = client.requests.filter { it.path.endsWith("/ingest") }
        assertEquals(2, ingestRequests.size, "Expected 2 ingest requests: one for phone and one for watch")

        val phoneBody = ingestRequests[0].body?.decodeToString().orEmpty()
        val watchBody = ingestRequests[1].body?.decodeToString().orEmpty()

        // Literal checks for phone upload
        assertTrue(phoneBody.contains("\"source\":\"audio\""), "Phone upload must have source 'audio'")
        assertTrue(phoneBody.contains("\"day\":\"20261109\""))
        assertTrue(phoneBody.contains("\"segment\":\"100000_300\""))
        assertTrue(phoneBody.contains("\"platform\":\"android\""), "Phone envelope platform must be 'android'")
        assertTrue(ingestRequests[0].body?.let { bytes ->
            val idx = bytes.indexOf(11)
            idx >= 0 && bytes[idx + 1] == 22.toByte() && bytes[idx + 2] == 33.toByte() && bytes[idx + 3] == 44.toByte()
        } == true, "Phone body must contain literal phone bytes")

        // Literal checks for watch upload
        println("WATCH BODY: $watchBody")
        assertTrue(watchBody.contains("\"source\":\"watch-audio\""), "Watch upload must have source 'watch-audio'")
        assertTrue(watchBody.contains("\"day\":\"20261109\""))
        assertTrue(watchBody.contains("\"segment\":\"100000_300\""))
        assertTrue(watchBody.contains("\"tz\":\"America/Denver\""))
        assertTrue(watchBody.contains("\"utc_offset_seconds\":-25200"))
        assertTrue(watchBody.contains("\"platform\":\"android\""), "Phone envelope platform must remain 'android'")
        assertTrue(ingestRequests[1].body?.let { bytes ->
            val idx = bytes.indexOf(55)
            idx >= 0 && bytes[idx + 1] == 66.toByte() && bytes[idx + 2] == 77.toByte() && bytes[idx + 3] == 88.toByte()
        } == true, "Watch body must contain literal watch bytes")
    }

    @Test
    fun locationRoundTripAndDeniedLocationOmission() {
        val root = Files.createTempDirectory("watch-location-round-trip")
        val spoolDir = root.resolve("spool")
        val stagingDir = root.resolve("staging")
        Files.createDirectories(spoolDir)
        Files.createDirectories(stagingDir)

        val dao = InMemorySegmentDao()
        val publisher = WatchCustodyPublisher(spoolDir, stagingDir, dao) { 1000L }

        // 1. With location fix
        val fix = WatchLocationFix(
            provider = "gps",
            timestampEpochMs = 1794243960000L,
            lat = 39.7392,
            lon = -104.9903,
            accuracyMeters = 3.5,
            fixAgeMs = 120L,
        )
        val expectedLocationString =
            "{\"schema\":\"solstone.location.segment/1\",\"platform\":\"wearos\"}\n" +
                "{\"provider\":\"gps\",\"timestamp\":1794243960000,\"lat\":39.7392,\"lon\":-104.9903,\"accuracy\":3.5,\"fixAge\":120}\n"
        val locationBytes = WatchLocationRecord.buildLocationRecord(fix).toByteArray(Charsets.UTF_8)
        val audioBytes = byteArrayOf(1, 2, 3)

        val headerWithLoc = CourierBundleHeader(
            protocolVersion = WatchCourierProtocol.PROTOCOL_VERSION,
            bundleId = "watch-bundle-with-loc",
            day = "20261109",
            segment = "100500_300",
            startEpochMs = Instant.parse("2026-11-09T17:05:00Z").toEpochMilli(),
            endEpochMs = Instant.parse("2026-11-09T17:10:00Z").toEpochMilli(),
            zoneId = "America/Denver",
            utcOffsetSeconds = -25200,
            sealGeneration = 1L,
        )
        val filesWithLoc = listOf(
            CourierFilePayload(
                name = WatchCourierProtocol.FILE_NAME_AUDIO,
                mediaType = WatchCourierProtocol.MEDIA_TYPE_AUDIO,
                sha256 = WatchCourierCodec.sha256Hex(audioBytes),
                byteSize = audioBytes.size.toLong(),
                bytes = audioBytes,
            ),
            CourierFilePayload(
                name = WatchCourierProtocol.FILE_NAME_LOCATION,
                mediaType = WatchCourierProtocol.MEDIA_TYPE_LOCATION,
                sha256 = WatchCourierCodec.sha256Hex(locationBytes),
                byteSize = locationBytes.size.toLong(),
                bytes = locationBytes,
            ),
        )

        val baosWithLoc = ByteArrayOutputStream()
        WatchCourierCodec.encodeBundle(headerWithLoc, filesWithLoc, baosWithLoc)
        assertTrue(publisher.publish(ByteArrayInputStream(baosWithLoc.toByteArray())) is PublishBundleResult.Ack)

        // 2. Denied location: audio only, no location file
        val headerNoLoc = CourierBundleHeader(
            protocolVersion = WatchCourierProtocol.PROTOCOL_VERSION,
            bundleId = "watch-bundle-denied-loc",
            day = "20261109",
            segment = "101000_300",
            startEpochMs = Instant.parse("2026-11-09T17:10:00Z").toEpochMilli(),
            endEpochMs = Instant.parse("2026-11-09T17:15:00Z").toEpochMilli(),
            zoneId = "America/Denver",
            utcOffsetSeconds = -25200,
            sealGeneration = 1L,
        )
        val filesNoLoc = listOf(
            CourierFilePayload(
                name = WatchCourierProtocol.FILE_NAME_AUDIO,
                mediaType = WatchCourierProtocol.MEDIA_TYPE_AUDIO,
                sha256 = WatchCourierCodec.sha256Hex(audioBytes),
                byteSize = audioBytes.size.toLong(),
                bytes = audioBytes,
            ),
        )
        val baosNoLoc = ByteArrayOutputStream()
        WatchCourierCodec.encodeBundle(headerNoLoc, filesNoLoc, baosNoLoc)
        assertTrue(publisher.publish(ByteArrayInputStream(baosNoLoc.toByteArray())) is PublishBundleResult.Ack)

        val client = RecordingPlHttpClient(
            uploadRequiredSegments(),
            ingestAcceptedFor(filesWithLoc),
            ingestAcceptedFor(filesNoLoc),
        )

        val store = RoomDrainStore(dao)
        val finisher = ConfirmedCopyFinisher(spoolRoot = spoolDir, dao = dao)

        drainSegments(
            store = store,
            reconcile = SegmentReconciler(client)::diff,
            ingest = { manifest, fileBytes ->
                ObserverIngestClient(client) { "boundary456" }.ingest(
                    manifest = manifest,
                    fileBytes = fileBytes,
                    host = "test-phone",
                    platform = "android",
                )
            },
            readPayload = { segment, file ->
                val dir = spoolDir.resolve(segment.day).resolve(segment.stream).resolve(segment.dirSegment)
                Files.readAllBytes(dir.resolve(file.name))
            },
            readStoredZone = { segment -> readStoredZoneFor(spoolDir.toFile(), segment) },
            now = { 3000L },
            log = { _, _ -> },
            finisher = finisher,
        )

        val ingestRequests = client.requests.filter { it.path.endsWith("/ingest") }
        assertEquals(2, ingestRequests.size)

        // First ingest has location
        val withLocBody = ingestRequests[0].body?.decodeToString().orEmpty()
        assertTrue(withLocBody.contains("location.jsonl"))
        assertTrue(withLocBody.contains(expectedLocationString))
        assertTrue(withLocBody.contains("\"timestamp\":1794243960000"))
        assertTrue(withLocBody.contains("\"lat\":39.7392"))
        assertTrue(withLocBody.contains("\"lon\":-104.9903"))

        // Second ingest (denied location) has audio only, no location line
        val noLocBody = ingestRequests[1].body?.decodeToString().orEmpty()
        assertTrue(noLocBody.contains("audio.m4a"))
        assertFalse(noLocBody.contains("location.jsonl"))
        assertFalse(noLocBody.contains("solstone.location.segment/1"))
        assertFalse(noLocBody.contains("wearos"))
    }

    private fun ByteArray.indexOf(b: Byte): Int {
        for (i in indices) {
            if (this[i] == b) return i
        }
        return -1
    }
}
