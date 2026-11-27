// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.net.Network
import android.os.Build
import app.solstone.core.observer.RESUMABLE_UPLOAD_PHASE_PAUSED
import app.solstone.core.observer.RESUMABLE_UPLOAD_PHASE_SENDING
import app.solstone.core.sources.MAIN_STREAM
import app.solstone.platform.persistence.room.IMPORT_STATE_PENDING
import app.solstone.platform.persistence.room.ImportCustodyStore
import app.solstone.platform.persistence.room.ImportUploadProgressEvent
import app.solstone.platform.work.ResumableImportRunOutcome
import app.solstone.platform.work.ResumableImportSessionControl
import app.solstone.platform.work.ResumableThresholdHintStore
import app.solstone.platform.work.SyncScheduler
import app.solstone.platform.work.importStoreRoot
import app.solstone.platform.work.runResumableImportUploadLoop
import app.solstone.platform.work.syncStores
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Android 14+ (API 34+) user-initiated data transfer [JobService] for large resumable imports.
 * Uses `JobInfo.Builder.setUserInitiated(true)` and `setNotification(...)` on validated networks
 * (zero `dataSync` foreground service), handles network transitions via [onNetworkChanged], and
 * respects `SyncDrainGate.closed` (age-signal stop).
 *
 * On local Wi-Fi / LAN networks without validated internet (where JobScheduler's
 * `NETWORK_TYPE_ANY` and WorkManager's `NetworkType.CONNECTED` both require
 * `NET_CAPABILITY_VALIDATED` and would otherwise stall direct-journal uploads),
 * [startLocalFallback] runs the exact same [runResumableImportUploadLoop] on the shared
 * single-threaded [jobExecutor] under the shared [activeControl] CAS guard so at most one
 * runner can own any upload offset at a time.
 */
class ImportUploadJobService : JobService() {
    @Volatile
    private var lastNetwork: Network? = null

    @Volatile
    private var currentParams: JobParameters? = null

    @Volatile
    private var currentControl: ResumableImportSessionControl? = null

    @Volatile
    private var hasRemainingWork: Boolean = true

    private var progressListener: ((ImportUploadProgressEvent) -> Unit)? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val app = applicationContext
        val control = ResumableImportSessionControl()
        if (!activeControl.compareAndSet(null, control)) {
            // Another import runner (UIDT or direct-LAN fallback) already owns the upload queue.
            duplicateSuppressedCountForTests.incrementAndGet()
            wakeOnNetworkRecovery()
            runCatching { jobFinished(params, false) }
            return false
        }
        ImportUploadNotification.cancelStandalone(app)
        currentParams = params
        currentControl = control
        hasRemainingWork = true
        startedUidtJobCountForTests.incrementAndGet()
        if (Build.VERSION.SDK_INT >= 28) {
            lastNetwork = params.network
        }

        // Post the mandatory UIDT notification synchronously on main thread with zero Room I/O;
        // the background worker updates it immediately with confirmed progress once started.
        postJobNotification(
            params = params,
            filename = null,
            bytesSent = 0L,
            bytesTotal = 0L,
            phase = RESUMABLE_UPLOAD_PHASE_SENDING,
            itemId = null,
        )

        val lastNotifyAtMs = AtomicLong(0L)
        val lastPhase = AtomicReference<String?>(null)
        val listener: (ImportUploadProgressEvent) -> Unit = { event ->
            val nowMs = System.currentTimeMillis()
            val phaseChanged = lastPhase.getAndSet(event.phase) != event.phase
            val prevAt = lastNotifyAtMs.get()
            if (phaseChanged || (nowMs - prevAt) >= NOTIFY_MIN_INTERVAL_MS) {
                lastNotifyAtMs.set(nowMs)
                val name = ImportIntake.get(app).store.item(event.itemId)?.displayName
                postJobNotification(
                    params = params,
                    filename = name,
                    bytesSent = event.bytesSent,
                    bytesTotal = event.bytesTotal,
                    phase = event.phase,
                    itemId = event.itemId,
                )
            }
        }
        progressListener = listener
        ImportCustodyStore.addProgressListener(listener)

