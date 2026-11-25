// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.work

import app.solstone.core.observer.MobileImportClient
import app.solstone.core.observer.MobileImportFile
import app.solstone.core.observer.MobileImportInspectOutcome
import app.solstone.core.observer.MobileImportItem
import app.solstone.core.observer.MobileImportOutcome
import app.solstone.core.observer.MobileImportSource
import app.solstone.core.observer.MobileImportText
import app.solstone.core.observer.RESUMABLE_UPLOAD_PHASE_CHECKING
import app.solstone.core.observer.RESUMABLE_UPLOAD_PHASE_PAUSED
import app.solstone.core.observer.RESUMABLE_UPLOAD_PHASE_SENDING
import app.solstone.core.observer.ResumableChunkOutcome
import app.solstone.core.observer.ResumableCommitOutcome
import app.solstone.core.observer.ResumableConfigOutcome
import app.solstone.core.observer.ResumableCreateOutcome
import app.solstone.core.observer.ResumableStatusOutcome
import app.solstone.core.observer.ResumableUploadConfig
import app.solstone.core.observer.ResumableUploadCreateRequest
import app.solstone.core.observer.abortUpload
import app.solstone.core.observer.commitUpload
import app.solstone.core.observer.createUpload
import app.solstone.core.observer.resumableConfig
import app.solstone.core.observer.uploadChunk
import app.solstone.core.observer.uploadStatus
import app.solstone.core.pl.PlHttpClient
import app.solstone.platform.persistence.room.ImportCandidate
import app.solstone.platform.persistence.room.ImportChange
import app.solstone.platform.persistence.room.ImportCustodyStore
import app.solstone.platform.persistence.room.ImportItemDescriptor
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS

/** How many distinct imports one sync run takes on, across all its transport attempts. */
internal const val IMPORTS_PER_RUN = 16

/**
 * WorkManager execution window budget for chunked resumable continuation (7 minutes, safely inside
 * WorkManager's 10-minute window on API 26–33 and fallback paths).
 */
internal const val WORKMANAGER_IMPORT_BUDGET_MS = 7L * 60L * 1000L

private const val MAX_CHUNK_CHECKSUM_RETRIES = 3
private const val MAX_OFFSET_RESYNC_ATTEMPTS = 5
private const val MAX_COMMIT_INCOMPLETE_RESYNCS = 2

/**
 * What one sync run has done with imports so far, carried across its transport attempts (direct,
 * then relay), so an item retried on direct is tried again on relay without counting twice, and
 * the cap holds for the whole run.
 */
internal class ImportRun(private val cap: Int = IMPORTS_PER_RUN) {
    val selected = linkedSetOf<String>()
    val retried = linkedSetOf<String>()

    /** The last import stage finished without a retry or a refusal and took every item it could. */
    var stageClean = false

    /** The last stage stopped at the cap with more eligible items waiting. */
    var stoppedByCap = false

    /** The last stage yielded cleanly between chunks at the worker time budget to continue in a follow-up run. */
    var yieldedByBudget = false

    fun mayTake(id: String): Boolean = id in selected || selected.size < cap
}

internal enum class ImportStageResult {
    /** Every item it took was delivered, refused, saved, or yielded cleanly at the time budget. */
    CLEAN,

    /** At least one item should be tried again (on fallback, or by a later run). */
    RETRY,

    /** The journal refused this pairing; the run stops. */
    AUTH_STOP,

    /** The pairing changed during the stage; the run stops sending. */
    PAIRING_LEFT,

    /** The journal's version doesn't take imports yet, or isn't known; nothing was sent. */
    HELD,
}

enum class ImportReconcileOutcome {
    /** Reconciled every ready-to-review candidate cleanly (or none were waiting). */
    CLEAN,

    /** At least one candidate hit a transient/transport failure; fallback transport may retry. */
    TRANSIENT,

    /** The journal refused this pairing (401/403); batch halted without deleting bytes. */
    AUTH_STOP,

    /** The pairing changed during reconciliation. */
    PAIRING_LEFT,
}

/**
 * Safely reconcile `ready_to_review` items for [instanceId] via `GET /app/import/api/<id>`.
 *
 * Preconditions (paired, mark-confirmed, not pairing-refused) are enforced by the caller.
 * First resets any `ready_to_review` / `review_expired` items belonging to a different journal
 * instance back to `pending` so Journal B is never queried with Journal A's `import_id`.
 * Never logs raw filenames or file paths.
 */
