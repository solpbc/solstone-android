// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.persistence.room

import app.solstone.core.model.BundleFile
import app.solstone.core.model.QueueState
import app.solstone.core.segment.SealedSegment
import app.solstone.core.spool.SealResult
import app.solstone.core.spool.SealedSegmentSink
import java.io.File

class RoomSealedSegmentSink(private val dao: SegmentDao) : SealedSegmentSink {
    override fun persistSealed(segment: SealedSegment, result: SealResult, sealedAtEpochMs: Long) {
        val dirSegment = result.directory?.let { File(it.toString()).name } ?: segment.key.segment
        val segmentId = segment.id(dirSegment)
        dao.insertSegmentWithFiles(
            segment = SegmentRow(
                id = segmentId,
                day = segment.key.day,
                stream = segment.stream,
                segment = segment.key.segment,
                dirSegment = dirSegment,
                state = QueueState.SEALED,
                byteSize = result.manifest.files.sumOf { it.byteSize },
                sealedAt = sealedAtEpochMs,
                homeInstanceId = null,
                observerHandle = null,
            ),
            files = result.manifest.files.map { it.toRow(segmentId) },
        )
    }

    private fun BundleFile.toRow(segmentId: String): SegmentFileRow =
        SegmentFileRow(
            segmentId = segmentId,
            sourceId = sourceId,
            name = name,
            sha256 = sha256,
            byteSize = byteSize,
            mediaType = mediaType,
            captureStartEpochMs = captureStartEpochMs,
            captureEndEpochMs = captureEndEpochMs,
        )
}

fun SealedSegment.id(dirSegment: String): String = segmentRowId(key.day, stream, dirSegment)

fun segmentRowId(day: String, stream: String, dirSegment: String): String = "$day/$stream/$dirSegment"

/**
 * Whether a segment row already holds this directory leaf. A row outlives its directory once the
 * journal confirms the copy, so a later segment sealed under the same wire key must take another
 * leaf rather than reuse the row's id.
 */
fun SegmentDao.isLeafOccupied(day: String, stream: String, leaf: String): Boolean =
    segmentById(segmentRowId(day, stream, leaf)) != null
