// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.persistence.room

import app.solstone.core.model.QueueState
import app.solstone.core.spool.parseManifest
import java.io.File
import java.nio.file.Path

class SpoolRoomReconciler(
    baseDir: Path,
    private val dao: SegmentDao,
) {
    private val baseDirFile = File(baseDir.toString())

    fun reconcile(): Int {
        if (!baseDirFile.exists()) return 0
        var inserted = 0
        baseDirFile.walkTopDown()
            .filter { file -> file.isFile }
            .filter { file -> file.name == "manifest" }
            .filter { file -> !file.relativeParts().contains(".draft") }
            .forEach { manifestFile ->
                if (insertManifest(manifestFile)) inserted += 1
        }
        return inserted
    }

    private fun insertManifest(manifestFile: File): Boolean {
        val segmentDir = manifestFile.parentFile ?: return false
        val streamDir = segmentDir.parentFile ?: return false
        val dayDir = streamDir.parentFile ?: return false
        if (dayDir.parentFile != baseDirFile) return false
        val parsed = parseManifest(manifestFile.readText(Charsets.UTF_8))
        val stream = streamDir.name
        val dirSegment = segmentDir.name
        val segmentId = "${parsed.manifest.key.day}/$stream/$dirSegment"
        if (dao.segmentById(segmentId) != null) return false
        dao.insertSegmentWithFiles(
            SegmentRow(
                id = segmentId,
                day = parsed.manifest.key.day,
                stream = stream,
                segment = parsed.manifest.key.segment,
                dirSegment = dirSegment,
                state = QueueState.SEALED,
                byteSize = parsed.manifest.files.sumOf { it.byteSize },
                sealedAt = parsed.endEpochMs,
                homeInstanceId = null,
                observerHandle = null,
            ),
            parsed.manifest.files.map { file ->
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
            },
        )
        return true
    }

    private fun File.relativeParts(): List<String> =
        relativeTo(baseDirFile).invariantSeparatorsPath.split('/')
}
