// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.spool

import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

fun applyRecoveryActions(
    actions: List<RecoveryAction>,
    interruption: UnresolvedInterruption,
    otherInterruption: UnresolvedInterruption,
    unknownInterruption: UnresolvedInterruption,
): List<SpoolRecoveryEvent> {
    val events = mutableListOf<SpoolRecoveryEvent>()
    actions.forEach { action ->
        when (action) {
            is RecoveryAction.Finalize -> {
                if (Files.exists(action.finalDir)) {
                    val draftManifest = action.parsedManifest
                    val finalManifestPath = action.finalDir.resolve("manifest")
                    val finalParsed = if (Files.isRegularFile(finalManifestPath)) {
                        runCatching {
                            parseManifest(String(Files.readAllBytes(finalManifestPath), StandardCharsets.UTF_8))
                        }.getOrNull()
                    } else {
                        null
                    }

                    val equal = finalParsed != null &&
                        draftManifest.startEpochMs == finalParsed.startEpochMs &&
                        draftManifest.endEpochMs == finalParsed.endEpochMs &&
                        draftManifest.zoneId == finalParsed.zoneId &&
                        draftManifest.utcOffsetSeconds == finalParsed.utcOffsetSeconds &&
                        draftManifest.manifest.files.size == finalParsed.manifest.files.size &&
                        draftManifest.manifest.files.all { df ->
                            val draftFile = action.draftDir.resolve(df.name)
                            val finalFile = action.finalDir.resolve(df.name)
                            Files.isRegularFile(draftFile) &&
                                Files.isRegularFile(finalFile) &&
                                Files.size(draftFile) == Files.size(finalFile) &&
                                app.solstone.core.segment.sha256(draftFile) == app.solstone.core.segment.sha256(finalFile)
                        }

                    if (equal) {
                        action.draftDir.deleteRecursively()
                        cleanupEmptyDraftParents(action.draftDir)
                        events += SpoolRecoveryEvent(
                            kind = "partial_segment",
                            atEpochMs = action.parsedManifest.endEpochMs,
                            detail = "final already exists",
                        )
                    } else {
                        if (markRecoveryEvidence(action.draftDir, interruption, otherInterruption, unknownInterruption)) {
                            events += SpoolRecoveryEvent(
                                kind = "partial_segment",
                                atEpochMs = action.parsedManifest.endEpochMs,
                                detail = "final already exists",
                            )
                        }
                    }
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
                val content = recoveryContent(action.draftDir)
                if (content == RecoveryContent.EMPTY ||
                    markRecoveryEvidence(action.draftDir, interruption, otherInterruption, unknownInterruption)
                ) {
                    if (content == RecoveryContent.UNKNOWN) {
                        events += action.event
                        return@forEach
                    }
                    action.draftDir.deleteRecursively()
                    cleanupEmptyDraftParents(action.draftDir)
                    events += action.event
                }
            }
        }
    }
    return events
}

private enum class RecoveryContent { AUDIO, OTHER, UNKNOWN, EMPTY }

private fun recoveryContent(dir: Path): RecoveryContent {
    val manifest = dir.resolve("manifest")
    val parsed = runCatching {
        parseManifest(String(Files.readAllBytes(manifest), StandardCharsets.UTF_8))
    }.getOrNull()
    if (parsed != null && parsed.manifest.files.isNotEmpty()) {
        return if (parsed.manifest.files.any {
                it.sourceId == "audio" || it.mediaType.startsWith("audio/") || it.name == "audio.m4a"
            }) RecoveryContent.AUDIO else RecoveryContent.OTHER
    }
    val empty = runCatching {
        Files.list(dir).use { children ->
            children.allMatch { child ->
                parsed != null && child.fileName.toString() == "manifest"
            }
        }
    }.getOrDefault(false)
    return if (empty) RecoveryContent.EMPTY else RecoveryContent.UNKNOWN
}

private fun markRecoveryEvidence(
    dir: Path,
    audio: UnresolvedInterruption,
    other: UnresolvedInterruption,
    unknown: UnresolvedInterruption,
): Boolean = when (recoveryContent(dir)) {
    RecoveryContent.AUDIO -> audio.markUnresolved()
    RecoveryContent.OTHER -> other.markUnresolved()
    RecoveryContent.UNKNOWN -> unknown.markUnresolved()
    RecoveryContent.EMPTY -> true
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
