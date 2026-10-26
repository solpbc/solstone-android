// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.spool

import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
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

/** One listener failure does not skip the events after it. */
fun emitDroppedRecoveryEvents(
    events: List<SpoolRecoveryEvent>,
    emit: (kind: String, source: String, reason: String, material: String) -> Unit,
) {
    events.forEach { event ->
        runCatching { emitDroppedRecoveryEvent(event, emit) }
    }
}

fun applyRecoveryActions(
    actions: List<RecoveryAction>,
    gaps: GapNoticeStore?,
    unknownPaths: MutableCollection<String>,
    filesDir: Path,
): List<SpoolRecoveryEvent> {
    val events = mutableListOf<SpoolRecoveryEvent>()
    actions.forEach { action ->
        when (action) {
            is RecoveryAction.Finalize -> {
                val draftManifest = action.parsedManifest
                if (!draftInventoryMatches(action.draftDir, draftManifest)) {
                    rememberUnknown(filesDir, action.draftDir, unknownPaths)
                    events += SpoolRecoveryEvent("partial_segment", draftManifest.endEpochMs, "kept unknown")
                    return@forEach
                }
                if (Files.exists(action.finalDir)) {
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
                        draftManifest.manifest.files == finalParsed.manifest.files &&
                        wholeDraftMatchesSealed(action.draftDir, action.finalDir, finalParsed)

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
                        events += SpoolRecoveryEvent(
                            kind = "partial_segment",
                            atEpochMs = action.parsedManifest.endEpochMs,
                            detail = "divergent final",
                        )
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
                gaps?.recordDiagnostic(
                    "spool",
                    "delivered",
                    gapMaterialForDraft(action.finalDir),
                    action.parsedManifest.endEpochMs,
                )
                events += SpoolRecoveryEvent(
                    kind = "finalized_segment",
                    atEpochMs = action.parsedManifest.endEpochMs,
                    detail = leaf,
                )
            }
            is RecoveryAction.Discard -> {
                val detail = action.event.detail.orEmpty()
                when {
                    (detail == "missing manifest" || detail == "malformed manifest") &&
                        isSealedResidue(action.draftDir) -> {
                        action.draftDir.deleteRecursively()
                        cleanupEmptyDraftParents(action.draftDir)
                    }
                    recoveryContent(action.draftDir) == RecoveryContent.EMPTY -> {
                        gaps?.recordDiagnostic(
                            "spool",
                            "empty draft",
                            gapMaterialForDraft(action.draftDir),
                            null,
                        )
                        action.draftDir.deleteRecursively()
                        cleanupEmptyDraftParents(action.draftDir)
                        events += action.event
                    }
                    detail.startsWith("invalid payload") -> {
                        if (discardInvalidPayload(action.draftDir, gaps)) {
                            action.draftDir.deleteRecursively()
                            cleanupEmptyDraftParents(action.draftDir)
                            events += action.event
                        } else {
                            rememberUnknown(filesDir, action.draftDir, unknownPaths)
                        }
                    }
                    else -> {
                        rememberUnknown(filesDir, action.draftDir, unknownPaths)
                        events += SpoolRecoveryEvent(
                            kind = action.event.kind,
                            atEpochMs = action.event.atEpochMs,
                            detail = "kept unknown",
                        )
                    }
                }
            }
            is RecoveryAction.Parked -> retireParked(action, gaps, unknownPaths, filesDir, events)
        }
    }
    return events
}

/**
 * A parked draft is an unknown item until a pass can record it. It is never sealed.
 * Payload bytes are a loss. An empty draft is a diagnostic. The folder is removed only when that
 * record succeeds.
 */
private fun retireParked(
    action: RecoveryAction.Parked,
    gaps: GapNoticeStore?,
    unknownPaths: MutableCollection<String>,
    filesDir: Path,
    events: MutableList<SpoolRecoveryEvent>,
) {
    val dir = action.draftDir
    val material = parkedMaterial(dir)
    val recorded = if (directoryHasPayloadBytes(dir)) {
        gaps?.recordLoss("spool", "undeliverable", material, parkedCaptureStart(dir)) == true
    } else {
        gaps?.recordDiagnostic("spool", "empty draft", material, null) == true
    }
    if (recorded) {
        dir.deleteRecursively()
        cleanupEmptyDraftParents(dir)
        return
    }
    rememberUnknown(filesDir, dir, unknownPaths)
    events += SpoolRecoveryEvent(
        kind = "partial_segment",
        atEpochMs = action.atEpochMs,
        detail = "kept unknown",
    )
}

private val PARKED_SUFFIX = Regex("""\.kept-\d+$""")

private fun parkedMaterial(dir: Path): String {
    val folder = dir.fileName?.toString().orEmpty().replace(PARKED_SUFFIX, "")
    val stream = dir.parent?.fileName?.toString().orEmpty()
    val day = dir.parent?.parent?.fileName?.toString().orEmpty()
    return gapDraftMaterial(day, stream, folder)
}

private fun parkedCaptureStart(dir: Path): Long? {
    val manifest = dir.resolve("manifest")
    if (!Files.isRegularFile(manifest)) return null
    return runCatching {
        parseManifest(String(Files.readAllBytes(manifest), StandardCharsets.UTF_8)).startEpochMs
    }.getOrNull()
}

private fun discardInvalidPayload(dir: Path, gaps: GapNoticeStore?): Boolean {
    val parsed = runCatching {
        parseManifest(String(Files.readAllBytes(dir.resolve("manifest")), StandardCharsets.UTF_8))
    }.getOrNull() ?: return false
    if (!draftInventoryMatches(dir, parsed)) return false
    val files = parsed.manifest.files
    if (files.isEmpty()) return false
    val material = gapMaterialForDraft(dir)
    var everyLoss = true
    for ((sourceId, entries) in files.groupBy { it.sourceId }) {
        val source = gapSourceFor(sourceId)
        val captured = entries.firstNotNullOfOrNull { it.captureStartEpochMs.takeIf { start -> start > 0L } }
        val existed = entries.any { entry -> draftPayloadExisted(dir, entry.name) }
        if (existed) {
            if (gaps?.recordLoss(source, "invalid payload", material, captured) != true) everyLoss = false
        } else {
            gaps?.recordDiagnostic(source, "no bytes", material, captured)
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

/** Only a complete, verified same-coordinate sealed copy can retire unknown material. */
private fun isSealedResidue(draft: Path): Boolean = runCatching {
    val stream = draft.parent ?: return false
    val day = stream.parent ?: return false
    val drafts = day.parent ?: return false
    if (drafts.fileName.toString() != ".draft") return false
    val root = drafts.parent ?: return false
    val sealed = root.resolve(day.fileName).resolve(stream.fileName).resolve(draft.fileName)
    val manifest = sealed.resolve("manifest")
    if (!Files.isRegularFile(manifest, NOFOLLOW_LINKS)) return false
    val parsed = parseManifest(String(Files.readAllBytes(manifest), StandardCharsets.UTF_8))
    parsed.manifest.key.day == day.fileName.toString() &&
        parsed.manifest.key.segment == draft.fileName.toString() &&
        wholeDraftMatchesSealed(draft, sealed, parsed)
}.getOrDefault(false)

private fun draftInventoryMatches(draft: Path, parsed: ParsedManifest): Boolean = runCatching {
    if (!Files.isDirectory(draft, NOFOLLOW_LINKS)) return false
    val entries = parsed.manifest.files
    val names = entries.map { it.name }.toSet()
    if (names.size != entries.size || names.any { it.isEmpty() || it == "manifest" || it == "." || it == ".." || '/' in it || '\\' in it }) return false
    val children = Files.list(draft).use { it.toArray().map { child -> child as Path } }
    if (children.any { !Files.isRegularFile(it, NOFOLLOW_LINKS) }) return false
    children.map { it.fileName.toString() }.filter { it != "manifest" }.toSet() == names
}.getOrDefault(false)

private fun wholeDraftMatchesSealed(draft: Path, sealed: Path, parsed: ParsedManifest): Boolean = runCatching {
    if (!draftInventoryMatches(draft, parsed) || !Files.isDirectory(sealed, NOFOLLOW_LINKS)) return false
    parsed.manifest.files.all { entry ->
        val original = draft.resolve(entry.name)
        val copy = sealed.resolve(entry.name)
        Files.isRegularFile(copy, NOFOLLOW_LINKS) &&
            Files.size(original) == entry.byteSize && Files.size(copy) == entry.byteSize &&
            app.solstone.core.segment.sha256(original) == entry.sha256 &&
            app.solstone.core.segment.sha256(copy) == entry.sha256
    }
}.getOrDefault(false)

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