        jobExecutor.execute {
            var reschedule = false
            try {
                val intake = ImportIntake.get(app)
                val initialRow = intake.store.items(null, null).firstOrNull {
                    it.state == IMPORT_STATE_PENDING && (it.uploadResumable || it.inFlight)
                } ?: intake.store.items(null, null).firstOrNull { it.state == IMPORT_STATE_PENDING }
                if (initialRow != null) {
                    postJobNotification(
                        params = params,
                        filename = initialRow.displayName,
                        bytesSent = initialRow.uploadOffset,
                        bytesTotal = initialRow.byteSize,
                        phase = initialRow.uploadPhase ?: RESUMABLE_UPLOAD_PHASE_SENDING,
                        itemId = initialRow.id,
                    )
                }
                val stores = syncStores(app)
                val instanceId = stores.identityStore.load()?.instanceId
                val outcome = runResumableImportUploadLoop(
                    stores = stores,
                    importStore = intake.store,
                    control = control,
                    onObservedThreshold = instanceId?.let { id ->
                        { threshold -> ResumableThresholdHintStore(importStoreRoot(app)).record(id, threshold) }
                    },
                )
                reschedule = outcome == ResumableImportRunOutcome.RESCHEDULE
                hasRemainingWork = reschedule
            } finally {
                ImportCustodyStore.removeProgressListener(listener)
                progressListener = null
                activeControl.compareAndSet(control, null)
                currentControl = null
                currentParams = null
                finishedRunsCountForTests.incrementAndGet()
                runCatching { jobFinished(params, reschedule) }
            }
        }
        return true
    }

    override fun onNetworkChanged(params: JobParameters) {
        currentParams = params
        if (Build.VERSION.SDK_INT >= 28) {
            val nextNetwork = params.network
            val switched = nextNetwork != null && lastNetwork != null && nextNetwork != lastNetwork
            lastNetwork = nextNetwork
            currentControl?.signalNetworkChange(interruptActiveConnection = switched)
        } else {
            currentControl?.signalNetworkChange(interruptActiveConnection = false)
        }
    }

    override fun onStopJob(params: JobParameters): Boolean {
        val control = currentControl
        control?.requestStop()
        activeControl.compareAndSet(control, null)
        progressListener?.let { ImportCustodyStore.removeProgressListener(it) }
        progressListener = null
        currentParams = null
        // Let JobScheduler reschedule the UIDT job when constraints recover if work remains.
        return hasRemainingWork
    }

    private fun postJobNotification(
        params: JobParameters,
        filename: String?,
        bytesSent: Long,
        bytesTotal: Long,
        phase: String?,
        itemId: String?,
    ) {
        if (Build.VERSION.SDK_INT < 34) return
        val notification = ImportUploadNotification.build(
            context = applicationContext,
            filename = filename,
            bytesSent = bytesSent,
            bytesTotal = bytesTotal,
            phase = phase,
            itemId = itemId,
        )
        runCatching {
            setNotification(
                params,
                ImportUploadNotification.NOTIFICATION_ID,
                notification,
                JOB_END_NOTIFICATION_POLICY_REMOVE,
            )
        }
    }

    companion object {
        private const val NOTIFY_MIN_INTERVAL_MS = 400L
        internal const val LOCAL_FALLBACK_MAX_UNREACHABLE_RETRIES = 2

        private val jobExecutor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "import-upload-uidt").apply { isDaemon = true }
        }

        private val activeControl = AtomicReference<ResumableImportSessionControl?>(null)
        private val localFallbackActive = AtomicBoolean(false)

        internal val startedUidtJobCountForTests = AtomicInteger(0)
        internal val startedLocalFallbackCountForTests = AtomicInteger(0)
        internal val duplicateSuppressedCountForTests = AtomicInteger(0)
        internal val finishedRunsCountForTests = AtomicInteger(0)

        internal fun resetTestCounters() {
            startedUidtJobCountForTests.set(0)
            startedLocalFallbackCountForTests.set(0)
            duplicateSuppressedCountForTests.set(0)
            finishedRunsCountForTests.set(0)
        }

        internal fun stopActiveForTests() {
            activeControl.get()?.requestStop()
        }

        internal fun wakeOnNetworkRecovery() {
            activeControl.get()?.signalNetworkChange(interruptActiveConnection = false)
        }

        internal fun wakeForCancel() {
            activeControl.get()?.signalNetworkChange(interruptActiveConnection = false)
        }

        internal fun isActive(): Boolean = activeControl.get() != null

        internal fun isLocalFallbackActive(): Boolean = localFallbackActive.get()

        /**
         * Run resumable import delivery directly on [jobExecutor] when the active network has no
         * validated internet (so UIDT `NETWORK_TYPE_ANY` and WorkManager `NetworkType.CONNECTED`
         * would otherwise stall). Shares [activeControl] with [onStartJob] so at most one runner
         * owns each upload offset.
         */
        internal fun startLocalFallback(
            context: Context,
            maxUnreachableRetries: Int = LOCAL_FALLBACK_MAX_UNREACHABLE_RETRIES,
        ): Boolean {
            val app = context.applicationContext
            val control = ResumableImportSessionControl()
            if (!activeControl.compareAndSet(null, control)) {
                duplicateSuppressedCountForTests.incrementAndGet()
                wakeOnNetworkRecovery()
                return false
            }
            localFallbackActive.set(true)
            startedLocalFallbackCountForTests.incrementAndGet()

            val lastNotifyAtMs = AtomicLong(0L)
            val lastPhase = AtomicReference<String?>(null)
            val listener: (ImportUploadProgressEvent) -> Unit = { event ->
                val nowMs = System.currentTimeMillis()
                val phaseChanged = lastPhase.getAndSet(event.phase) != event.phase
                val prevAt = lastNotifyAtMs.get()
                if (phaseChanged || (nowMs - prevAt) >= NOTIFY_MIN_INTERVAL_MS) {
                    lastNotifyAtMs.set(nowMs)
                    val name = ImportIntake.get(app).store.item(event.itemId)?.displayName
                    ImportUploadNotification.postStandalone(
                        context = app,
                        filename = name,
                        bytesSent = event.bytesSent,
                        bytesTotal = event.bytesTotal,
                        phase = event.phase,
                        itemId = event.itemId,
                    )
                }
            }
            ImportCustodyStore.addProgressListener(listener)

            jobExecutor.execute {
                try {
                    val intake = ImportIntake.get(app)
                    val stores = syncStores(app)
                    val instanceId = stores.identityStore.load()?.instanceId
                    val initialRow = intake.store.items(instanceId, null).firstOrNull {
                        it.state == IMPORT_STATE_PENDING && (it.uploadResumable || it.inFlight)
                    } ?: intake.store.items(instanceId, null).firstOrNull { it.state == IMPORT_STATE_PENDING }
                    if (initialRow != null) {
                        ImportUploadNotification.postStandalone(
                            context = app,
                            filename = initialRow.displayName,
                            bytesSent = initialRow.uploadOffset,
                            bytesTotal = initialRow.byteSize,
                            phase = initialRow.uploadPhase ?: RESUMABLE_UPLOAD_PHASE_SENDING,
                            itemId = initialRow.id,
                        )
                    }
                    runResumableImportUploadLoop(
                        stores = stores,
                        importStore = intake.store,
                        control = control,
                        onObservedThreshold = instanceId?.let { id ->
                            { threshold -> ResumableThresholdHintStore(importStoreRoot(app)).record(id, threshold) }
                        },
                        onRetryWaiting = { attempt -> attempt < maxUnreachableRetries },
                    )
                } finally {
                    ImportCustodyStore.removeProgressListener(listener)
                    localFallbackActive.set(false)
                    activeControl.compareAndSet(control, null)
                    finishedRunsCountForTests.incrementAndGet()
                    // Update or clear the standalone notification accurately: if a resumable item
                    // paused mid-upload with confirmed bytes, show paused; if finished or waiting
                    // at 0 B because the journal is unreachable, clear the active sending notice.
                    val remainingPartial = runCatching {
                        val stores = syncStores(app)
                        val instanceId = stores.identityStore.load()?.instanceId
                        ImportIntake.get(app).store.items(instanceId, null).firstOrNull {
                            it.state == IMPORT_STATE_PENDING &&
                                it.uploadResumable &&
                                it.uploadId != null &&
                                it.uploadOffset > 0L
                        }
                    }.getOrNull()
                    if (remainingPartial != null) {
                        ImportUploadNotification.postStandalone(
                            context = app,
                            filename = remainingPartial.displayName,
                            bytesSent = remainingPartial.uploadOffset,
                            bytesTotal = remainingPartial.byteSize,
                            phase = RESUMABLE_UPLOAD_PHASE_PAUSED,
                            itemId = remainingPartial.id,
                        )
                    } else {
                        ImportUploadNotification.cancelStandalone(app)
                    }
                }
            }
            return true
        }
    }
}