fun reconcileReadyToReviewImports(
    http: PlHttpClient,
    store: ImportCustodyStore,
    instanceId: String,
    pairingCurrent: () -> Boolean,
    log: (String, Throwable?) -> Unit = { _, _ -> },
    client: (PlHttpClient) -> MobileImportClient = ::MobileImportClient,
): ImportReconcileOutcome {
    if (!pairingCurrent()) return ImportReconcileOutcome.PAIRING_LEFT
    store.resetReviewItemsForInstanceChange(instanceId)
    store.resetUploadSessionsForInstanceChange(instanceId)
    val candidates = store.reviewCandidates(instanceId)
    if (candidates.isEmpty()) return ImportReconcileOutcome.CLEAN
    val wire = client(http)
    var anyTransient = false
    try {
        for (candidate in candidates) {
            if (!pairingCurrent()) return ImportReconcileOutcome.PAIRING_LEFT
            val outcome = try {
                wire.inspect(candidate.importId)
            } catch (e: Exception) {
                log("import ${candidate.id} reconcile threw", e)
                MobileImportInspectOutcome.Transient("threw-${e.javaClass.simpleName}")
            }
            when (outcome) {
                MobileImportInspectOutcome.Confirmed -> {
                    store.reconcileConfirmed(candidate.id, candidate.importId, candidate.instanceId)
                }
                MobileImportInspectOutcome.StillWaiting -> Unit
                MobileImportInspectOutcome.Expired -> {
                    store.reconcileExpired(candidate.id, candidate.importId, candidate.instanceId)
                }
                MobileImportInspectOutcome.AuthStop -> return ImportReconcileOutcome.AUTH_STOP
                is MobileImportInspectOutcome.Transient -> {
                    anyTransient = true
                }
            }
        }
    } finally {
        runCatching { store.finish() }.onFailure { log("finishing reconciled imports failed", it) }
    }
    return if (anyTransient) ImportReconcileOutcome.TRANSIENT else ImportReconcileOutcome.CLEAN
}

/**
 * Whether [candidate] can be sent to a journal at [journalVersion] without losing its caption.
 * An item already saved at this journal (`progress != null`) finishes its `start` regardless so
 * a half-finished exchange never wedges.
 */
internal fun isImportDeliverable(candidate: ImportCandidate, journalVersion: String): Boolean =
    candidate.progress != null || !candidate.hasCaption || journalSupportsImportCaptions(journalVersion)

/** Whether any currently eligible item can be delivered to [journalVersion] right now. */
internal fun hasDeliverableImports(
    store: ImportCustodyStore,
    instanceId: String,
    journalVersion: String,
    now: Long,
): Boolean = store.eligible(instanceId, journalVersion, now, Int.MAX_VALUE).any { isImportDeliverable(it, journalVersion) }

/**
 * Delay (in ms, >= 1) until the earliest deliverable import waiting in retry backoff at
 * [instanceId] becomes due for [journalVersion], or null when none is waiting in backoff or
 * [gate] does not allow imports.
 */
internal fun nextImportRetryDelayMs(
    store: ImportCustodyStore,
    instanceId: String,
    journalVersion: String,
    gate: ImportVersionGate,
    now: Long,
): Long? {
    if (gate != ImportVersionGate.SUPPORTED) return null
    val earliest = store.earliestBackoffAt(instanceId, now) { hasCaption, hasSavedProgress ->
        hasSavedProgress || !hasCaption || journalSupportsImportCaptions(journalVersion)
    } ?: return null
    return (earliest - now).coerceAtLeast(1L)
}

/**
 * Deliver held imports to the paired journal, oldest first, within [run]'s cap. Each item's
 * exchange is marked in the store so the owner can't remove it mid-flight, and each answer is
 * recorded and the exchange ended under the store lock.
 *
 * Files at or above the journal-advertised `threshold_bytes` use resumable chunked uploads
 * (`/app/import/api/uploads*`) with whole-file and per-chunk SHA-256 verification and confirmed
 * `next_offset` resumption. Smaller files and older journals keep `/app/import/api/save`.
 */
