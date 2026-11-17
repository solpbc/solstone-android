// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.foldersync

import app.solstone.core.model.QueueState
import app.solstone.core.segment.SealedSegment
import app.solstone.core.segment.SegmentPayload
import app.solstone.core.spool.FileSpoolWriter
import app.solstone.core.spool.PayloadBytesProvider
import app.solstone.core.spool.SealState
import app.solstone.platform.persistence.room.RoomSealedSegmentSink
import app.solstone.platform.persistence.room.SegmentDao
import app.solstone.platform.persistence.room.SegmentRow
import app.solstone.platform.persistence.room.id
import app.solstone.platform.persistence.room.occupiedDirSegments
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path

/** Hands one source payload to the ordinary spool and reads its delivery receipt. */
class FolderDelivery(private val spool: Path, private val dao: SegmentDao) {
    fun seal(segment: SealedSegment, bytes: ByteArray): String? {
        val result = FileSpoolWriter(spool, occupiedLeaves = dao::occupiedDirSegments).seal(
            segment,
            object : PayloadBytesProvider {
                override fun open(payload: SegmentPayload) = ByteArrayInputStream(bytes)
            },
        )
        if (result.state != SealState.SEALED) return null
        RoomSealedSegmentSink(dao).persistSealed(segment, result, System.currentTimeMillis())
        return segment.id(result.directory?.fileName?.toString() ?: segment.key.segment)
    }

    fun receipt(segmentId: String): SegmentRow? = dao.segmentById(segmentId)

    fun receipt(day: String, stream: String, name: String): SegmentRow? =
        dao.segmentsByDay(day).firstOrNull { row ->
            row.stream == stream && dao.filesBySegmentId(row.id).any { it.name == name }
        }

    fun hasPayload(row: SegmentRow, name: String): Boolean =
        Files.isRegularFile(spool.resolve(row.day).resolve(row.stream).resolve(row.dirSegment).resolve(name))
}

fun SegmentRow?.journalHasSegment(): Boolean = this?.state == QueueState.UPLOADED || this?.state == QueueState.EVICTED
