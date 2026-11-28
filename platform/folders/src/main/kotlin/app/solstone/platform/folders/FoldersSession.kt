// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.folders

import android.content.Context
import android.net.Uri
import android.os.CancellationSignal
import android.util.Log
import androidx.work.WorkerParameters
import app.solstone.core.folders.BeginSetupResult
import app.solstone.core.folders.ConfirmResult
import app.solstone.core.folders.DeliveryOutcome
import app.solstone.core.folders.EstimateFetchResult
import app.solstone.core.folders.EstimateItem
import app.solstone.core.folders.FacetOption
import app.solstone.core.folders.FacetsAvailability
import app.solstone.core.folders.FacetsFetchResult
import app.solstone.core.folders.FileKind
import app.solstone.core.folders.FolderAttention
import app.solstone.core.folders.FolderClock
import app.solstone.core.folders.FolderEngine
import app.solstone.core.folders.FolderIdentity
import app.solstone.core.folders.FolderKinds
import app.solstone.core.folders.FolderMode
import app.solstone.core.folders.FolderSpan
import app.solstone.core.folders.FolderSummary
import app.solstone.core.folders.FolderUploadReply
import app.solstone.core.folders.SegmentDelivery
import app.solstone.core.folders.StagedPartPayload
import app.solstone.core.folders.SupportedFetchResult
import app.solstone.core.folders.SystemFolderClock
import app.solstone.core.folders.extensionLower
import app.solstone.core.folders.fetchFolderCapability
import app.solstone.core.folders.fetchFolderEstimate
import app.solstone.core.folders.fetchFolderFacets
import app.solstone.core.folders.fetchFolderSupported
import app.solstone.core.folders.mediaTypeForPartName
import app.solstone.core.folders.prepareFolderUpload
import app.solstone.core.folders.sha256Hex
import app.solstone.core.folders.uploadPreparedFolderSegment
import app.solstone.core.pl.PlHttpClient
import app.solstone.platform.foldersync.FolderCheckWorker
import app.solstone.platform.foldersync.FolderChecks
import app.solstone.platform.foldersync.FolderRuntime
import app.solstone.platform.foldersync.SafFolder
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

private const val TAG = "FoldersSession"
const val FOLDERS_WORK_NAME = "solstone-folders-check"
private const val FOREGROUND_CHECK_INTERVAL_MS = 30_000L
private const val MAX_SEGMENTS_PER_SYNC = 24

object FoldersRuntimes : FolderRuntime<FoldersSession>()

interface FoldersWorkerHost {
    fun foldersSessionForWork(): FoldersSession?
}

class FoldersCheckWorker(context: Context, params: WorkerParameters) : FolderCheckWorker(context, params) {
    override fun runCheck(stopped: () -> Boolean): Boolean =
        (FoldersRuntimes.current ?: (applicationContext as? FoldersWorkerHost)?.foldersSessionForWork())
            ?.runCheckBlocking(stopped) == true
}

enum class FolderStageResult {
    CLEAN,
    RETRY,
    AUTH_STOP,
    PAIRING_LEFT,
}

sealed class FolderPickerNotice {
    data object None : FolderPickerNotice()
    data class AlreadyAdded(val folderId: String, val folderName: String) : FolderPickerNotice()
    data class ProviderRefused(val folderName: String?) : FolderPickerNotice()
}

data class FoldersRegistryStatus(
    val anyConfigured: Boolean = false,
    val anyActiveOrPaused: Boolean = false,
    val anyOn: Boolean = false,
    val allPaused: Boolean = false,
    val anyNeedsAttention: Boolean = false,
    val allAccessOk: Boolean = true,
)

data class FoldersUi(
    val folders: List<FolderSummary> = emptyList(),
    val facets: List<FacetOption> = emptyList(),
    val facetsState: FacetsAvailability = FacetsAvailability.LOADING,
    val capability: Boolean? = null,
    val pickerNotice: FolderPickerNotice = FolderPickerNotice.None,
    val checking: Boolean = false,
    val pendingCount: Int = 0,
    val newlyAddedFolderId: String? = null,
    val lastBackgroundCheckMs: Long? = null,
)