internal fun deliverImports(
    http: PlHttpClient,
    store: ImportCustodyStore,
    run: ImportRun,
    instanceId: String,
    journalVersion: String,
    gate: ImportVersionGate,
    pairingCurrent: () -> Boolean,
    now: () -> Long,
    log: (String, Throwable?) -> Unit,
    diag: (String) -> Unit = {},
    client: (PlHttpClient) -> MobileImportClient = ::MobileImportClient,
    clientCertFingerprint: String = "",
    resumableConfigProvider: ((MobileImportClient) -> ResumableConfigOutcome)? = null,
    onObservedThreshold: ((Long) -> Unit)? = null,
    shouldYield: () -> Boolean = { false },
    candidateFilter: (ImportCandidate) -> Boolean = { true },
): ImportStageResult {
    if (gate != ImportVersionGate.SUPPORTED) return ImportStageResult.HELD
    if (pairingCurrent()) {
        store.resetReviewItemsForInstanceChange(instanceId)
        store.resetUploadSessionsForInstanceChange(instanceId)
    }
    val wire = client(http)
    val candidates = store.eligible(instanceId, journalVersion, now(), Int.MAX_VALUE)
    var anyRetry = false
    var anyHeldForVersion = false
    var attempted = 0
    var result = ImportStageResult.CLEAN
    run.stoppedByCap = false
    run.yieldedByBudget = false

    var cachedConfigOutcome: ResumableConfigOutcome? = null
    val probeResumableConfig: () -> ResumableConfigOutcome = {
        cachedConfigOutcome ?: run {
            val probed = if (resumableConfigProvider != null) {
                resumableConfigProvider(wire)
            } else {
                wire.resumableConfig(clientCertFingerprint)
            }
            if (probed is ResumableConfigOutcome.Supported) {
                runCatching { onObservedThreshold?.invoke(probed.config.thresholdBytes) }
            }
            if (probed !is ResumableConfigOutcome.Retry) {
                cachedConfigOutcome = probed
            }
            probed
        }
    }

    try {
        for (candidate in candidates) {
            if (!candidateFilter(candidate)) continue
            if (!isImportDeliverable(candidate, journalVersion)) {
                anyHeldForVersion = true
                continue
            }
            if (shouldYield()) {
                run.yieldedByBudget = true
                break
            }
            if (!run.mayTake(candidate.id)) {
                run.stoppedByCap = true
                break
            }
            if (!pairingCurrent()) {
                result = ImportStageResult.PAIRING_LEFT
                break
            }
            if (store.beginExchange(candidate.id, instanceId, journalVersion) != ImportChange.DONE) continue
            attempted++
            run.selected.add(candidate.id)
            run.retried.remove(candidate.id)
            val retried = { stage: String, cause: String ->
                runCatching { diag(importRetryLine(candidate.id, stage, cause)) }
                Unit
            }
            val step = try {
                deliverOne(
                    wire = wire,
                    store = store,
                    id = candidate.id,
                    saved = candidate.progress?.let { it.path to it.timestamp },
                    instanceId = instanceId,
                    journalVersion = journalVersion,
                    pairingCurrent = pairingCurrent,
                    probeResumableConfig = probeResumableConfig,
                    shouldYield = shouldYield,
                    retried = retried,
                )
            } catch (t: Throwable) {
                log("import ${candidate.id} failed; retry", t)
                retried("exchange", "threw-" + t.javaClass.simpleName.ifEmpty { "Throwable" })
                ItemStep.RETRY
            } finally {
                store.endExchange(candidate.id)
            }
            when (step) {
                ItemStep.DONE -> Unit
                ItemStep.YIELDED -> {
                    run.yieldedByBudget = true
                    break
                }
                ItemStep.RETRY -> {
                    run.retried.add(candidate.id)
                    anyRetry = true
                }
                ItemStep.AUTH_STOP -> {
                    result = ImportStageResult.AUTH_STOP
                    break
                }
                ItemStep.PAIRING_LEFT -> {
                    result = ImportStageResult.PAIRING_LEFT
                    break
                }
            }
        }
    } finally {
        runCatching { store.finish() }.onFailure { log("finishing delivered imports failed", it) }
    }
    if (result == ImportStageResult.CLEAN && anyHeldForVersion && attempted == 0) {
        run.stageClean = false
        return ImportStageResult.HELD
    }
    if (result == ImportStageResult.CLEAN && anyRetry) result = ImportStageResult.RETRY
    run.stageClean = result == ImportStageResult.CLEAN
    return result
}

/**
 * Record backoff for every item this run tried and could not deliver, once the whole run (all its
 * transports) is over, so a retry on direct doesn't keep the same item from relay.
 */
internal fun recordImportRetries(store: ImportCustodyStore, run: ImportRun, instanceId: String, now: Long) {
    for (id in run.retried) {
        store.markRetry(id, instanceId, now)
    }
}

/** One owner event-log line for an import left to retry. The id is the device's own random one. */
internal fun importRetryLine(id: String, stage: String, cause: String): String =
    "kind=import id=$id step=$stage outcome=retry cause=$cause"

private enum class ItemStep { DONE, RETRY, AUTH_STOP, PAIRING_LEFT, YIELDED }

