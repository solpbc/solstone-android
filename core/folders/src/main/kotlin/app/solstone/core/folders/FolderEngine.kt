// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.folders

import java.io.File
import java.util.TreeSet

private const val SETTLE_MS = 2_000L
private val RETRY_MS = longArrayOf(30_000L, 120_000L, 300_000L, 900_000L, 1_800_000L)

sealed class BeginSetupResult {
    data class Started(val folderId: String, val overlap: OverlapTarget?) : BeginSetupResult()
    data class AlreadyAdded(val existingId: String, val existingName: String) : BeginSetupResult()
}

sealed class ConfirmResult {
    data object Ok : ConfirmResult()
    data object NotFound : ConfirmResult()
    data object NoKindsSelected : ConfirmResult()
    data object Unreadable : ConfirmResult()
}

private fun FolderRecord.applyKindChoices() {
    val entries = pending.listIterator()
    while (entries.hasNext()) {
        val event = entries.next()
        if (kinds.enabledFor(event.kind)) continue
        if (kinds.other) entries.set(event.copy(delivery = DeliveryMode.METADATA_ONLY, contentHash = null, notePayload = null))
        else entries.remove()
    }
    if (!kinds.other) files.replaceAll { _, file ->
        if (kinds.enabledFor(file.kind)) file else file.copy(pendingRevisionSaveMs = null, pendingRevisionHash = null)
    }
}

/**
 * Multi-folder state machine and durable ledger.
 *
 * Pure JVM, zero Android imports. Handles:
 * - setup preview (zero uploads before explicit confirm)
 * - 5-minute window grouping (`HHMMSS_300`), newest-first delivery
 * - note edit coalescing in-window with revert cancellation via net line diff
 * - 1-hour document/transcript revision cool-down
 * - rename detection (document ID or size+SHA-256)
 * - delete-before-send (drops unsent items cleanly; emits `removed` only after delivery)
 * - incomplete-listing protection (never deletes on an incomplete walk)
 * - 63-part chunking per segment upload
 * - `refused_files` fallback to details-only (`part: null`)
 * - pause without skipping (changes queue while paused and deliver on resume)
 * - A new pairing keeps the granted folder and its waiting changes, and restarts setup.
 */
