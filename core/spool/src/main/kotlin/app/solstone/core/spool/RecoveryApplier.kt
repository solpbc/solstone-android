// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.spool

import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

fun applyRecoveryActions(actions: List<RecoveryAction>): List<SpoolRecoveryEvent> {
    val events = mutableListOf<SpoolRecoveryEvent>()
    actions.forEach { action ->
        when (action) {
            is RecoveryAction.Finalize -> {
                if (Files.exists(action.finalDir)) {
                    action.draftDir.deleteRecursively()
                    cleanupEmptyDraftParents(action.draftDir)
                    events += SpoolRecoveryEvent(
                        kind = "partial_segment",
                        atEpochMs = action.parsedManifest.endEpochMs,
                        detail = "final already exists",
                    )
                    return@forEach
                }
                Files.createDirectories(action.finalDir.parent)
                try {
                    Files.move(action.draftDir, action.finalDir, StandardCopyOption.ATOMIC_MOVE)
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(action.draftDir, action.finalDir)
                }
                cleanupEmptyDraftParents(action.draftDir)
                events += SpoolRecoveryEvent(
                    kind = "finalized_segment",
                    atEpochMs = action.parsedManifest.endEpochMs,
                    detail = action.finalDir.toString(),
                )
            }
            is RecoveryAction.Discard -> {
                action.draftDir.deleteRecursively()
                cleanupEmptyDraftParents(action.draftDir)
                events += action.event
            }
        }
    }
    return events
}

private fun cleanupEmptyDraftParents(draftDir: Path) {
    var current: Path? = draftDir.parent
    repeat(3) {
        val dir = current ?: return
        if (Files.exists(dir) && Files.isDirectory(dir) && Files.list(dir).use { !it.findAny().isPresent }) {
            Files.delete(dir)
        }
        current = dir.parent
    }
}

private fun Path.deleteRecursively() {
    if (!Files.exists(this)) return
    Files.walk(this).use { paths ->
        paths.sorted(Comparator.reverseOrder()).forEach(Files::delete)
    }
}