private fun deliverOne(
    wire: MobileImportClient,
    store: ImportCustodyStore,
    id: String,
    saved: Pair<String, String>?,
    instanceId: String,
    journalVersion: String,
    pairingCurrent: () -> Boolean,
    probeResumableConfig: () -> ResumableConfigOutcome,
    shouldYield: () -> Boolean,
    retried: (stage: String, cause: String) -> Unit,
): ItemStep {
    var progress = saved
    if (progress == null) {
        val descriptor = store.item(id) ?: return ItemStep.DONE
        if (descriptor.payloadKind == "file") {
            when (val configOutcome = probeResumableConfig()) {
                ResumableConfigOutcome.AuthStop -> return ItemStep.AUTH_STOP
                is ResumableConfigOutcome.Retry -> {
                    retried("resumable-config", configOutcome.cause)
                    return ItemStep.RETRY
                }
                ResumableConfigOutcome.Unsupported -> {
                    // Older journal without resumable uploads: keep /save path unchanged.
                }
                is ResumableConfigOutcome.Supported -> {
                    val hasExistingSession = descriptor.uploadId != null && descriptor.uploadInstanceId == instanceId
                    if (descriptor.size >= configOutcome.config.thresholdBytes || hasExistingSession) {
                        return deliverResumable(
                            wire = wire,
                            store = store,
                            descriptor = descriptor,
                            config = configOutcome.config,
                            instanceId = instanceId,
                            journalVersion = journalVersion,
                            pairingCurrent = pairingCurrent,
                            shouldYield = shouldYield,
                            retried = retried,
                        )
                    }
                }
            }
        }
        when (val outcome = save(wire, descriptor)) {
            is MobileImportOutcome.Saved -> {
                if (!pairingCurrent()) return ItemStep.PAIRING_LEFT
                if (store.recordSaved(id, outcome.path, outcome.timestamp, instanceId) == ImportChange.GONE) return ItemStep.DONE
                progress = outcome.path to outcome.timestamp
            }
            else -> return settle(store, id, outcome, instanceId, journalVersion) { retried("save", it) }
        }
    }
    if (!pairingCurrent()) return ItemStep.PAIRING_LEFT
    return settle(store, id, wire.start(progress.first, progress.second), instanceId, journalVersion) { retried("start", it) }
}