internal enum class ImportScheduleRoute {
    UIDT_JOB,
    LOCAL_FALLBACK,
    WORK_MANAGER,
    WAKE_ACTIVE,
    NONE,
}

internal object ImportUploadScheduler {
    const val JOB_ID = 7401
    // Scheduling hint only before the first /app/import/api/sources read; never used in protocol routing.
    private const val INITIAL_UIDT_HINT_BYTES = 16L * 1024L * 1024L

    internal fun decideScheduleRoute(
        sdkInt: Int,
        hasValidatedNetwork: Boolean,
        hasPending: Boolean,
        hasLargeOrResumable: Boolean,
        isActive: Boolean,
        isResumeOnly: Boolean,
    ): ImportScheduleRoute {
        if (!hasPending) return ImportScheduleRoute.NONE
        if (isActive) return ImportScheduleRoute.WAKE_ACTIVE
        if (sdkInt >= 34 && hasLargeOrResumable && hasValidatedNetwork) {
            return ImportScheduleRoute.UIDT_JOB
        }
        if (!hasValidatedNetwork) {
            return ImportScheduleRoute.LOCAL_FALLBACK
        }
        return if (isResumeOnly) ImportScheduleRoute.NONE else ImportScheduleRoute.WORK_MANAGER
    }

    /**
     * Schedule import delivery after foreground intake, retry, or resume. On API 34+ with a
     * validated network, prefers UIDT [ImportUploadJobService] when any pending import is already
     * resumable or at least the last journal-advertised threshold. On unvalidated local Wi-Fi /
     * LAN networks, routes directly to the single-owner in-process loop so direct-journal uploads
     * do not stall on `NET_CAPABILITY_VALIDATED`.
     */
    fun scheduleForImports(context: Context) {
        val app = context.applicationContext
        val store = ImportIntake.get(app).store
        val instanceId = runCatching { syncStores(app).identityStore.load()?.instanceId }.getOrNull()
        val hintThreshold = ResumableThresholdHintStore(importStoreRoot(app)).readFor(instanceId)
            ?: INITIAL_UIDT_HINT_BYTES
        val pending = store.items(instanceId, null).filter { it.state == IMPORT_STATE_PENDING }
        val largeOrResumable = pending.filter { it.uploadResumable || it.byteSize >= hintThreshold }
        val hasValidated = ExportDownloadScheduler.hasValidatedNetwork(app)

        when (
            decideScheduleRoute(
                sdkInt = Build.VERSION.SDK_INT,
                hasValidatedNetwork = hasValidated,
                hasPending = pending.isNotEmpty(),
                hasLargeOrResumable = largeOrResumable.isNotEmpty(),
                isActive = ImportUploadJobService.isActive(),
                isResumeOnly = false,
            )
        ) {
            ImportScheduleRoute.WAKE_ACTIVE -> {
                ImportUploadJobService.wakeOnNetworkRecovery()
            }
            ImportScheduleRoute.UIDT_JOB -> {
                val estimatedUploadBytes = largeOrResumable
                    .sumOf { (it.byteSize - it.uploadOffset).coerceAtLeast(0L) }
                    .coerceAtLeast(INITIAL_UIDT_HINT_BYTES)
                if (!scheduleUidtJob(app, estimatedUploadBytes)) {
                    SyncScheduler.enqueueFollowUp(app, MAIN_STREAM)
                }
            }
            ImportScheduleRoute.LOCAL_FALLBACK -> {
                if (!ImportUploadJobService.isActive()) {
                    cancelJob(app)
                }
                ImportUploadJobService.startLocalFallback(app)
            }
            ImportScheduleRoute.WORK_MANAGER -> {
                SyncScheduler.enqueueFollowUp(app, MAIN_STREAM)
            }
            ImportScheduleRoute.NONE -> {
                SyncScheduler.enqueueFollowUp(app, MAIN_STREAM)
            }
        }
    }

