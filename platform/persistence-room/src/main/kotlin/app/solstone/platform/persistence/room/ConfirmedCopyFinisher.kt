// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.persistence.room

import app.solstone.core.model.QueueState
import app.solstone.core.queue.QueueEvent
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

const val MAX_RESIDUAL_REMOVAL_ATTEMPTS_PER_PASS = 32

class ConfirmedCopyFinisher(
    private val spoolRoot: Path,
    private val dao: SegmentDao,
    private val directoryRemover: SpoolDirectoryRemover = NioSpoolDirectoryRemover(),
    private val log: (String) -> Unit = {},
) {
    fun confirmationReady(row: SegmentRow): Boolean {
        val proof = proveSegmentDirectory(spoolRoot, row)
        return proof is SegmentDirectoryProof.Proven && proveManifestIdentity(proof, row) == null
    }

    fun finishUploaded(id: String) {
        val row = dao.segmentById(id) ?: return
        when (row.state) {
            QueueState.EVICTED -> {
                val proof = proveSegmentDirectory(spoolRoot, row)
                if (proof !is SegmentDirectoryProof.Proven) return
                removeDirectory(id, proof.path)
            }
            QueueState.UPLOADED -> {
                val proof = proveSegmentDirectory(spoolRoot, row)
                if (proof is SegmentDirectoryProof.Refused) {
                    if (proof.reason == JournalCachePathRefusal.MISSING_DIRECTORY) {
                        dao.advanceState(id, QueueEvent.FINISH)
                    }
                    return
                }
                proof as SegmentDirectoryProof.Proven
                if (proveManifestIdentity(proof, row) != null) return
                dao.advanceState(id, QueueEvent.FINISH)
                removeDirectory(id, proof.path)
            }
            else -> return
        }
    }

    fun finishPass() {
        var residualAttempts = 0
        dao.segmentsByState(QueueState.EVICTED).forEach { row ->
            val proof = proveSegmentDirectory(spoolRoot, row)
            if (proof is SegmentDirectoryProof.Refused) {
                return@forEach
            }
            proof as SegmentDirectoryProof.Proven
            if (residualAttempts >= MAX_RESIDUAL_REMOVAL_ATTEMPTS_PER_PASS) {
                return@forEach
            }
            residualAttempts += 1
            removeDirectory(row.id, proof.path)
        }

        dao.segmentsByState(QueueState.UPLOADED).forEach { row ->
            finishUploaded(row.id)
        }

        dao.segmentsByState(QueueState.FAILED).forEach { row ->
            if (row.lastError != "removed_in_journal") return@forEach
            val proof = proveSegmentDirectory(spoolRoot, row)
            if (proof is SegmentDirectoryProof.Refused) {
                if (proof.reason == JournalCachePathRefusal.MISSING_DIRECTORY) {
                    dao.finishLegacyRemovedInJournal(row.id)
                }
                return@forEach
            }
            proof as SegmentDirectoryProof.Proven
            if (proveManifestIdentity(proof, row) != null) return@forEach
            if (dao.finishLegacyRemovedInJournal(row.id)) {
                removeDirectory(row.id, proof.path)
            }
        }
    }

    private fun removeDirectory(id: String, path: Path) {
        val result = directoryRemover.remove(path)
        if (result != DirectoryRemovalResult.ConfirmedAbsent || Files.exists(path, NOFOLLOW_LINKS)) {
            log("confirmed copy removal incomplete $id")
        }
    }
}
