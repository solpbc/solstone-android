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
    fun confirmationReady(row: SegmentRow): Boolean = confirmationRefusal(row) == null

    /** Why [row]'s local directory cannot be proven to be its own copy, or null when it can. */
    fun confirmationRefusal(row: SegmentRow): JournalCachePathRefusal? =
        when (val proof = proveSegmentDirectory(spoolRoot, row)) {
            is SegmentDirectoryProof.Refused -> proof.reason
            is SegmentDirectoryProof.Proven -> proveManifestIdentity(proof, row)
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
                    } else {
                        log("confirmed copy refused $id ${proof.reason}")
                    }
                    return
                }
                proof as SegmentDirectoryProof.Proven
                val identityRefusal = proveManifestIdentity(proof, row)
                if (identityRefusal != null) {
                    log("confirmed copy refused $id $identityRefusal")
                    return
                }
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
                if (proof.reason != JournalCachePathRefusal.MISSING_DIRECTORY) {
                    log("confirmed copy refused ${row.id} ${proof.reason}")
                }
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
            finishRow(row.id) { finishUploaded(row.id) }
        }

        dao.segmentsByState(QueueState.FAILED).forEach { row ->
            if (row.lastError != "removed_in_journal") return@forEach
            finishRow(row.id) { finishLegacyRemoved(row) }
        }
    }

    private fun finishLegacyRemoved(row: SegmentRow) {
        val proof = proveSegmentDirectory(spoolRoot, row)
        if (proof is SegmentDirectoryProof.Refused) {
            if (proof.reason == JournalCachePathRefusal.MISSING_DIRECTORY) {
                dao.finishLegacyRemovedInJournal(row.id)
            } else {
                log("confirmed copy refused ${row.id} ${proof.reason}")
            }
            return
        }
        proof as SegmentDirectoryProof.Proven
        val identityRefusal = proveManifestIdentity(proof, row)
        if (identityRefusal != null) {
            log("confirmed copy refused ${row.id} $identityRefusal")
            return
        }
        if (dao.finishLegacyRemovedInJournal(row.id)) {
            removeDirectory(row.id, proof.path)
        }
    }

    // A database write that fails (a full disk raises SQLiteFullException) leaves this row for a
    // later pass; it must not stop the rest of the pass or the process.
    private inline fun finishRow(id: String, finish: () -> Unit) {
        try {
            finish()
        } catch (e: RuntimeException) {
            log("confirmed copy not finished $id ${e.javaClass.simpleName}")
        }
    }

    private fun removeDirectory(id: String, path: Path) {
        val result = directoryRemover.remove(path)
        if (result != DirectoryRemovalResult.ConfirmedAbsent || Files.exists(path, NOFOLLOW_LINKS)) {
            log("confirmed copy removal incomplete $id")
        }
    }
}