    /**
     * On foreground resume or network recovery, re-arm the API 34+ UIDT job on validated networks
     * or restart the single-owner local fallback on unvalidated local Wi-Fi / LAN if any pending
     * import is waiting and no runner is currently active. No-op when idle.
     */
    fun resumePendingLargeImports(context: Context) {
        if (ImportUploadJobService.isActive()) {
            ImportUploadJobService.wakeOnNetworkRecovery()
            return
        }
        val app = context.applicationContext
        val store = ImportIntake.get(app).store
        val instanceId = runCatching { syncStores(app).identityStore.load()?.instanceId }.getOrNull()
        val hintThreshold = ResumableThresholdHintStore(importStoreRoot(app)).readFor(instanceId)
            ?: INITIAL_UIDT_HINT_BYTES
        val pending = store.items(instanceId, null).filter {
            it.state == IMPORT_STATE_PENDING && !it.inFlight
        }
        val largeOrResumable = pending.filter { it.uploadResumable || it.byteSize >= hintThreshold }
        val hasValidated = ExportDownloadScheduler.hasValidatedNetwork(app)

        when (
            decideScheduleRoute(
                sdkInt = Build.VERSION.SDK_INT,
                hasValidatedNetwork = hasValidated,
                hasPending = pending.isNotEmpty(),
                hasLargeOrResumable = largeOrResumable.isNotEmpty(),
                isActive = ImportUploadJobService.isActive(),
                isResumeOnly = true,
            )
        ) {
            ImportScheduleRoute.UIDT_JOB -> {
                val estimatedUploadBytes = largeOrResumable
                    .sumOf { (it.byteSize - it.uploadOffset).coerceAtLeast(0L) }
                    .coerceAtLeast(INITIAL_UIDT_HINT_BYTES)
                scheduleUidtJob(app, estimatedUploadBytes)
            }
            ImportScheduleRoute.LOCAL_FALLBACK -> {
                if (!ImportUploadJobService.isActive()) {
                    cancelJob(app)
                }
                ImportUploadJobService.startLocalFallback(app)
            }
            ImportScheduleRoute.WAKE_ACTIVE -> {
                ImportUploadJobService.wakeOnNetworkRecovery()
            }
            ImportScheduleRoute.WORK_MANAGER,
            ImportScheduleRoute.NONE,
            -> Unit
        }
    }

    internal fun scheduleUidtJob(context: Context, estimatedUploadBytes: Long): Boolean {
        if (Build.VERSION.SDK_INT < 34) return false
        val app = context.applicationContext
        val scheduler = app.getSystemService(JobScheduler::class.java) ?: return false
        if (ImportUploadJobService.isActive()) {
            ImportUploadJobService.wakeOnNetworkRecovery()
            return true
        }
        return runCatching {
            val info = JobInfo.Builder(
                JOB_ID,
                ComponentName(app, ImportUploadJobService::class.java),
            )
                .setUserInitiated(true)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setEstimatedNetworkBytes(1024L * 1024L, estimatedUploadBytes.coerceAtLeast(1L))
                .build()
            scheduler.schedule(info) == JobScheduler.RESULT_SUCCESS
        }.getOrDefault(false)
    }

    fun cancelJob(context: Context) {
        val scheduler = context.applicationContext.getSystemService(JobScheduler::class.java) ?: return
        runCatching { scheduler.cancel(JOB_ID) }
    }
}
