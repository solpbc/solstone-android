// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.persistence.room

import app.solstone.core.model.BundleFile
import app.solstone.core.model.QueueState
import app.solstone.core.segment.SealedSegment
import app.solstone.core.spool.SealResult
import app.solstone.core.spool.SealedSegmentSink

class RoomSealedSegmentSink(private val dao: SegmentDao) : SealedSegmentSink {
    override fun persistSealed(segment: SealedSegment, result: SealResult, sealedAtEpochMs: Long) {
        val segmentId = segment.id()
        dao.insertSegmentWithFiles(
            segment = SegmentRow(
                id = segmentId,
                day = segment.key.day,
                stream = segment.stream,
                segment = segment.key.segment,
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

fun SealedSegment.id(): String = "${key.day}/$stream/${key.segment}"