private fun deliverResumable(
    wire: MobileImportClient,
    store: ImportCustodyStore,
    descriptor: ImportItemDescriptor,
    config: ResumableUploadConfig,
    instanceId: String,
    journalVersion: String,
    pairingCurrent: () -> Boolean,
    shouldYield: () -> Boolean,
    retried: (stage: String, cause: String) -> Unit,
): ItemStep {
    val id = descriptor.id
    val totalBytes = descriptor.size
    if (totalBytes <= 0L || !Files.isRegularFile(descriptor.payloadPath, NOFOLLOW_LINKS)) {
        store.markLocalUnreadable(id, "local copy unreadable")
        return ItemStep.DONE
    }
    val actualSize = try {
        Files.size(descriptor.payloadPath)
    } catch (e: IOException) {
        store.markLocalUnreadable(id, e.message)
        return ItemStep.DONE
    }
    if (actualSize != totalBytes) {
        store.markLocalUnreadable(id, "local copy size changed")
        return ItemStep.DONE
    }

    if (store.isCancelRequested(id)) {
        abortIfKnown(wire, descriptor.uploadId, descriptor.uploadInstanceId, instanceId)
        store.completeCancelled(id)
        return ItemStep.DONE
    }
    if (!pairingCurrent()) return ItemStep.PAIRING_LEFT
    if (shouldYield()) return ItemStep.YIELDED

    // 1. Compute or reuse whole-file SHA-256 from custody copy before creating the session.
    val sha256 = descriptor.payloadSha256?.takeIf { it.length == 64 } ?: run {
        store.recordUploadPhase(id, RESUMABLE_UPLOAD_PHASE_SENDING)
        val computed = try {
            sha256FileHex(
                path = descriptor.payloadPath,
                shouldStop = { store.isCancelRequested(id) || !pairingCurrent() || shouldYield() },
            )
        } catch (e: IOException) {
            store.markLocalUnreadable(id, e.message)
            return ItemStep.DONE
        }
        if (computed == null) {
            return when {
                store.isCancelRequested(id) -> {
                    abortIfKnown(wire, descriptor.uploadId, descriptor.uploadInstanceId, instanceId)
                    store.completeCancelled(id)
                    ItemStep.DONE
                }
                !pairingCurrent() -> ItemStep.PAIRING_LEFT
                else -> ItemStep.YIELDED
            }
        }
        store.recordPayloadSha256(id, computed)
        computed
    }

    val createReq = ResumableUploadCreateRequest(
        clientItemId = descriptor.id,
        filename = descriptor.displayName,
        mediaType = descriptor.mime,
        totalBytes = totalBytes,
        sha256 = sha256,
        caption = descriptor.caption,
        area = descriptor.area,
        context = descriptor.context,
    )

    // 2. Resync an existing session on this journal instance via GET /uploads/{id}, or create one.
    var uploadId: String
    var offset: Long
    var chunkBytes: Long = config.chunkBytes

    val existingUploadId = descriptor.uploadId?.takeIf { descriptor.uploadInstanceId == instanceId }
    if (existingUploadId != null) {
        when (val status = wire.uploadStatus(existingUploadId)) {
            is ResumableStatusOutcome.Open -> {
                uploadId = status.uploadId
                offset = status.nextOffset.coerceIn(0L, totalBytes)
                chunkBytes = status.chunkBytes
                store.recordUploadProgress(
                    id = id,
                    uploadId = uploadId,
                    instanceId = instanceId,
                    confirmedOffset = offset,
                    phase = RESUMABLE_UPLOAD_PHASE_SENDING,
                    resuming = offset > 0L,
                )
            }
            ResumableStatusOutcome.UploadNotFound -> {
                // If all bytes were already uploaded, check once whether a prior commit already staged/imported it.
                if (descriptor.uploadOffset >= totalBytes) {
                    val dedup = probeAlreadyCompleted(wire, store, id, createReq, instanceId, journalVersion, pairingCurrent, retried)
                    if (dedup != null) return dedup
                }
                store.recordAttention(
                    id = id,
                    error = "upload expired",
                    reasonCode = "upload_not_found",
                    kind = null,
                    instance = instanceId,
                    journalVersion = journalVersion,
                )
                return ItemStep.DONE
            }
            ResumableStatusOutcome.AuthStop -> return ItemStep.AUTH_STOP
            is ResumableStatusOutcome.Retry -> {
                store.recordUploadPhase(id, RESUMABLE_UPLOAD_PHASE_PAUSED)
                retried("upload-status", status.cause)
                return ItemStep.RETRY
            }
        }
    } else {
        when (val created = wire.createUpload(createReq)) {
            is ResumableCreateOutcome.Open -> {
                uploadId = created.uploadId
                offset = created.nextOffset.coerceIn(0L, totalBytes)
                chunkBytes = created.chunkBytes
                store.recordUploadProgress(
                    id = id,
                    uploadId = uploadId,
                    instanceId = instanceId,
                    confirmedOffset = offset,
                    phase = RESUMABLE_UPLOAD_PHASE_SENDING,
                    resuming = offset > 0L,
                )
            }
            is ResumableCreateOutcome.AlreadyStaged -> {
                if (!pairingCurrent()) return ItemStep.PAIRING_LEFT
                if (store.recordSaved(id, created.path, created.timestamp, instanceId) == ImportChange.GONE) return ItemStep.DONE
                return settle(store, id, wire.start(created.path, created.timestamp), instanceId, journalVersion) { retried("start", it) }
            }
            is ResumableCreateOutcome.AlreadyImported -> {
                store.recordDelivered(id, instanceId, created.importId)
                return ItemStep.DONE
            }
            ResumableCreateOutcome.RouteUnavailable -> {
                // 404/405 fallback to single-POST /save for older journals.
                return when (val outcome = save(wire, descriptor)) {
                    is MobileImportOutcome.Saved -> {
                        if (!pairingCurrent()) return ItemStep.PAIRING_LEFT
                        if (store.recordSaved(id, outcome.path, outcome.timestamp, instanceId) == ImportChange.GONE) return ItemStep.DONE
                        settle(store, id, wire.start(outcome.path, outcome.timestamp), instanceId, journalVersion) { retried("start", it) }
                    }
                    else -> settle(store, id, outcome, instanceId, journalVersion) { retried("save", it) }
                }
            }
            is ResumableCreateOutcome.InsufficientSpace -> {
                store.recordAttention(id, created.error, created.reasonCode, null, instanceId, journalVersion)
                return ItemStep.DONE
            }
            ResumableCreateOutcome.AuthStop -> return ItemStep.AUTH_STOP
            is ResumableCreateOutcome.Retry -> {
                retried("upload-create", created.cause)
                return ItemStep.RETRY
            }
            is ResumableCreateOutcome.NeedsAttention -> {
                store.recordAttention(id, created.error, created.reasonCode ?: "", created.kind, instanceId, journalVersion)
                return ItemStep.DONE
            }
        }
    }

    // 3. Stream remaining chunks sequentially and commit once confirmed offset reaches totalBytes.
    var commitIncompleteResyncs = 0
    while (true) {
        var chunkMismatchAttempts = 0
        var offsetMismatchAttempts = 0
        while (offset < totalBytes) {
            if (store.isCancelRequested(id)) {
                runCatching { wire.abortUpload(uploadId) }
                store.completeCancelled(id)
                return ItemStep.DONE
            }
            if (!pairingCurrent()) {
                store.recordUploadPhase(id, RESUMABLE_UPLOAD_PHASE_PAUSED)
                return ItemStep.PAIRING_LEFT
            }
            if (shouldYield()) {
                store.recordUploadPhase(id, RESUMABLE_UPLOAD_PHASE_PAUSED)
                return ItemStep.YIELDED
            }

            val chunkLen = minOf(chunkBytes, totalBytes - offset)
            val chunkSha = try {
                sha256SliceHex(
                    path = descriptor.payloadPath,
                    offset = offset,
                    length = chunkLen,
                    shouldStop = { store.isCancelRequested(id) || !pairingCurrent() || shouldYield() },
                )
            } catch (e: IOException) {
                store.markLocalUnreadable(id, e.message)
                return ItemStep.DONE
            }
            if (chunkSha == null) {
                return when {
                    store.isCancelRequested(id) -> {
                        runCatching { wire.abortUpload(uploadId) }
                        store.completeCancelled(id)
                        ItemStep.DONE
                    }
                    !pairingCurrent() -> {
                        store.recordUploadPhase(id, RESUMABLE_UPLOAD_PHASE_PAUSED)
                        ItemStep.PAIRING_LEFT
                    }
                    else -> {
                        store.recordUploadPhase(id, RESUMABLE_UPLOAD_PHASE_PAUSED)
                        ItemStep.YIELDED
                    }
                }
            }

            val source = FileChannelSliceSource(
                path = descriptor.payloadPath,
                offset = offset,
                length = chunkLen,
                shouldStop = { store.isCancelRequested(id) || !pairingCurrent() },
            )
            val chunkOutcome = wire.uploadChunk(uploadId, offset, chunkLen, chunkSha, source)

            if (store.isCancelRequested(id)) {
                runCatching { wire.abortUpload(uploadId) }
                store.completeCancelled(id)
                return ItemStep.DONE
            }
            if (source.localReadFailure != null) {
                store.markLocalUnreadable(id, source.localReadFailure?.message)
                return ItemStep.DONE
            }
            if (!pairingCurrent()) {
                store.recordUploadPhase(id, RESUMABLE_UPLOAD_PHASE_PAUSED)
                return ItemStep.PAIRING_LEFT
            }

            when (chunkOutcome) {
                is ResumableChunkOutcome.Accepted -> {
                    chunkMismatchAttempts = 0
                    offsetMismatchAttempts = 0
                    offset = chunkOutcome.nextOffset.coerceIn(0L, totalBytes)
                    store.recordUploadProgress(
                        id = id,
                        uploadId = uploadId,
                        instanceId = instanceId,
                        confirmedOffset = offset,
                        phase = RESUMABLE_UPLOAD_PHASE_SENDING,
                        resuming = false,
                    )
                    if (chunkOutcome.complete && offset >= totalBytes) break
                }
                is ResumableChunkOutcome.OffsetMismatch -> {
                    offsetMismatchAttempts++
                    if (offsetMismatchAttempts > MAX_OFFSET_RESYNC_ATTEMPTS) {
                        store.recordUploadPhase(id, RESUMABLE_UPLOAD_PHASE_PAUSED)
                        retried("upload-chunk", "offset-mismatch-loop")
                        return ItemStep.RETRY
                    }
                    val hinted = chunkOutcome.nextOffset?.takeIf { it in 0L..totalBytes }
                    if (hinted != null) {
                        offset = hinted
                        store.recordUploadProgress(
                            id = id,
                            uploadId = uploadId,
                            instanceId = instanceId,
                            confirmedOffset = offset,
                            phase = RESUMABLE_UPLOAD_PHASE_SENDING,
                            resuming = true,
                        )
                    } else {
                        when (val resync = wire.uploadStatus(uploadId)) {
                            is ResumableStatusOutcome.Open -> {
                                offset = resync.nextOffset.coerceIn(0L, totalBytes)
                                chunkBytes = resync.chunkBytes
                                store.recordUploadProgress(
                                    id = id,
                                    uploadId = uploadId,
                                    instanceId = instanceId,
                                    confirmedOffset = offset,
                                    phase = RESUMABLE_UPLOAD_PHASE_SENDING,
                                    resuming = true,
                                )
                            }
                            ResumableStatusOutcome.UploadNotFound -> {
                                store.recordAttention(id, "upload expired", "upload_not_found", null, instanceId, journalVersion)
                                return ItemStep.DONE
                            }
                            ResumableStatusOutcome.AuthStop -> return ItemStep.AUTH_STOP
                            is ResumableStatusOutcome.Retry -> {
                                store.recordUploadPhase(id, RESUMABLE_UPLOAD_PHASE_PAUSED)
                                retried("upload-resync", resync.cause)
                                return ItemStep.RETRY
                            }
                        }
                    }
                }
                is ResumableChunkOutcome.ChunkChecksumMismatch -> {
                    chunkOutcome.nextOffset?.takeIf { it in 0L..totalBytes }?.let { offset = it }
                    chunkMismatchAttempts++
                    if (chunkMismatchAttempts > MAX_CHUNK_CHECKSUM_RETRIES) {
                        store.recordAttention(
                            id = id,
                            error = "chunk checksum mismatch",
                            reasonCode = "chunk_checksum_mismatch",
                            kind = null,
                            instance = instanceId,
                            journalVersion = journalVersion,
                        )
                        return ItemStep.DONE
                    }
                }
                is ResumableChunkOutcome.UploadNotFound -> {
                    store.recordAttention(id, chunkOutcome.error, "upload_not_found", null, instanceId, journalVersion)
                    return ItemStep.DONE
                }
                is ResumableChunkOutcome.InsufficientSpace -> {
                    store.recordAttention(id, chunkOutcome.error, chunkOutcome.reasonCode, null, instanceId, journalVersion)
                    return ItemStep.DONE
                }
                ResumableChunkOutcome.AuthStop -> return ItemStep.AUTH_STOP
                is ResumableChunkOutcome.Retry -> {
                    store.recordUploadPhase(id, RESUMABLE_UPLOAD_PHASE_PAUSED)
                    retried("upload-chunk", chunkOutcome.cause)
                    return ItemStep.RETRY
                }
                is ResumableChunkOutcome.NeedsAttention -> {
                    store.recordAttention(id, chunkOutcome.error, chunkOutcome.reasonCode ?: "", chunkOutcome.kind, instanceId, journalVersion)
                    return ItemStep.DONE
                }
            }
        }

        if (store.isCancelRequested(id)) {
            runCatching { wire.abortUpload(uploadId) }
            store.completeCancelled(id)
            return ItemStep.DONE
        }
        if (!pairingCurrent()) {
            store.recordUploadPhase(id, RESUMABLE_UPLOAD_PHASE_PAUSED)
            return ItemStep.PAIRING_LEFT
        }

        store.recordUploadPhase(id, RESUMABLE_UPLOAD_PHASE_CHECKING)
        when (val commitOutcome = wire.commitUpload(uploadId, sha256)) {
            is ResumableCommitOutcome.Saved -> {
                if (!pairingCurrent()) return ItemStep.PAIRING_LEFT
                if (store.recordSaved(id, commitOutcome.path, commitOutcome.timestamp, instanceId) == ImportChange.GONE) {
                    return ItemStep.DONE
                }
                return settle(store, id, wire.start(commitOutcome.path, commitOutcome.timestamp), instanceId, journalVersion) {
                    retried("start", it)
                }
            }
            is ResumableCommitOutcome.Incomplete -> {
                commitIncompleteResyncs++
                if (commitIncompleteResyncs > MAX_COMMIT_INCOMPLETE_RESYNCS) {
                    store.recordUploadPhase(id, RESUMABLE_UPLOAD_PHASE_PAUSED)
                    retried("upload-commit", "incomplete-loop")
                    return ItemStep.RETRY
                }
                val hinted = commitOutcome.nextOffset?.takeIf { it in 0L until totalBytes }
                if (hinted != null) {
                    offset = hinted
                    store.recordUploadProgress(id, uploadId, instanceId, offset, RESUMABLE_UPLOAD_PHASE_SENDING, resuming = true)
                } else {
                    when (val resync = wire.uploadStatus(uploadId)) {
                        is ResumableStatusOutcome.Open -> {
                            offset = resync.nextOffset.coerceIn(0L, totalBytes)
                            chunkBytes = resync.chunkBytes
                            store.recordUploadProgress(id, uploadId, instanceId, offset, RESUMABLE_UPLOAD_PHASE_SENDING, resuming = true)
                        }
                        ResumableStatusOutcome.UploadNotFound -> {
                            store.recordAttention(id, "upload expired", "upload_not_found", null, instanceId, journalVersion)
                            return ItemStep.DONE
                        }
                        ResumableStatusOutcome.AuthStop -> return ItemStep.AUTH_STOP
                        is ResumableStatusOutcome.Retry -> {
                            store.recordUploadPhase(id, RESUMABLE_UPLOAD_PHASE_PAUSED)
                            retried("upload-resync", resync.cause)
                            return ItemStep.RETRY
                        }
                    }
                }
            }
            is ResumableCommitOutcome.WholeFileChecksumMismatch -> {
                store.recordAttention(
                    id = id,
                    error = commitOutcome.error,
                    reasonCode = "whole_file_checksum_mismatch",
                    kind = null,
                    instance = instanceId,
                    journalVersion = journalVersion,
                )
                return ItemStep.DONE
            }
            is ResumableCommitOutcome.UploadNotFound -> {
                val dedup = probeAlreadyCompleted(wire, store, id, createReq, instanceId, journalVersion, pairingCurrent, retried)
                if (dedup != null) return dedup
                store.recordAttention(id, commitOutcome.error, "upload_not_found", null, instanceId, journalVersion)
                return ItemStep.DONE
            }
            is ResumableCommitOutcome.InsufficientSpace -> {
                store.recordAttention(id, commitOutcome.error, commitOutcome.reasonCode, null, instanceId, journalVersion)
                return ItemStep.DONE
            }
            ResumableCommitOutcome.AuthStop -> return ItemStep.AUTH_STOP
            is ResumableCommitOutcome.Retry -> {
                store.recordUploadPhase(id, RESUMABLE_UPLOAD_PHASE_PAUSED)
                retried("upload-commit", commitOutcome.cause)
                return ItemStep.RETRY
            }
            is ResumableCommitOutcome.NeedsAttention -> {
                store.recordAttention(id, commitOutcome.error, commitOutcome.reasonCode ?: "", commitOutcome.kind, instanceId, journalVersion)
                return ItemStep.DONE
            }
        }
    }
}

