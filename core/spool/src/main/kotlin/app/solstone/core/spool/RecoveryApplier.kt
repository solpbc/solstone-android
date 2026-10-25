// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.spool

import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * A recovery event the container used to drop, when that event did not already write a gap row.
 * An empty draft adds its scanner line beside the row. A path is not logged.
 */
fun emitDroppedRecoveryEvent(
    event: SpoolRecoveryEvent,
    emit: (kind: String, source: String, reason: String, material: String) -> Unit,
) {
    val detail = event.detail ?: return
    if ('/' in detail || '\\' in detail) return
    if (event.kind == "finalized_segment") return
    if (detail.startsWith("invalid payload")) return
    emit("diagnostic", "spool", detail, "draft")
}

fun applyRecoveryActions(
    actions: List<RecoveryAction>,
    gaps: GapNoticeStore,
    unknownPaths: MutableCollection<String>,
    filesDir: Path,
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
                        rememberUnknown(filesDir, action.draftDir, unknownPaths)
                    }
                    return@forEach
                }
                val leaf = action.finalDir.fileName.toString()
                Files.createDirectories(action.finalDir.parent)
                try {
                    Files.move(action.draftDir, action.finalDir, StandardCopyOption.ATOMIC_MOVE)
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(action.draftDir, action.finalDir)
                }
                cleanupEmptyDraftParents(action.draftDir)
                gaps.recordDiagnostic("spool", "delivered", leaf, action.parsedManifest.endEpochMs)
                events += SpoolRecoveryEvent(
                    kind = "finalized_segment",
                    atEpochMs = action.parsedManifest.endEpochMs,
                    detail = leaf,
                )
            }
            is RecoveryAction.Discard -> {
                val detail = action.event.detail.orEmpty()
                when {
                    recoveryContent(action.draftDir) == RecoveryContent.EMPTY -> {
                        gaps.recordDiagnostic("spool", "empty draft", action.draftDir.fileName.toString(), null)
                        action.draftDir.deleteRecursively()
                        cleanupEmptyDraftParents(action.draftDir)
                        events += action.event
                    }
                    detail.startsWith("invalid payload") -> {
                        if (discardInvalidPayload(action.draftDir, gaps)) {
                            action.draftDir.deleteRecursively()
                            cleanupEmptyDraftParents(action.draftDir)
                            events += action.event
                        }
                    }
                    else -> rememberUnknown(filesDir, action.draftDir, unknownPaths)
                }
            }
        }
    }
    return events
}

private fun discardInvalidPayload(dir: Path, gaps: GapNoticeStore): Boolean {
    val parsed = runCatching {
        parseManifest(String(Files.readAllBytes(dir.resolve("manifest")), StandardCharsets.UTF_8))
    }.getOrNull() ?: return false
    val files = parsed.manifest.files
    if (files.isEmpty()) return false
    val leaf = dir.fileName.toString()
    var everyLoss = true
    for ((sourceId, entries) in files.groupBy { it.sourceId }) {
        val source = gapSourceFor(sourceId)
        val captured = entries.firstNotNullOfOrNull { it.captureStartEpochMs.takeIf { start -> start > 0L } }
        val existed = entries.any { entry -> draftPayloadExisted(dir, entry.name) }
        if (existed) {
            if (!gaps.recordLoss(source, "invalid payload", leaf, captured)) everyLoss = false
        } else {
            gaps.recordDiagnostic(source, "no bytes", leaf, captured)
        }
    }
    return everyLoss
}

private fun draftPayloadExisted(dir: Path, name: String): Boolean {
    val file = dir.resolve(name)
    if (!Files.isRegularFile(file)) return false
    return if (name == "capture.adts") adtsFileExisted(file) else payloadBytesExisted(Files.size(file))
}

private fun rememberUnknown(filesDir: Path, draftDir: Path, unknownPaths: MutableCollection<String>) {
    val relative = runCatching { filesDir.relativize(draftDir) }.getOrNull() ?: return
    if (relative.isAbsolute) return
    val text = relative.toString().replace('\\', '/')
    val parts = text.split('/')
    if (text.isEmpty() || parts.any { it.isEmpty() || it == "." || it == ".." }) return
    unknownPaths += text
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