/**
 * Owns watched-folder state, SAF scanning (via `:platform:folder-sync`'s [SafFolder] and
 * [FolderChecks]), background `WorkManager` periodic checks, interactive journal discovery
 * (`/facets`, `/supported`, `/estimate`), and Protocol v3 delivery inside `SyncWithTransport`.
 */
class FoldersSession(
    private val context: Context,
    private val wishOn: () -> Boolean,
    private val setEnabled: (Boolean) -> Boolean,
    private val clearWish: () -> Unit = {},
    private val ownerStopped: () -> Boolean,
    private val onQueued: () -> Unit,
    private val onChanged: () -> Unit,
    private val openLiveClient: (() -> PlHttpClient?)? = null,
    private val obsidianIdentity: () -> FolderIdentity? = { null },
    private val screenshotsIdentity: () -> FolderIdentity? = { null },
    private val clock: FolderClock = SystemFolderClock,
    ledgerFile: File = File(context.filesDir, "folders/ledger.json"),
    safFolder: SafFolder = SafFolder(context.contentResolver),
) {
    private val saf = safFolder
    private val activeSignal = AtomicReference<CancellationSignal?>(null)
    private val fs = AndroidFolderFileSystem(saf) { activeSignal.get() }
    private val engine = FolderEngine(ledgerFile, fs, clock)
    private val networkExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "solstone-folders-net").apply { isDaemon = true }
    }

    @Volatile var ui: FoldersUi = FoldersUi()
        private set

    @Volatile private var listener: (() -> Unit)? = null
    @Volatile private var capability: Boolean? = null
    @Volatile private var capabilityFingerprint: String? = null
    @Volatile private var facets: List<FacetOption> = emptyList()
    @Volatile private var facetsState: FacetsAvailability = FacetsAvailability.LOADING
    @Volatile private var pickerNotice: FolderPickerNotice = FolderPickerNotice.None
    @Volatile private var checking: Boolean = false
    @Volatile private var newlyAddedFolderId: String? = null
    @Volatile private var lastBackgroundCheckMs: Long? = null
    @Volatile private var supportedLoadedForPairing: String? = null

    private val checks = FolderChecks(
        context = context,
        workName = FOLDERS_WORK_NAME,
        worker = FoldersCheckWorker::class.java,
        threadName = "solstone-folders",
        check = { signal -> runCheck(signal, fromBackground = false) },
        onFailure = { error -> Log.w(TAG, "folder check failed", error) },
        backgroundWanted = { hasTrackedFolders() },
        foregroundDelayMs = { if (hasTrackedFolders()) FOREGROUND_CHECK_INTERVAL_MS else null },
        workerTimeoutMs = 90_000L,
    )

    fun setListener(next: () -> Unit) {
        listener = next
    }

    fun onProcessStart() {
        FoldersRuntimes.register(this)
        checks.submit {
            publishLocked()
            checks.scheduleBackground()
        }
    }

    fun onForeground(inForeground: Boolean) {
        checks.onForeground(inForeground)
        if (inForeground) {
            refreshFacetsAsync()
        }
    }

    fun onOwnerStopRequested() {
        checks.submit { publishLocked() }
    }

    fun onPauseChanged() {
        checks.submit { publishLocked() }
    }

    fun runCheckBlocking(stopped: () -> Boolean): Boolean {
        val ok = checks.runFromWorker(stopped)
        if (ok) {
            lastBackgroundCheckMs = clock.nowMs()
            checks.submit { publishLocked() }
        }
        return ok
    }

    fun checkNow(folderId: String? = null) {
        checks.submit {
            checking = true
            publishLocked()
            try {
                activeSignal.set(CancellationSignal())
                engine.scanAll(onlyFolderId = folderId)
                if (engine.hasReadyDelivery(ownerStopped())) {
                    onQueued()
                }
            } finally {
                activeSignal.set(null)
                checking = false
                publishLocked()
            }
        }
    }

    fun clearPickerNotice() {
        checks.submit {
            if (pickerNotice != FolderPickerNotice.None) {
                pickerNotice = FolderPickerNotice.None
                publishLocked()
            }
        }
    }

    fun consumeNewlyAddedFolderId() {
        checks.submit {
            if (newlyAddedFolderId != null) {
                newlyAddedFolderId = null
                publishLocked()
            }
        }
    }

    fun addFolder(treeUri: Uri) {
        checks.submit {
            pickerNotice = FolderPickerNotice.None
            val persisted = runCatching { saf.persist(treeUri); true }.getOrDefault(false)
            if (!persisted && !saf.hasReadGrant(treeUri)) {
                pickerNotice = FolderPickerNotice.ProviderRefused(null)
                publishLocked()
                return@submit
            }
            val descriptor = fs.describeTree(treeUri)
            if (descriptor == null) {
                pickerNotice = FolderPickerNotice.ProviderRefused(null)
                publishLocked()
                return@submit
            }
            // Verify the provider actually lets us list the root (catches restricted/refused providers early).
            val testWalk = fs.walk(descriptor.treeUri)
            if (testWalk !is app.solstone.core.folders.WalkOutcome.Ok) {
                pickerNotice = FolderPickerNotice.ProviderRefused(descriptor.displayName)
                publishLocked()
                return@submit
            }
            when (
                val res = engine.beginSetup(
                    treeUri = descriptor.treeUri,
                    displayName = descriptor.displayName,
                    label = descriptor.label,
                    canonicalPath = descriptor.canonicalPath,
                    obsidian = obsidianIdentity(),
                    screenshots = screenshotsIdentity(),
                )
            ) {
                is BeginSetupResult.AlreadyAdded -> {
                    pickerNotice = FolderPickerNotice.AlreadyAdded(res.existingId, res.existingName)
                    publishLocked()
                }
                is BeginSetupResult.Started -> {
                    newlyAddedFolderId = res.folderId
                    val pair = engine.previewFolderWithItems(res.folderId, FolderSpan.LAST_30_DAYS)
                    publishLocked()
                    checks.scheduleBackground()
                    refreshFacetsAsync()
                    if (pair != null) {
                        requestJournalEstimateAsync(res.folderId, FolderSpan.LAST_30_DAYS, pair.second)
                    }
                }
            }
        }
    }

    fun regrantFolder(folderId: String, treeUri: Uri) {
        checks.submit {
            pickerNotice = FolderPickerNotice.None
            val persisted = runCatching { saf.persist(treeUri); true }.getOrDefault(false)
            if (!persisted && !saf.hasReadGrant(treeUri)) {
                pickerNotice = FolderPickerNotice.ProviderRefused(null)
                publishLocked()
                return@submit
            }
            val descriptor = fs.describeTree(treeUri)
            if (descriptor == null) {
                pickerNotice = FolderPickerNotice.ProviderRefused(null)
                publishLocked()
                return@submit
            }
            if (engine.regrantAccess(folderId, descriptor.treeUri, descriptor.displayName, descriptor.label)) {
                engine.scanAll(onlyFolderId = folderId)
                if (engine.hasReadyDelivery(ownerStopped())) onQueued()
                publishLocked()
            }
        }
    }

    fun selectSpan(folderId: String, span: FolderSpan) {
        checks.submit {
            engine.setSelectedSpan(folderId, span)
            val pair = engine.previewFolderWithItems(folderId, span)
            publishLocked()
            if (pair != null) {
                requestJournalEstimateAsync(folderId, span, pair.second)
            }
        }
    }

    fun refreshPreview(folderId: String, span: FolderSpan? = null) {
        checks.submit {
            val targetSpan = span ?: engine.folderById(folderId)?.selectedSpan ?: FolderSpan.LAST_30_DAYS
            val pair = engine.previewFolderWithItems(folderId, targetSpan)
            publishLocked()
            refreshFacetsAsync()
            if (pair != null) {
                requestJournalEstimateAsync(folderId, targetSpan, pair.second)
            }
        }
    }

    fun setKinds(folderId: String, kinds: FolderKinds) {
        checks.submit {
            if (!engine.setKinds(folderId, kinds)) return@submit
            val rec = engine.folderById(folderId)
            val pair = if (rec != null && rec.mode == FolderMode.SETTING_UP) {
                engine.previewFolderWithItems(folderId, rec.selectedSpan)
            } else {
                null
            }
            publishLocked()
            if (rec != null && pair != null) {
                requestJournalEstimateAsync(folderId, rec.selectedSpan, pair.second)
            }
        }
    }

    fun setFacet(folderId: String, facet: FacetOption?) {
        checks.submit {
            if (engine.setFacet(folderId, facet)) {
                publishLocked()
            }
        }
    }

    fun confirm(folderId: String, span: FolderSpan? = null) {
        checks.submit {
            val chosenSpan = span ?: engine.folderById(folderId)?.selectedSpan ?: FolderSpan.LAST_30_DAYS
            if (engine.confirmFolder(folderId, chosenSpan) == ConfirmResult.Ok) {
                syncRegistryWishLocked()
                checks.scheduleBackground()
                if (engine.hasReadyDelivery(ownerStopped())) {
                    onQueued()
                }
            }
            publishLocked()
        }
    }

    fun pause(folderId: String) {
        checks.submit {
            if (engine.pauseFolder(folderId)) {
                syncRegistryWishLocked()
                publishLocked()
            }
        }
    }

    fun resume(folderId: String) {
        checks.submit {
            if (engine.resumeFolder(folderId)) {
                syncRegistryWishLocked()
                engine.scanAll(onlyFolderId = folderId)
                if (engine.hasReadyDelivery(ownerStopped())) {
                    onQueued()
                }
                publishLocked()
            }
        }
    }

    fun turnOff(folderId: String) {
        checks.submit {
            if (engine.turnOffFolder(folderId)) {
                syncRegistryWishLocked()
                checks.scheduleBackground()
                publishLocked()
            }
        }
    }

    fun turnOn(folderId: String) {
        checks.submit {
            if (engine.turnOnFolder(folderId)) {
                val span = engine.folderById(folderId)?.selectedSpan ?: FolderSpan.LAST_30_DAYS
                val pair = engine.previewFolderWithItems(folderId, span)
                syncRegistryWishLocked()
                publishLocked()
                refreshFacetsAsync()
                if (pair != null) {
                    requestJournalEstimateAsync(folderId, span, pair.second)
                }
            }
        }
    }

    fun remove(folderId: String) {
        checks.submit {
            val removedUri = engine.removeFolder(folderId) ?: return@submit
            // Release persisted SAF grant ONLY if nothing else holds this exact tree URI.
            val stillUsed = engine.folderRecords().any { it.treeUri == removedUri } ||
                obsidianIdentity()?.treeUri == removedUri ||
                screenshotsIdentity()?.treeUri == removedUri
            if (!stillUsed) {
                runCatching { saf.release(Uri.parse(removedUri)) }
            }
            syncRegistryWishLocked()
            checks.scheduleBackground()
            publishLocked()
        }
    }

    /** Pairing Principle 9: new pairing returns folders to setup without losing grants/kinds/facet. */
    fun restartFirstSetup(): Boolean {
        capability = null
        capabilityFingerprint = null
        supportedLoadedForPairing = null
        facets = emptyList()
        facetsState = FacetsAvailability.LOADING
        return checks.restartFirstSetup {
            engine.restartSetup()
            clearWish()
            publishLocked()
            checks.scheduleBackground()
        }
    }

    fun pendingCount(): Int = ui.pendingCount

    fun registryStatus(): FoldersRegistryStatus {
        val list = ui.folders
        if (list.isEmpty()) return FoldersRegistryStatus()
        val activeOrPaused = list.filter { it.mode == FolderMode.ON || it.mode == FolderMode.PAUSED }
        val anyAttention = list.any { it.attention != null }
        val anyLostGrant = list.any { it.attention == FolderAttention.PERMISSION_LOST }
        val anyOn = list.any { it.mode == FolderMode.ON && it.attention == null && !it.pausedByApp }
        val allPaused = activeOrPaused.isNotEmpty() && activeOrPaused.all { it.mode == FolderMode.PAUSED || it.pausedByApp }
        return FoldersRegistryStatus(
            anyConfigured = true,
            anyActiveOrPaused = activeOrPaused.isNotEmpty(),
            anyOn = anyOn,
            allPaused = allPaused,
            anyNeedsAttention = anyAttention,
            allAccessOk = !anyLostGrant,
        )
    }

    /**
     * Runs inside `SyncWithTransport` on the live authenticated `PlHttpClient` (after mark
     * confirmation). Checks `"folder"` capability, refreshes `/supported` and `/facets`,
     * scans once, and delivers closed 5-minute folder segments.
     */
    fun deliverStage(
        client: PlHttpClient,
        pairingCurrent: () -> Boolean,
        clientCertFingerprint: String,
    ): FolderStageResult {
        if (!pairingCurrent()) return FolderStageResult.PAIRING_LEFT

        // 1. Check capability `"folder"`
        val cap = try {
            fetchFolderCapability(client, clientCertFingerprint)
        } catch (e: IOException) {
            throw e
        } catch (_: Exception) {
            null
        }
        if (!pairingCurrent()) return FolderStageResult.PAIRING_LEFT
        if (cap != null) {
            capability = cap
            capabilityFingerprint = clientCertFingerprint
            checks.submit { publishLocked() }
        }
        if (cap == false) {
            return FolderStageResult.CLEAN
        }
        if (cap == null && capability != true) {
            return FolderStageResult.RETRY
        }

        // 2. Refresh `/supported` and `/facets` once per pairing (or on first sync)
        if (supportedLoadedForPairing != clientCertFingerprint) {
            when (val sup = runCatching { fetchFolderSupported(client) }.getOrNull()) {
                SupportedFetchResult.AuthStop -> return FolderStageResult.AUTH_STOP
                is SupportedFetchResult.Loaded -> {
                    supportedLoadedForPairing = clientCertFingerprint
                    checks.blocking(10) { engine.updateSupported(sup.supported) }
                }
                else -> Unit
            }
            when (val fac = runCatching { fetchFolderFacets(client) }.getOrNull()) {
                FacetsFetchResult.AuthStop -> return FolderStageResult.AUTH_STOP
                is FacetsFetchResult.Loaded -> {
                    facets = fac.facets
                    facetsState = if (fac.facets.isEmpty()) FacetsAvailability.EMPTY else FacetsAvailability.LOADED
                    checks.submit { publishLocked() }
                }
                else -> Unit
            }
        }

        if (!pairingCurrent()) return FolderStageResult.PAIRING_LEFT

        // 3. Fresh scan before picking deliveries
        checks.blocking(30) {
            activeSignal.set(CancellationSignal())
            try {
                engine.scanAll()
            } finally {
                activeSignal.set(null)
            }
            publishLocked()
        }

        var hadRetryable = false
        var sentCount = 0

        while (sentCount < MAX_SEGMENTS_PER_SYNC) {
            if (!pairingCurrent()) return FolderStageResult.PAIRING_LEFT
            val delivery = checks.blocking(15) { engine.nextDelivery(ownerStopped()) } ?: break
            val stageDir = File(
                context.cacheDir,
                "folder-stage/${delivery.folderId}-${delivery.window.day}-${delivery.window.segment}-${System.nanoTime()}",
            )
            val reply = try {
                stageDir.mkdirs()
                val parts = stageCompanionParts(delivery, stageDir)
                val prepared = prepareFolderUpload(delivery, parts)
                uploadPreparedFolderSegment(client, prepared)
            } catch (e: IOException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "folder segment upload failed", e)
                FolderUploadReply.Outcome(DeliveryOutcome.Hold)
            } finally {
                stageDir.deleteRecursively()
            }

            if (!pairingCurrent()) return FolderStageResult.PAIRING_LEFT

            when (reply) {
                FolderUploadReply.AuthStop -> return FolderStageResult.AUTH_STOP
                is FolderUploadReply.Outcome -> {
                    checks.blocking(15) {
                        engine.recordOutcome(delivery, reply.outcome)
                        publishLocked()
                    }
                    when (reply.outcome) {
                        DeliveryOutcome.Hold -> {
                            hadRetryable = true
                            break
                        }
                        DeliveryOutcome.Delivered,
                        is DeliveryOutcome.DeliveredWithRefusedContent,
                        is DeliveryOutcome.AllFilesRefused,
                        DeliveryOutcome.InvalidPermanent,
                        -> sentCount++
                    }
                }
            }
        }

        val moreReady = checks.blocking(10) { engine.hasReadyDelivery(ownerStopped()) } == true
        if (moreReady && !hadRetryable) {
            onQueued()
        }
        return if (hadRetryable) FolderStageResult.RETRY else FolderStageResult.CLEAN
    }

    private fun stageCompanionParts(
        delivery: SegmentDelivery,
        stageDir: File,
    ): List<StagedPartPayload> {
        val out = ArrayList<StagedPartPayload>(delivery.contentFiles.size)
        var nextIndex = 1
        val maxContentBytes = engine.supported.maxContentBytes
        val maxImagePixels = engine.supported.maxImagePixels

        for (item in delivery.contentFiles) {
            val origExt = extensionLower(item.relPath)
            val notePayload = item.notePayload
            if (notePayload != null) {
                val ext = origExt ?: "md"
                val partName = String.format(Locale.ROOT, "c_%02d.%s", nextIndex, ext)
                val bytes = notePayload.toByteArray(Charsets.UTF_8)
                val hash = sha256Hex(bytes)
                out.add(
                    StagedPartPayload(
                        eventId = item.eventId,
                        partName = partName,
                        contentHash = hash,
                        mediaType = mediaTypeForPartName(partName),
                        byteLength = bytes.size.toLong(),
                        openStream = { ByteArrayInputStream(bytes) },
                    ),
                )
                nextIndex++
            } else if (item.needsImageConvert) {
                val rawFile = File(stageDir, "raw_$nextIndex.${origExt ?: "img"}")
                val copiedHash = fs.copyToFileAndHash(delivery.treeUri, item.documentId, rawFile, maxContentBytes)
                if (copiedHash != null) {
                    val outExt = if (item.kind == FileKind.SCREENSHOT) "png" else "jpg"
                    val partName = String.format(Locale.ROOT, "c_%02d.%s", nextIndex, outExt)
                    val converted = File(stageDir, partName)
                    val ok = FolderImageConverter.convert(
                        sourceFile = rawFile,
                        extension = origExt.orEmpty(),
                        kind = item.kind,
                        targetFile = converted,
                        maxPixels = maxImagePixels,
                    )
                    rawFile.delete()
                    if (ok && converted.isFile && converted.length() > 0L) {
                        val hash = sha256Hex(converted.readBytes())
                        out.add(
                            StagedPartPayload(
                                eventId = item.eventId,
                                partName = partName,
                                contentHash = hash,
                                mediaType = mediaTypeForPartName(partName),
                                byteLength = converted.length(),
                                openStream = { FileInputStream(converted) },
                            ),
                        )
                        nextIndex++
                    } else {
                        converted.delete()
                    }
                }
            } else {
                val ext = origExt ?: "bin"
                val partName = String.format(Locale.ROOT, "c_%02d.%s", nextIndex, ext)
                val staged = File(stageDir, partName)
                val hash = fs.copyToFileAndHash(delivery.treeUri, item.documentId, staged, maxContentBytes)
                if (hash != null && staged.isFile) {
                    out.add(
                        StagedPartPayload(
                            eventId = item.eventId,
                            partName = partName,
                            contentHash = hash,
                            mediaType = mediaTypeForPartName(partName),
                            byteLength = staged.length(),
                            openStream = { FileInputStream(staged) },
                        ),
                    )
                    nextIndex++
                }
            }
        }
        return out
    }

    private fun refreshFacetsAsync() {
        val opener = openLiveClient ?: return
        networkExecutor.execute {
            val client = runCatching { opener() }.getOrNull() ?: return@execute
            try {
                val cap = runCatching { fetchFolderCapability(client) }.getOrNull()
                if (cap != null) capability = cap
                if (cap == false) {
                    facetsState = FacetsAvailability.UNAVAILABLE
                    checks.submit { publishLocked() }
                    return@execute
                }
                when (val sup = runCatching { fetchFolderSupported(client) }.getOrNull()) {
                    is SupportedFetchResult.Loaded -> checks.submit { engine.updateSupported(sup.supported) }
                    else -> Unit
                }
                when (val res = runCatching { fetchFolderFacets(client) }.getOrNull()) {
                    is FacetsFetchResult.Loaded -> {
                        facets = res.facets
                        facetsState = if (res.facets.isEmpty()) FacetsAvailability.EMPTY else FacetsAvailability.LOADED
                    }
                    else -> {
                        if (facets.isEmpty()) facetsState = FacetsAvailability.UNAVAILABLE
                    }
                }
                checks.submit { publishLocked() }
            } finally {
                (client as? Closeable)?.let { runCatching { it.close() } }
            }
        }
    }

    private fun requestJournalEstimateAsync(
        folderId: String,
        span: FolderSpan,
        items: List<EstimateItem>,
    ) {
        val opener = openLiveClient ?: return
        networkExecutor.execute {
            val client = runCatching { opener() }.getOrNull() ?: return@execute
            try {
                val cap = runCatching { fetchFolderCapability(client) }.getOrNull()
                if (cap != null) capability = cap
                if (cap == false) {
                    checks.submit { publishLocked() }
                    return@execute
                }
                when (val res = runCatching { fetchFolderEstimate(client, folderId, items) }.getOrNull()) {
                    is EstimateFetchResult.Loaded -> {
                        checks.submit {
                            engine.setJournalEstimate(folderId, span, res.estimate)
                            publishLocked()
                        }
                    }
                    else -> Unit
                }
            } finally {
                (client as? Closeable)?.let { runCatching { it.close() } }
            }
        }
    }

    private fun runCheck(signal: CancellationSignal, fromBackground: Boolean) {
        activeSignal.set(signal)
        try {
            if (hasTrackedFolders()) {
                engine.scanAll()
                if (engine.hasReadyDelivery(ownerStopped())) {
                    onQueued()
                }
            }
            if (fromBackground) {
                lastBackgroundCheckMs = clock.nowMs()
            }
            publishLocked()
        } finally {
            activeSignal.set(null)
        }
    }

    private fun hasTrackedFolders(): Boolean =
        engine.folderRecords().any { it.mode == FolderMode.ON || it.mode == FolderMode.PAUSED }

    private fun syncRegistryWishLocked() {
        val records = engine.folderRecords()
        when {
            records.isEmpty() -> clearWish()
            records.any { it.mode == FolderMode.ON || it.mode == FolderMode.PAUSED } -> {
                if (!wishOn()) setEnabled(true)
            }
            records.all { it.mode == FolderMode.OFF } -> {
                if (wishOn()) setEnabled(false)
            }
            else -> Unit
        }
    }

    private fun publishLocked() {
        val appPaused = ownerStopped()
        val summaries = engine.summaries(appPaused)
        val pending = engine.pendingCount()
        ui = FoldersUi(
            folders = summaries,
            facets = facets,
            facetsState = facetsState,
            capability = capability,
            pickerNotice = pickerNotice,
            checking = checking,
            pendingCount = pending,
            newlyAddedFolderId = newlyAddedFolderId,
            lastBackgroundCheckMs = lastBackgroundCheckMs,
        )
        listener?.invoke()
        onChanged()
    }
}