/**
 * When a `404` arrives after all bytes were already sent, check once via `POST /uploads` whether
 * a prior commit already staged or imported this `client_item_id`/`sha256` before reporting expiry.
 * If `POST /uploads` opens a brand-new session instead, abort it immediately so we don't leak a
 * zero-byte partial upload while reporting expiry to the owner.
 */
private fun probeAlreadyCompleted(
    wire: MobileImportClient,
    store: ImportCustodyStore,
    id: String,
    request: ResumableUploadCreateRequest,
    instanceId: String,
    journalVersion: String,
    pairingCurrent: () -> Boolean,
    retried: (stage: String, cause: String) -> Unit,
): ItemStep? =
    when (val probed = wire.createUpload(request)) {
        is ResumableCreateOutcome.AlreadyStaged -> {
            if (!pairingCurrent()) {
                ItemStep.PAIRING_LEFT
            } else if (store.recordSaved(id, probed.path, probed.timestamp, instanceId) == ImportChange.GONE) {
                ItemStep.DONE
            } else {
                settle(store, id, wire.start(probed.path, probed.timestamp), instanceId, journalVersion) { retried("start", it) }
            }
        }
        is ResumableCreateOutcome.AlreadyImported -> {
            store.recordDelivered(id, instanceId, probed.importId)
            ItemStep.DONE
        }
        is ResumableCreateOutcome.Open -> {
            runCatching { wire.abortUpload(probed.uploadId) }
            null
        }
        ResumableCreateOutcome.AuthStop -> ItemStep.AUTH_STOP
        else -> null
    }