class FolderEngine(
    private val ledgerFile: File,
    private val fs: FolderFileSystem,
    private val clock: FolderClock = SystemFolderClock,
) {
    private val folders: MutableList<FolderRecord> = FolderLedgerStore.load(ledgerFile).mapTo(ArrayList()) { it.copy(name = boundedFolderName(it.name)).also { record -> record.applyKindChoices() } }
    var supported: SupportedList = SupportedList()
        private set

    fun updateSupported(list: SupportedList) {
        if (supported == list) return
        supported = list
        var changed = false
        for (folder in folders) {
            val entries = folder.pending.listIterator()
            while (entries.hasNext()) {
                val event = entries.next()
                if (event.kind != FileKind.OTHER || (event.verb != ActivityVerb.ADDED && event.verb != ActivityVerb.EDITED)) continue
                val ext = event.metadata.extension ?: continue
                if (ext !in list.documentExtensions && ext !in list.transcriptExtensions) continue
                val meta = fs.stat(folder.treeUri, event.documentId, event.relPath) ?: continue
                if (meta.sizeBytes != event.metadata.sizeBytes || meta.modifiedMs != event.metadata.modifiedMs) continue
                val bytes = if (ext in list.transcriptExtensions && meta.sizeBytes <= list.maxNoteBytes) {
                    fs.readBytes(folder.treeUri, event.documentId, event.relPath, list.maxNoteBytes)
                } else null
                val (kind, delivery) = classifyFile(ext, meta.sizeBytes, false, bytes, folder.kinds, list)
                if (delivery != DeliveryMode.CONTENT) continue
                val hash = bytes?.let(::sha256Hex) ?: fs.sha256Hex(folder.treeUri, event.documentId, event.relPath, list.maxContentBytes) ?: continue
                entries.set(event.copy(kind = kind, delivery = delivery, contentHash = hash))
                folder.files[event.relPath]?.let { folder.files[event.relPath] = it.copy(kind = kind, delivery = delivery, contentHash = hash) }
                changed = true
            }
        }
        if (changed) save()
    }

    fun folderRecords(): List<FolderRecord> = folders.toList()

    fun folderById(folderId: String): FolderRecord? = folders.firstOrNull { it.id == folderId }

    fun save(): Boolean = FolderLedgerStore.save(ledgerFile, folders)

    fun beginSetup(
        treeUri: String,
        displayName: String,
        label: String,
        canonicalPath: String?,
        obsidian: FolderIdentity? = null,
        screenshots: FolderIdentity? = null,
    ): BeginSetupResult {
        val existingIds = folders.map {
            FolderIdentity(it.id, it.treeUri, it.canonicalPath, it.name)
        }
        val overlapOutcome = detectOverlap(
            candidateTreeUri = treeUri,
            candidatePath = canonicalPath,
            obsidian = obsidian,
            screenshots = screenshots,
            existingFolders = existingIds,
        )
        if (overlapOutcome is OverlapOutcome.AlreadyAdded) {
            return BeginSetupResult.AlreadyAdded(overlapOutcome.id, overlapOutcome.name)
        }
        val overlap = (overlapOutcome as? OverlapOutcome.Overlap)?.target
        val stableKey = normalizePathForOverlap(canonicalPath) ?: treeUri
        var candidateId = makeFolderId(displayName, stableKey)
        if (folders.any { it.id == candidateId }) {
            candidateId = makeFolderId(displayName, "$stableKey#${folders.size + 1}")
        }
        val record = FolderRecord(
            id = candidateId,
            treeUri = treeUri,
            name = boundedFolderName(displayName),
            label = label.trim().ifBlank { displayName },
            canonicalPath = normalizePathForOverlap(canonicalPath),
            mode = FolderMode.SETTING_UP,
            returning = false,
            kinds = FolderKinds(),
            facet = null,
            overlap = overlap,
            selectedSpan = FolderSpan.LAST_30_DAYS,
        )
        folders.add(record)
        save()
        return BeginSetupResult.Started(record.id, overlap)
    }

    fun setKinds(folderId: String, kinds: FolderKinds): Boolean {
        if (!kinds.anySelected()) return false
        val idx = folders.indexOfFirst { it.id == folderId }
        if (idx < 0) return false
        folders[idx] = folders[idx].copy(kinds = kinds).also { it.applyKindChoices() }
        save()
        return true
    }

    fun setFacet(folderId: String, facet: FacetOption?): Boolean {
        val idx = folders.indexOfFirst { it.id == folderId }
        if (idx < 0) return false
        folders[idx] = folders[idx].copy(facet = facet)
        save()
        return true
    }

    fun setSelectedSpan(folderId: String, span: FolderSpan): Boolean {
        val idx = folders.indexOfFirst { it.id == folderId }
        if (idx < 0) return false
        folders[idx] = folders[idx].copy(selectedSpan = span)
        save()
        return true
    }

    fun regrantAccess(folderId: String, newTreeUri: String, newName: String? = null, newLabel: String? = null): Boolean {
        val idx = folders.indexOfFirst { it.id == folderId }
        if (idx < 0) return false
        val current = folders[idx]
        folders[idx] = current.copy(
            treeUri = newTreeUri,
            name = newName?.trim()?.takeIf(String::isNotBlank)?.take(128) ?: current.name,
            label = newLabel?.trim()?.takeIf(String::isNotBlank) ?: current.label,
            attention = null,
        )
        save()
        return true
    }

    /**
     * Scans a folder to compute a preview summary + capped `EstimateItem` list without
     * queueing any uploads.
     */
    fun previewFolderWithItems(
        folderId: String,
        span: FolderSpan,
    ): Pair<PreviewSummary, List<EstimateItem>>? {
        val idx = folders.indexOfFirst { it.id == folderId }
        if (idx < 0) return null
        val folder = folders[idx]
        val alreadyDelivered = folder.files.values
            .filter { it.deliveredToJournal }
            .map { it.relPath }
            .toHashSet()

        val nowStamp = clock.now()
        val fromMs = spanStartMs(span, nowStamp.epochMs, clock.zoneAt(nowStamp.epochMs))
        val walk = fs.walk(folder.treeUri)
        if (walk !is WalkOutcome.Ok) {
            val failed = PreviewSummary(span = span, failed = true)
            folders[idx] = folder.copy(selectedSpan = span, preview = failed)
            return failed to emptyList()
        }

        var totalItems = 0L
        var notes = 0L
        var documents = 0L
        var transcripts = 0L
        var screenshots = 0L
        var photos = 0L
        var other = 0L
        var contentItems = 0L
        var metadataOnlyItems = 0L
        var contentBytes = 0L
        var oldestMs: Long? = null
        var newestMs: Long? = null
        val windows = TreeSet<FolderWindow>()
        val days = LinkedHashSet<String>()
        var contentThoughts = 0L
        val estimateItems = ArrayList<EstimateItem>()

        for (file in walk.files) {
            if (file.metadata.modifiedMs < fromMs) continue
            if (file.relPath in alreadyDelivered) continue

            val noteBytes = if (needsClassificationBytes(file.metadata.extension, supported) &&
                file.sizeBytes <= supported.maxNoteBytes
            ) {
                fs.readBytes(folder.treeUri, file.documentId, file.relPath, supported.maxNoteBytes)
            } else {
                null
            }
            val (kind, delivery) = classifyFile(
                extension = file.metadata.extension,
                sizeBytes = file.sizeBytes,
                isScreenshot = file.isScreenshot,
                noteBytes = noteBytes,
                kinds = folder.kinds,
                supported = supported,
            )
            if (delivery == DeliveryMode.IGNORE) continue

            totalItems++
            when (delivery) {
                DeliveryMode.CONTENT -> {
                    contentItems++
                    contentBytes += file.sizeBytes
                    contentThoughts++
                    when (kind) {
                        FileKind.NOTE -> notes++
                        FileKind.DOCUMENT -> documents++
                        FileKind.TRANSCRIPT -> transcripts++
                        FileKind.SCREENSHOT -> screenshots++
                        FileKind.PHOTO -> photos++
                        FileKind.OTHER -> other++
                    }
                }
                DeliveryMode.METADATA_ONLY -> {
                    metadataOnlyItems++
                    other++
                }
                DeliveryMode.IGNORE -> Unit
            }

            val clampedMs = minOf(file.metadata.modifiedMs, nowStamp.epochMs)
            oldestMs = if (oldestMs == null) clampedMs else minOf(oldestMs, clampedMs)
            newestMs = if (newestMs == null) clampedMs else maxOf(newestMs, clampedMs)
            val evStamp = clock.stampAt(clampedMs)
            val w = evStamp.window()
            days.add(w.day)
            windows.add(w)

            if (estimateItems.size < MAX_ESTIMATE_ITEMS) {
                val hasContent = delivery == DeliveryMode.CONTENT
                val contentHash = if (hasContent) {
                    noteBytes?.let(::sha256Hex)
                } else {
                    null
                }
                estimateItems.add(
                    EstimateItem(
                        path = file.relPath,
                        at = evStamp.rfc3339(),
                        fileType = file.metadata.extension?.takeIf {
                            it.isNotEmpty() && it.length <= 64 && it.none { c -> c.code < 0x20 || c.code == 0x7F }
                        },
                        kindHint = if (hasContent) kind.kindHint else null,
                        contentHash = contentHash,
                        hasContent = hasContent,
                    ),
                )
            }
        }

        val summary = PreviewSummary(
            span = span,
            totalItems = totalItems,
            notes = notes,
            documents = documents,
            transcripts = transcripts,
            screenshots = screenshots,
            photos = photos,
            other = other,
            contentItems = contentItems,
            metadataOnlyItems = metadataOnlyItems,
            contentBytes = contentBytes,
            unreadable = walk.unreadable,
            oldestMs = oldestMs,
            newestMs = newestMs,
            estimate = BackfillEstimate(
                segments = windows.size.toLong(),
                days = days.size.toLong(),
                thoughts = contentThoughts,
                alreadyInJournal = 0L,
                fromJournal = false,
            ),
            failed = false,
        )
        folders[idx] = folder.copy(selectedSpan = span, preview = summary)
        return summary to estimateItems
    }

    fun setJournalEstimate(folderId: String, span: FolderSpan, estimate: BackfillEstimate) {
        val idx = folders.indexOfFirst { it.id == folderId }
        if (idx < 0) return
        val preview = folders[idx].preview ?: return
        if (preview.span != span) return
        folders[idx] = folders[idx].copy(preview = preview.copy(estimate = estimate))
    }

    /**
     * Confirms setup or `bring in older files` for one folder. Transitions to [FolderMode.ON]
     * and queues eligible files within [span] without duplicating delivered items.
     */
    fun confirmFolder(folderId: String, span: FolderSpan): ConfirmResult {
        val idx = folders.indexOfFirst { it.id == folderId }
        if (idx < 0) return ConfirmResult.NotFound
        val folder = folders[idx]
        if (!folder.kinds.anySelected()) return ConfirmResult.NoKindsSelected

        val nowStamp = clock.now()
        val fromMs = spanStartMs(span, nowStamp.epochMs, clock.zoneAt(nowStamp.epochMs))
        val walk = fs.walk(folder.treeUri)
        if (walk !is WalkOutcome.Ok) {
            val att = when (walk) {
                WalkOutcome.PermissionLost -> FolderAttention.PERMISSION_LOST
                WalkOutcome.Missing -> FolderAttention.MISSING
                else -> FolderAttention.UNREADABLE
            }
            folders[idx] = folder.copy(attention = att, lastCheckedMs = nowStamp.epochMs)
            save()
            return ConfirmResult.Unreadable
        }

        val updated = folder.copy(
            mode = if (folder.pauseDuringSetup) FolderMode.PAUSED else FolderMode.ON,
            pauseDuringSetup = false,
            returning = true,
            attention = null,
            selectedSpan = span,
            lastCheckedMs = nowStamp.epochMs,
            cantRead = walk.unreadable,
            spanFromMs = folder.spanFromMs?.let { minOf(it, fromMs) } ?: fromMs,
        )

        val waitingPaths = updated.pending.mapTo(HashSet()) { it.relPath }
        var queuedNow = 0L
        for (file in walk.files) {
            if (file.relPath in waitingPaths) continue
            val existing = updated.files[file.relPath]
            if (existing?.deliveredToJournal == true || (existing?.pendingRevisionSaveMs != null && existing.sizeBytes == file.sizeBytes && existing.modifiedMs == file.modifiedMs)) continue

            if (file.metadata.modifiedMs < fromMs) {
                if (existing == null) {
                    updated.files[file.relPath] = TrackedFile(
                        relPath = file.relPath,
                        documentId = file.documentId,
                        sizeBytes = file.sizeBytes,
                        modifiedMs = file.modifiedMs,
                        contentHash = null,
                        kind = FileKind.OTHER,
                        delivery = DeliveryMode.IGNORE,
                        deliveredToJournal = false,
                        excludedBeforeSpan = true,
                    )
                }
                continue
            }

            val noteBytes = if (needsClassificationBytes(file.metadata.extension, supported) &&
                file.sizeBytes <= supported.maxNoteBytes
            ) {
                fs.readBytes(updated.treeUri, file.documentId, file.relPath, supported.maxNoteBytes)
            } else {
                null
            }
            val (kind, delivery) = classifyFile(
                extension = file.metadata.extension,
                sizeBytes = file.sizeBytes,
                isScreenshot = file.isScreenshot,
                noteBytes = noteBytes,
                kinds = updated.kinds,
                supported = supported,
            )
            if (delivery == DeliveryMode.IGNORE) continue

            val contentHash = if (delivery == DeliveryMode.CONTENT) {
                noteBytes?.let(::sha256Hex)
                    ?: fs.sha256Hex(updated.treeUri, file.documentId, file.relPath, supported.maxContentBytes)
            } else {
                null
            }
            val noteText = if (kind == FileKind.NOTE && delivery == DeliveryMode.CONTENT) {
                noteBytes?.let(::decodeStrictUtf8)
            } else {
                null
            }

            val evMs = minOf(file.metadata.modifiedMs, nowStamp.epochMs)
            val evStamp = clock.stampAt(evMs)
            val window = evStamp.window()
            val windowEndMs = evStamp.windowEndMs()
            val isBackfill = windowEndMs <= nowStamp.epochMs
            val eventId = eventIdFor(updated.id, file.relPath, ActivityVerb.ADDED, evMs)

            updated.pending.removeAll { it.relPath == file.relPath }
            updated.pending.add(
                PendingEvent(
                    id = eventId,
                    relPath = file.relPath,
                    documentId = file.documentId,
                    verb = ActivityVerb.ADDED,
                    kind = kind,
                    delivery = delivery,
                    eventMs = evMs,
                    observedMs = nowStamp.epochMs,
                    offsetSeconds = evStamp.offsetSeconds,
                    window = window,
                    windowEndMs = windowEndMs,
                    metadata = file.metadata,
                    contentHash = contentHash,
                    notePayload = noteText,
                    isRevision = false,
                    backfill = isBackfill,
                ),
            )
            updated.files[file.relPath] = TrackedFile(
                relPath = file.relPath,
                documentId = file.documentId,
                sizeBytes = file.sizeBytes,
                modifiedMs = file.modifiedMs,
                contentHash = contentHash,
                kind = kind,
                delivery = delivery,
                deliveredToJournal = false,
                excludedBeforeSpan = false,
            )
            queuedNow++
        }

        val finalRecord = if (queuedNow > 0L) {
            updated.copy(backfillTotal = updated.backfillDone + queuedNow)
        } else {
            updated
        }
        folders[idx] = finalRecord
        save()
        return ConfirmResult.Ok
    }

    /** Pause sync without skipping changes (changes still scan and wait for resume). */
    fun pauseFolder(folderId: String): Boolean {
        val idx = folders.indexOfFirst { it.id == folderId }
        if (idx < 0 || folders[idx].mode != FolderMode.ON) return false
        folders[idx] = folders[idx].copy(mode = FolderMode.PAUSED)
        save()
        return true
    }

    fun resumeFolder(folderId: String): Boolean {
        val idx = folders.indexOfFirst { it.id == folderId }
        if (idx < 0 || folders[idx].mode != FolderMode.PAUSED) return false
        folders[idx] = folders[idx].copy(mode = FolderMode.ON)
        save()
        return true
    }

    /** Stops watching and drops unsent queue entries; keeps delivered markers so re-enabling won't duplicate. */
    fun turnOffFolder(folderId: String): Boolean {
        val idx = folders.indexOfFirst { it.id == folderId }
        if (idx < 0) return false
        val current = folders[idx]
        current.pending.clear()
        current.files.replaceAll { _, file -> file.copy(pendingRevisionSaveMs = null, pendingRevisionHash = null) }
        folders[idx] = current.copy(
            mode = FolderMode.OFF,
            pauseDuringSetup = false,
            backfillTotal = 0L,
            backfillDone = 0L,
            attention = null,
        )
        save()
        return true
    }

    fun turnOnFolder(folderId: String): Boolean {
        val idx = folders.indexOfFirst { it.id == folderId }
        if (idx < 0) return false
        folders[idx] = folders[idx].copy(mode = FolderMode.SETTING_UP, returning = true)
        save()
        return true
    }

    /** Removes the folder from the app and returns its removed tree URI (if any). */
    fun removeFolder(folderId: String): String? {
        val idx = folders.indexOfFirst { it.id == folderId }
        if (idx < 0) return null
        val removed = folders.removeAt(idx)
        save()
        return removed.treeUri
    }

    /**
     * Pairing Principle 9: a new journal pairing returns active/paused folders to setup while
     * preserving granted folders, switches, and already queued changes.
     */
    fun restartSetup(): Boolean {
        for (i in folders.indices) {
            val f = folders[i]
            val nextMode = if (f.mode == FolderMode.ON || f.mode == FolderMode.PAUSED) {
                FolderMode.SETTING_UP
            } else {
                f.mode
            }
            val resetFiles = LinkedHashMap<String, TrackedFile>(f.files.size)
            for ((path, tracked) in f.files) {
                resetFiles[path] = tracked.copy(
                    deliveredToJournal = false,
                    lastSentHash = null,
                    lastNoteText = null,
                )
            }
            folders[i] = f.copy(
                mode = nextMode,
                pauseDuringSetup = f.mode == FolderMode.PAUSED || f.pauseDuringSetup,
                returning = if (nextMode == FolderMode.SETTING_UP) true else f.returning,
                backfillDone = 0L,
                backfillTotal = 0L,
                spanFromMs = null,
                preview = null,
                itemsTakenIn = 0L,
                lastTakenInMs = null,
                fallbackToDetails = 0L,
                files = resetFiles,
            )
        }
        return save()
    }

    /**
     * Scans every `ON` and `PAUSED` folder. Notice `PAUSED` folders still scan so changes
     * made while paused are tracked and delivered once resumed (never skipped).
     */
    fun scanAll(onlyFolderId: String? = null): Boolean {
        val nowMs = clock.nowMs()
        var dirty = false
        for (idx in folders.indices) {
            val f = folders[idx]
            if (onlyFolderId != null && f.id != onlyFolderId) continue
            if (f.mode == FolderMode.SETTING_UP) {
                if (releaseDueRevisions(f, nowMs)) dirty = true
                continue
            }
            if (f.mode != FolderMode.ON && f.mode != FolderMode.PAUSED) continue
            if (scanOne(idx, nowMs)) dirty = true
        }
        if (dirty) save()
        return dirty
    }

    private fun scanOne(idx: Int, nowMs: Long): Boolean {
        val folder = folders[idx]
        val walk = when (val result = fs.walk(folder.treeUri)) {
            is WalkOutcome.Ok -> {
                folders[idx] = folder.copy(
                    attention = null,
                    lastCheckedMs = nowMs,
                    cantRead = result.unreadable,
                )
                result
            }
            WalkOutcome.PermissionLost -> {
                folders[idx] = folder.copy(
                    attention = FolderAttention.PERMISSION_LOST,
                    lastCheckedMs = nowMs,
                )
                return true
            }
            WalkOutcome.Missing -> {
                folders[idx] = folder.copy(
                    attention = FolderAttention.MISSING,
                    lastCheckedMs = nowMs,
                )
                return true
            }
            WalkOutcome.Unreadable -> {
                folders[idx] = folder.copy(
                    attention = FolderAttention.UNREADABLE,
                    lastCheckedMs = nowMs,
                )
                return true
            }
        }

        val current = folders[idx]
        var changed = true // lastCheckedMs updated
        val seenPaths = HashSet<String>(walk.files.size)
        val newCandidates = ArrayList<WalkedFile>()
        val modifiedCandidates = ArrayList<WalkedFile>()

        for (file in walk.files) {
            seenPaths.add(file.relPath)
            if (nowMs - file.metadata.modifiedMs < SETTLE_MS) continue
            val tracked = current.files[file.relPath]
            if (tracked == null) {
                newCandidates.add(file)
            } else if (tracked.sizeBytes != file.sizeBytes || tracked.modifiedMs != file.modifiedMs) {
                modifiedCandidates.add(file)
            }
        }

        // Only a complete walk (with no unreadable directories) may treat
        // disappeared paths as renames or deletions. Unrelated partial
        // downloads (e.g. `.crdownload`) are excluded from the walk and
        // must not block rename/deletion reconciliation for other files.
        val disappearedPaths = if (walk.complete) {
            current.files.keys.filter { it !in seenPaths }
        } else {
            emptyList()
        }

        val matchedDisappeared = HashSet<String>()
        val remainingNew = ArrayList<WalkedFile>()
        val disappearedSizes = disappearedPaths.mapNotNull { current.files[it]?.sizeBytes }.toHashSet()

        for (cand in newCandidates) {
            var matchedOld: String? = null
            // 1. Document ID match (when provider keeps documentId stable across renames)
            if (cand.documentId != null) {
                for (oldRel in disappearedPaths) {
                    if (oldRel in matchedDisappeared) continue
                    val old = current.files[oldRel]
                    if (old?.documentId != null && old.documentId == cand.documentId) {
                        matchedOld = oldRel
                        break
                    }
                }
            }
            // 2. Same size + content SHA-256 match
            if (matchedOld == null && cand.sizeBytes in disappearedSizes) {
                val candHash = fs.sha256Hex(current.treeUri, cand.documentId, cand.relPath, supported.maxContentBytes)
                if (candHash != null) {
                    for (oldRel in disappearedPaths) {
                        if (oldRel in matchedDisappeared) continue
                        val old = current.files[oldRel]
                        if (old != null && old.sizeBytes == cand.sizeBytes && old.contentHash == candHash) {
                            matchedOld = oldRel
                            break
                        }
                    }
                }
            }

            if (matchedOld != null) {
                matchedDisappeared.add(matchedOld)
                val oldTracked = current.files.remove(matchedOld)
                if (oldTracked != null) {
                    val updatedTracked = oldTracked.copy(
                        relPath = cand.relPath,
                        documentId = cand.documentId,
                        sizeBytes = cand.sizeBytes,
                        modifiedMs = cand.modifiedMs,
                    )
                    if (!oldTracked.deliveredToJournal) {
                        for (i in current.pending.indices) {
                            if (current.pending[i].relPath == matchedOld) {
                                current.pending[i] = current.pending[i].copy(
                                    relPath = cand.relPath,
                                    documentId = cand.documentId,
                                )
                            }
                        }
                    } else {
                        val evStamp = clock.stampAt(nowMs)
                        current.pending.add(
                            PendingEvent(
                                id = eventIdFor(current.id, cand.relPath, ActivityVerb.RENAMED, nowMs),
                                relPath = cand.relPath,
                                documentId = cand.documentId,
                                previousPath = matchedOld,
                                verb = ActivityVerb.RENAMED,
                                kind = oldTracked.kind,
                                delivery = DeliveryMode.METADATA_ONLY,
                                eventMs = nowMs,
                                observedMs = nowMs,
                                offsetSeconds = evStamp.offsetSeconds,
                                window = evStamp.window(),
                                windowEndMs = evStamp.windowEndMs(),
                                metadata = cand.metadata,
                                contentHash = oldTracked.contentHash,
                            ),
                        )
                    }
                    current.files[cand.relPath] = updatedTracked
                    changed = true
                }
            } else {
                remainingNew.add(cand)
            }
        }

        // Delete-before-send & removed events ONLY on complete listing.
        if (walk.complete) {
            for (oldRel in disappearedPaths) {
                if (oldRel in matchedDisappeared) continue
                val old = current.files.remove(oldRel) ?: continue
                current.pending.removeAll { it.relPath == oldRel }
                if (!old.deliveredToJournal) {
                    // Delete before send: drop any waiting events cleanly; zero trace sent.
                    changed = true
                } else if (!old.excludedBeforeSpan) {
                    val evStamp = clock.stampAt(nowMs)
                    current.pending.add(
                        PendingEvent(
                            id = eventIdFor(current.id, oldRel, ActivityVerb.REMOVED, nowMs),
                            relPath = oldRel,
                            documentId = null,
                            verb = ActivityVerb.REMOVED,
                            kind = old.kind,
                            delivery = DeliveryMode.METADATA_ONLY,
                            eventMs = nowMs,
                            observedMs = nowMs,
                            offsetSeconds = evStamp.offsetSeconds,
                            window = evStamp.window(),
                            windowEndMs = evStamp.windowEndMs(),
                            metadata = PlatformMetadata(
                                sizeBytes = old.sizeBytes,
                                modifiedMs = nowMs,
                                extension = extensionLower(oldRel),
                            ),
                        ),
                    )
                    changed = true
                }
            }
        }

        // Newly appeared files
        for (file in remainingNew) {
            val noteBytes = if (needsClassificationBytes(file.metadata.extension, supported) &&
                file.sizeBytes <= supported.maxNoteBytes
            ) {
                fs.readBytes(current.treeUri, file.documentId, file.relPath, supported.maxNoteBytes)
            } else {
                null
            }
            val (kind, delivery) = classifyFile(
                extension = file.metadata.extension,
                sizeBytes = file.sizeBytes,
                isScreenshot = file.isScreenshot,
                noteBytes = noteBytes,
                kinds = current.kinds,
                supported = supported,
            )
            if (delivery == DeliveryMode.IGNORE) continue

            val contentHash = if (delivery == DeliveryMode.CONTENT) {
                noteBytes?.let(::sha256Hex)
                    ?: fs.sha256Hex(current.treeUri, file.documentId, file.relPath, supported.maxContentBytes)
            } else {
                null
            }
            val noteText = if (kind == FileKind.NOTE && delivery == DeliveryMode.CONTENT) {
                noteBytes?.let(::decodeStrictUtf8)
            } else {
                null
            }
            val evMs = minOf(file.metadata.modifiedMs, nowMs)
            val evStamp = clock.stampAt(evMs)
            current.pending.add(
                PendingEvent(
                    id = eventIdFor(current.id, file.relPath, ActivityVerb.ADDED, evMs),
                    relPath = file.relPath,
                    documentId = file.documentId,
                    verb = ActivityVerb.ADDED,
                    kind = kind,
                    delivery = delivery,
                    eventMs = evMs,
                    observedMs = nowMs,
                    offsetSeconds = evStamp.offsetSeconds,
                    window = evStamp.window(),
                    windowEndMs = evStamp.windowEndMs(),
                    metadata = file.metadata,
                    contentHash = contentHash,
                    notePayload = noteText,
                ),
            )
            current.files[file.relPath] = TrackedFile(
                relPath = file.relPath,
                documentId = file.documentId,
                sizeBytes = file.sizeBytes,
                modifiedMs = file.modifiedMs,
                contentHash = contentHash,
                kind = kind,
                delivery = delivery,
                deliveredToJournal = false,
                excludedBeforeSpan = false,
            )
            changed = true
        }

        // Modified files
        for (file in modifiedCandidates) {
            val noteBytes = if (needsClassificationBytes(file.metadata.extension, supported) &&
                file.sizeBytes <= supported.maxNoteBytes
            ) {
                fs.readBytes(current.treeUri, file.documentId, file.relPath, supported.maxNoteBytes)
            } else {
                null
            }
            val (kind, delivery) = classifyFile(
                extension = file.metadata.extension,
                sizeBytes = file.sizeBytes,
                isScreenshot = file.isScreenshot,
                noteBytes = noteBytes,
                kinds = current.kinds,
                supported = supported,
            )
            if (delivery == DeliveryMode.IGNORE) continue

            val newHash = if (delivery == DeliveryMode.CONTENT) {
                noteBytes?.let(::sha256Hex)
                    ?: fs.sha256Hex(current.treeUri, file.documentId, file.relPath, supported.maxContentBytes)
            } else {
                null
            }
            val oldTracked = current.files[file.relPath] ?: continue
            var updatedTracked = oldTracked.copy(
                documentId = file.documentId,
                sizeBytes = file.sizeBytes,
                modifiedMs = file.modifiedMs,
                kind = kind,
                delivery = delivery,
                excludedBeforeSpan = false,
            )
            if (newHash != null && newHash == oldTracked.contentHash) {
                current.files[file.relPath] = updatedTracked
                continue
            }
            updatedTracked = updatedTracked.copy(contentHash = newHash)

            val evMs = minOf(file.metadata.modifiedMs, nowMs)
            val evStamp = clock.stampAt(evMs)
            val evWindow = evStamp.window()
            val evWindowEnd = evStamp.windowEndMs()

            when {
                kind == FileKind.NOTE && delivery == DeliveryMode.CONTENT -> {
                    val currentText = noteBytes?.let(::decodeStrictUtf8)
                    val baseline = oldTracked.lastNoteText
                    if (baseline != null && currentText != null && netLineDiff(baseline, currentText) == null) {
                        // Reverted back to baseline before window closed: drop pending edit.
                        current.pending.removeAll { it.relPath == file.relPath && it.window == evWindow }
                        current.files[file.relPath] = updatedTracked
                        changed = true
                        continue
                    }
                    val verb = if (baseline != null) ActivityVerb.EDITED else ActivityVerb.ADDED
                    current.pending.removeAll { it.relPath == file.relPath && it.window == evWindow }
                    current.pending.add(
                        PendingEvent(
                            id = eventIdFor(current.id, file.relPath, verb, evMs),
                            relPath = file.relPath,
                            documentId = file.documentId,
                            verb = verb,
                            kind = FileKind.NOTE,
                            delivery = DeliveryMode.CONTENT,
                            eventMs = evMs,
                            observedMs = nowMs,
                            offsetSeconds = evStamp.offsetSeconds,
                            window = evWindow,
                            windowEndMs = evWindowEnd,
                            metadata = file.metadata,
                            contentHash = newHash,
                            notePayload = currentText,
                        ),
                    )
                    current.files[file.relPath] = updatedTracked
                    changed = true
                }
                (kind == FileKind.DOCUMENT || kind == FileKind.TRANSCRIPT) &&
                    delivery == DeliveryMode.CONTENT && oldTracked.deliveredToJournal -> {
                    // A return to the acknowledged bytes cancels the outstanding revision.
                    updatedTracked = updatedTracked.copy(
                        pendingRevisionSaveMs = evMs.takeIf { newHash != null && newHash != oldTracked.lastSentHash },
                        pendingRevisionHash = newHash.takeIf { it != oldTracked.lastSentHash },
                    )
                    current.pending.removeAll { it.relPath == file.relPath && it.isRevision }
                    current.files[file.relPath] = updatedTracked
                    changed = true
                }
                (kind == FileKind.DOCUMENT || kind == FileKind.TRANSCRIPT) &&
                    delivery == DeliveryMode.CONTENT && !oldTracked.deliveredToJournal -> {
                    current.pending.removeAll { it.relPath == file.relPath }
                    current.pending.add(
                        PendingEvent(
                            id = eventIdFor(current.id, file.relPath, ActivityVerb.ADDED, evMs),
                            relPath = file.relPath,
                            documentId = file.documentId,
                            verb = ActivityVerb.ADDED,
                            kind = kind,
                            delivery = DeliveryMode.CONTENT,
                            eventMs = evMs,
                            observedMs = nowMs,
                            offsetSeconds = evStamp.offsetSeconds,
                            window = evWindow,
                            windowEndMs = evWindowEnd,
                            metadata = file.metadata,
                            contentHash = newHash,
                        ),
                    )
                    current.files[file.relPath] = updatedTracked
                    changed = true
                }
                delivery == DeliveryMode.METADATA_ONLY -> {
                    current.pending.removeAll { it.relPath == file.relPath && it.window == evWindow }
                    current.pending.add(
                        PendingEvent(
                            id = eventIdFor(current.id, file.relPath, ActivityVerb.EDITED, evMs),
                            relPath = file.relPath,
                            documentId = file.documentId,
                            verb = ActivityVerb.EDITED,
                            kind = kind,
                            delivery = DeliveryMode.METADATA_ONLY,
                            eventMs = evMs,
                            observedMs = nowMs,
                            offsetSeconds = evStamp.offsetSeconds,
                            window = evWindow,
                            windowEndMs = evWindowEnd,
                            metadata = file.metadata,
                        ),
                    )
                    current.files[file.relPath] = updatedTracked
                    changed = true
                }
                else -> {
                    current.files[file.relPath] = updatedTracked
                }
            }
        }

        return releaseDueRevisions(current, nowMs) || changed
    }

    private fun releaseDueRevisions(current: FolderRecord, nowMs: Long): Boolean {
        var changed = false
        val revWaitMs = supported.documentRevisionCooldownMs
        val dueRevisions = current.files.values.filter { t ->
            val saveMs = t.pendingRevisionSaveMs
            saveMs != null && (nowMs - saveMs) >= revWaitMs
        }
        for (tracked in dueRevisions) {
            val saveMs = tracked.pendingRevisionSaveMs ?: continue
            val metadata = fs.stat(current.treeUri, tracked.documentId, tracked.relPath) ?: continue
            if (metadata.sizeBytes != tracked.sizeBytes || metadata.modifiedMs != tracked.modifiedMs) continue
            val evStamp = clock.stampAt(saveMs)
            current.pending.add(
                PendingEvent(
                    id = eventIdFor(current.id, tracked.relPath, ActivityVerb.REVISED, saveMs),
                    relPath = tracked.relPath,
                    documentId = tracked.documentId,
                    verb = ActivityVerb.REVISED,
                    kind = tracked.kind,
                    delivery = DeliveryMode.CONTENT,
                    eventMs = saveMs,
                    observedMs = nowMs,
                    offsetSeconds = evStamp.offsetSeconds,
                    window = evStamp.window(),
                    windowEndMs = evStamp.windowEndMs(),
                    metadata = metadata,
                    contentHash = tracked.pendingRevisionHash,
                    isRevision = true,
                ),
            )
            current.files[tracked.relPath] = tracked.copy(
                pendingRevisionSaveMs = null,
                pendingRevisionHash = null,
            )
            changed = true
        }

        if (changed) current.applyKindChoices()
        return changed
    }

    /**
     * Picks the next ready closed five-minute segment across all `ON` folders (newest window
     * first). Enforces the 63 companion-part cap and pre-send delete-before-send check.
     */
    fun nextDelivery(appPaused: Boolean): SegmentDelivery? {
        if (appPaused) return null
        val nowMs = clock.nowMs()
        var bestIdx = -1
        var bestWindow: FolderWindow? = null

        for (idx in folders.indices) {
            val folder = folders[idx]
            if (!folder.canDeliverPending() || folder.attention != null) continue
            for (ev in folder.pending) {
                if (ev.windowEndMs > nowMs) continue
                if (ev.retryAtMs != null && ev.retryAtMs > nowMs) continue
                if (bestWindow == null || ev.window > bestWindow) {
                    bestIdx = idx
                    bestWindow = ev.window
                }
            }
        }
        if (bestIdx < 0 || bestWindow == null) return null

        val folder = folders[bestIdx]
        val candidates = folder.pending
            .filter {
                it.window == bestWindow &&
                    it.windowEndMs <= nowMs &&
                    (it.retryAtMs == null || it.retryAtMs <= nowMs)
            }
            .sortedWith(compareBy<PendingEvent>({ it.eventMs }, { it.relPath }))

        val droppedIds = HashSet<String>()
        val selectedEvents = ArrayList<PendingEvent>(minOf(candidates.size, MAX_ACTIVITY_LINES))
        val stagedFiles = ArrayList<StagedContentFile>()

        for (ev in candidates) {
            if (selectedEvents.size >= MAX_ACTIVITY_LINES) break
            if (ev.verb != ActivityVerb.REMOVED) {
                when (fs.checkExists(folder.treeUri, ev.documentId, ev.relPath)) {
                    FileExistence.Present -> Unit
                    FileExistence.Unknown -> {
                        val index = folder.pending.indexOfFirst { it.id == ev.id }
                        if (index >= 0) folder.pending[index] = ev.copy(retryAtMs = nowMs + RETRY_MS.first())
                        continue
                    }
                    FileExistence.Missing -> {
                        droppedIds.add(ev.id)
                        // The next complete walk owns deletion reconciliation and the removed line.
                        continue
                    }
                }
            }
            if (ev.delivery != DeliveryMode.CONTENT) {
                selectedEvents.add(ev)
                continue
            }
            if (stagedFiles.size >= MAX_UPLOAD_CONTENT_PARTS) continue

            val ext = ev.metadata.extension.orEmpty()
            val needsImageConvert = (ev.kind == FileKind.SCREENSHOT || ev.kind == FileKind.PHOTO) &&
                ext in supported.convertibleImageExtensions
            stagedFiles.add(
                StagedContentFile(
                    eventId = ev.id,
                    relPath = ev.relPath,
                    documentId = ev.documentId,
                    kind = ev.kind,
                    contentHash = ev.contentHash,
                    notePayload = ev.notePayload,
                    isRevision = ev.isRevision,
                    needsImageConvert = needsImageConvert,
                ),
            )
            selectedEvents.add(ev)
        }

        if (droppedIds.isNotEmpty()) {
            folder.pending.removeAll { it.id in droppedIds }
            save()
        }
        if (selectedEvents.isEmpty()) return null

        return SegmentDelivery(
            folderId = folder.id,
            treeUri = folder.treeUri,
            folderName = folder.name,
            facet = folder.facet?.name,
            kinds = folder.kinds,
            window = bestWindow,
            events = selectedEvents,
            contentFiles = stagedFiles,
        )
    }

    fun recordOutcome(delivery: SegmentDelivery, outcome: DeliveryOutcome) {
        val nowMs = clock.nowMs()
        val idx = folders.indexOfFirst { it.id == delivery.folderId }
        if (idx < 0) return
        val folder = folders[idx]
        val ids = delivery.events.map { it.id }.toHashSet()

        when (outcome) {
            DeliveryOutcome.Delivered, is DeliveryOutcome.DeliveredWithRefusedContent -> {
                val refusedSet = (outcome as? DeliveryOutcome.DeliveredWithRefusedContent)
                    ?.refusedPaths?.toHashSet().orEmpty()
                var fallbackCount = folder.fallbackToDetails + refusedSet.size.toLong()
                var completedBackfill = 0L
                var itemsTaken = folder.itemsTakenIn
                var lastTaken = folder.lastTakenInMs
                val fallbackEvents = ArrayList<PendingEvent>()

                for (ev in delivery.events) {
                    if (ev.relPath in refusedSet && ev.delivery == DeliveryMode.CONTENT) {
                        fallbackEvents.add(
                            ev.copy(
                                delivery = DeliveryMode.METADATA_ONLY,
                                contentHash = null,
                                notePayload = null,
                                retryAtMs = null,
                            ),
                        )
                        continue
                    }
                    if (ev.backfill) completedBackfill++
                    itemsTaken++
                    lastTaken = nowMs
                    val tracked = folder.files[ev.relPath]
                    if (tracked != null) {
                        folder.files[ev.relPath] = tracked.copy(
                            deliveredToJournal = true,
                            lastSentHash = ev.contentHash ?: tracked.lastSentHash,
                            lastNoteText = if (ev.kind == FileKind.NOTE &&
                                ev.delivery == DeliveryMode.CONTENT &&
                                ev.notePayload != null
                            ) {
                                ev.notePayload
                            } else {
                                tracked.lastNoteText
                            },
                        )
                    }
                }
                folder.pending.removeAll { it.id in ids }
                folder.pending.addAll(fallbackEvents)

                var backfillDone = folder.backfillDone + completedBackfill
                var backfillTotal = folder.backfillTotal
                if (completedBackfill > 0L && folder.pending.none { it.backfill }) {
                    backfillDone = 0L
                    backfillTotal = 0L
                }
                folders[idx] = folder.copy(
                    itemsTakenIn = itemsTaken,
                    lastTakenInMs = lastTaken,
                    fallbackToDetails = fallbackCount,
                    backfillDone = backfillDone,
                    backfillTotal = backfillTotal,
                )
            }
            is DeliveryOutcome.AllFilesRefused -> {
                val refusedSet = outcome.refusedPaths.toHashSet()
                var converted = 0L
                for (i in folder.pending.indices) {
                    val ev = folder.pending[i]
                    if (ev.id !in ids) continue
                    if (ev.delivery == DeliveryMode.CONTENT &&
                        (refusedSet.isEmpty() || ev.relPath in refusedSet)
                    ) {
                        folder.pending[i] = ev.copy(
                            delivery = DeliveryMode.METADATA_ONLY,
                            contentHash = null,
                            notePayload = null,
                            retryAtMs = null,
                        )
                        converted++
                    }
                }
                if (converted > 0L) {
                    folders[idx] = folder.copy(fallbackToDetails = folder.fallbackToDetails + converted)
                } else {
                    // Already metadata-only and still refused: drop so it never loops.
                    folder.pending.removeAll { it.id in ids }
                }
            }
            DeliveryOutcome.InvalidPermanent -> {
                folder.pending.removeAll { it.id in ids }
            }
            DeliveryOutcome.Hold -> {
                for (i in folder.pending.indices) {
                    val ev = folder.pending[i]
                    if (ev.id !in ids) continue
                    val step = ev.tries.coerceAtMost(RETRY_MS.lastIndex)
                    folder.pending[i] = ev.copy(
                        tries = ev.tries + 1,
                        retryAtMs = nowMs + RETRY_MS[step],
                    )
                }
            }
        }
        save()
    }

    private fun FolderRecord.canDeliverPending(): Boolean =
        mode == FolderMode.ON || (mode == FolderMode.SETTING_UP && !pauseDuringSetup)

    fun pendingCount(): Int = folders.sumOf { f ->
        f.pending.size
    }

    fun hasReadyDelivery(appPaused: Boolean): Boolean {
        if (appPaused) return false
        val nowMs = clock.nowMs()
        return folders.any { folder ->
            folder.canDeliverPending() && folder.attention == null && folder.pending.any { ev ->
                ev.windowEndMs <= nowMs && (ev.retryAtMs == null || ev.retryAtMs <= nowMs)
            }
        }
    }

    fun summaries(appPaused: Boolean): List<FolderSummary> = folders.map { f ->
        FolderSummary(
            id = f.id,
            treeUri = f.treeUri,
            name = f.name,
            label = f.label,
            mode = f.mode,
            returning = f.returning,
            kinds = f.kinds,
            facet = f.facet,
            overlap = f.overlap,
            attention = f.attention,
            selectedSpan = f.selectedSpan,
            preview = f.preview,
            backfillDone = f.backfillDone,
            backfillTotal = f.backfillTotal,
            waiting = f.pending.size,
            itemsTakenIn = f.itemsTakenIn,
            lastTakenInMs = f.lastTakenInMs,
            lastCheckedMs = f.lastCheckedMs,
            cantRead = f.cantRead,
            fallbackToDetails = f.fallbackToDetails,
            pausedByApp = appPaused && f.mode == FolderMode.ON,
        )
    }

    private fun eventIdFor(
        folderId: String,
        relPath: String,
        verb: ActivityVerb,
        eventMs: Long,
    ): String = sha256Hex("$folderId:$relPath:${verb.wire}:$eventMs".toByteArray(Charsets.UTF_8))
}
