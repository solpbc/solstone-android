// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.work

import app.solstone.core.model.QueueState
import app.solstone.core.model.SegmentKey
import app.solstone.core.model.WireKeys
import app.solstone.core.segment.SealedSegment
import app.solstone.core.segment.SegmentPayload
import app.solstone.core.sources.PayloadRef
import app.solstone.core.sources.WATCH_STREAM
import app.solstone.core.spool.FileSpoolWriter
import app.solstone.core.spool.PayloadBytesProvider
import app.solstone.core.watch.courier.CodecResult
import app.solstone.core.watch.courier.DecodedBundle
import app.solstone.core.watch.courier.WatchBundleReceipt
import app.solstone.core.watch.courier.WatchCourierCodec
import app.solstone.core.watch.courier.WatchCourierProtocol
import app.solstone.platform.persistence.room.SegmentDao
import app.solstone.platform.persistence.room.SegmentDao.WatchBundleCustodyResult
import app.solstone.platform.persistence.room.SegmentFileRow
import app.solstone.platform.persistence.room.SegmentRow
import app.solstone.platform.persistence.room.WatchBundleReceiptRow
import app.solstone.platform.persistence.room.occupiedDirSegments
import app.solstone.platform.persistence.room.segmentRowId
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path

sealed interface PublishBundleResult {
    data class Ack(val receipt: WatchBundleReceipt) : PublishBundleResult
    data class Conflict(val reason: String) : PublishBundleResult
    data class VersionMismatch(val version: Int) : PublishBundleResult
    data class Rejected(val reason: String) : PublishBundleResult
}

class WatchCustodyPublisher(
    private val spoolDir: Path,
    private val stagingDir: Path,
    private val dao: SegmentDao,
    private val nowProvider: () -> Long = System::currentTimeMillis,
) {
    fun publish(
        bundleInput: InputStream,
        onFirstCommit: () -> Unit = {},
    ): PublishBundleResult {
        val decodedResult = WatchCourierCodec.decodeBundle(bundleInput)
        val decoded = when (decodedResult) {
            is CodecResult.Success -> decodedResult.value
            is CodecResult.VersionMismatch -> return PublishBundleResult.VersionMismatch(decodedResult.version)
            is CodecResult.Malformed -> return PublishBundleResult.Rejected("Malformed bundle: ${decodedResult.reason}")
        }
        return publishDecoded(decoded, onFirstCommit)
    }

    fun publishDecoded(
        decoded: DecodedBundle,
        onFirstCommit: () -> Unit = {},
    ): PublishBundleResult {
        val header = decoded.header
        val bundleSha = decoded.bundleSha256

        // Fast-path duplicate check before disk staging
        val existing = dao.receiptByBundleId(header.bundleId)
        if (existing != null) {
            return if (existing.sha256.equals(bundleSha, ignoreCase = true)) {
                PublishBundleResult.Ack(
                    WatchBundleReceipt(
                        protocolVersion = header.protocolVersion,
                        bundleId = header.bundleId,
                        sha256 = bundleSha,
                        sealGeneration = header.sealGeneration,
                        segmentId = existing.segmentId,
                        committedAt = existing.committedAt,
                    ),
                )
            } else {
                PublishBundleResult.Conflict("Existing bundle ${header.bundleId} carries differing SHA-256")
            }
        }

        // Stage files outside the spool tree
        Files.createDirectories(stagingDir)
        val bundleStagingDir = Files.createTempDirectory(stagingDir, "stage-${header.bundleId}-")
        try {
            val filePayloadMap = decoded.files.associateBy { it.name }
            for (file in decoded.files) {
                val stagedFile = bundleStagingDir.resolve(file.name)
                Files.write(stagedFile, file.bytes)
            }

            val wireKeys = WireKeys(
                day = header.day,
                segment = header.segment,
                startEpochMs = header.startEpochMs,
                endEpochMs = header.endEpochMs,
                zoneId = header.zoneId,
                utcOffsetSeconds = header.utcOffsetSeconds,
            )

            val payloads = decoded.files.map { file ->
                SegmentPayload(
                    sourceId = WatchCourierProtocol.SOURCE_ID_WATCH_AUDIO,
                    ref = PayloadRef(
                        name = file.name,
                        mediaType = file.mediaType,
                        byteSize = file.byteSize,
                        sha256 = file.sha256,
                    ),
                    captureStartEpochMs = header.startEpochMs,
                    captureEndEpochMs = header.endEpochMs,
                )
            }

            val sealedSegment = SealedSegment(
                stream = WATCH_STREAM,
                key = SegmentKey(header.day, header.segment),
                wireKeys = wireKeys,
                payloads = payloads,
                gaps = emptyList(),
            )

            val spoolWriter = FileSpoolWriter(
                baseDir = spoolDir,
                occupiedLeaves = { day, stream -> dao.occupiedDirSegments(day, stream) },
            )

            val provider = object : PayloadBytesProvider {
                override fun open(payload: SegmentPayload): InputStream {
                    val file = filePayloadMap[payload.ref.name] ?: error("Payload missing: ${payload.ref.name}")
                    return ByteArrayInputStream(file.bytes)
                }

                override fun release(payload: SegmentPayload) {}
            }

            val sealResult = spoolWriter.seal(sealedSegment, provider)
            val dirSegment = File(sealResult.directory.toString()).name
            val segmentId = segmentRowId(header.day, WATCH_STREAM, dirSegment)
            val committedAt = nowProvider()

            val segmentRow = SegmentRow(
                id = segmentId,
                day = header.day,
                stream = WATCH_STREAM,
                segment = header.segment,
                dirSegment = dirSegment,
                state = QueueState.SEALED,
                byteSize = decoded.files.sumOf { it.byteSize },
                sealedAt = header.startEpochMs,
                homeInstanceId = null,
                observerHandle = null,
            )

            val fileRows = decoded.files.map { file ->
                SegmentFileRow(
                    segmentId = segmentId,
                    sourceId = WatchCourierProtocol.SOURCE_ID_WATCH_AUDIO,
                    name = file.name,
                    sha256 = file.sha256,
                    byteSize = file.byteSize,
                    mediaType = file.mediaType,
                    captureStartEpochMs = header.startEpochMs,
                    captureEndEpochMs = header.endEpochMs,
                )
            }

            val receiptRow = WatchBundleReceiptRow(
                bundleId = header.bundleId,
                sha256 = bundleSha,
                sealGeneration = header.sealGeneration,
                segmentId = segmentId,
                committedAt = committedAt,
            )

            val txResult = dao.insertWatchBundleReceiptTransaction(receiptRow, segmentRow, fileRows)
            val receipt = WatchBundleReceipt(
                protocolVersion = header.protocolVersion,
                bundleId = header.bundleId,
                sha256 = bundleSha,
                sealGeneration = header.sealGeneration,
                segmentId = segmentId,
                committedAt = committedAt,
            )

            return when (txResult) {
                WatchBundleCustodyResult.INSERTED -> {
                    onFirstCommit()
                    PublishBundleResult.Ack(receipt)
                }
                WatchBundleCustodyResult.DUPLICATE_SAME_SHA -> {
                    PublishBundleResult.Ack(receipt)
                }
                WatchBundleCustodyResult.CONFLICT_DIFFERENT_SHA -> {
                    PublishBundleResult.Conflict("Conflict: existing bundle with different sha")
                }
            }
        } finally {
            bundleStagingDir.toFile().deleteRecursively()
        }
    }
}