private fun abortIfKnown(
    wire: MobileImportClient,
    uploadId: String?,
    uploadInstanceId: String?,
    currentInstanceId: String,
) {
    if (uploadId != null && uploadInstanceId == currentInstanceId) {
        runCatching { wire.abortUpload(uploadId) }
    }
}

private fun settle(
    store: ImportCustodyStore,
    id: String,
    outcome: MobileImportOutcome,
    instanceId: String,
    journalVersion: String,
    retried: (cause: String) -> Unit,
): ItemStep =
    when (outcome) {
        is MobileImportOutcome.Delivered -> {
            store.recordDelivered(id, instanceId, outcome.importId)
            ItemStep.DONE
        }
        is MobileImportOutcome.ReviewRequired -> {
            store.recordReviewRequired(
                id = id,
                importId = outcome.importId,
                reasonCode = outcome.reasonCode,
                kind = outcome.kind,
                error = outcome.error,
                instance = instanceId,
            )
            ItemStep.DONE
        }
        is MobileImportOutcome.NeedsAttention -> {
            store.recordAttention(id, outcome.error, outcome.reasonCode ?: "", outcome.kind, instanceId, journalVersion)
            ItemStep.DONE
        }
        MobileImportOutcome.AuthStop -> ItemStep.AUTH_STOP
        is MobileImportOutcome.Retry -> {
            retried(outcome.cause)
            ItemStep.RETRY
        }
        // A save answer is handled by the caller; at start it means the journal answered
        // something else, so try again later rather than guess.
        is MobileImportOutcome.Saved -> {
            retried("answer-saved")
            ItemStep.RETRY
        }
    }

private fun save(wire: MobileImportClient, descriptor: ImportItemDescriptor): MobileImportOutcome {
    val item: MobileImportItem = if (descriptor.payloadKind == "text") {
        MobileImportText(descriptor.id, String(Files.readAllBytes(descriptor.payloadPath), Charsets.UTF_8))
    } else {
        MobileImportFile(
            clientItemId = descriptor.id,
            filename = descriptor.displayName,
            mediaType = descriptor.mime,
            length = descriptor.size,
            source = FileImportSource(Files.newInputStream(descriptor.payloadPath)),
            caption = descriptor.caption,
            area = descriptor.area,
            context = descriptor.context,
        )
    }
    return wire.save(item)
}

private class FileImportSource(private val input: InputStream) : MobileImportSource {
    override fun read(target: ByteArray, offset: Int, length: Int): Int = input.read(target, offset, length)
    override fun close() = input.close()
}
