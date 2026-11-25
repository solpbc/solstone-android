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
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Android 14+ (API 34+) user-initiated data transfer [JobService] for large resumable imports.
 * Uses `JobInfo.Builder.setUserInitiated(true)` and `setNotification(...)` rather than a
 * `dataSync` foreground service, handles network transitions via [onNetworkChanged], and respects
 * `SyncDrainGate.closed` (age-signal stop).
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
        currentParams = params
        hasRemainingWork = true
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

        val control = ResumableImportSessionControl()
        currentControl = control
        activeControl.set(control)

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

        private val jobExecutor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "import-upload-uidt").apply { isDaemon = true }
        }

        private val activeControl = AtomicReference<ResumableImportSessionControl?>(null)

        internal fun wakeOnNetworkRecovery() {
            activeControl.get()?.signalNetworkChange(interruptActiveConnection = false)
        }

        internal fun wakeForCancel() {
            activeControl.get()?.signalNetworkChange(interruptActiveConnection = false)
        }

        internal fun isActive(): Boolean = activeControl.get() != null
    }
}

internal object ImportUploadScheduler {
    const val JOB_ID = 7401
    // Scheduling hint only before the first /app/import/api/sources read; never used in protocol routing.
    private const val INITIAL_UIDT_HINT_BYTES = 16L * 1024L * 1024L

    /**
     * Schedule import delivery after foreground intake, retry, or resume. On API 34+, prefers a
     * user-initiated data transfer job when any pending import is already resumable or at least
     * the last journal-advertised threshold, falling back cleanly to WorkManager if UIDT cannot
     * be scheduled from the current process state or on API 26–33.
     */
    fun scheduleForImports(context: Context) {
        val app = context.applicationContext
        val store = ImportIntake.get(app).store
        val instanceId = runCatching { syncStores(app).identityStore.load()?.instanceId }.getOrNull()
        val hintThreshold = ResumableThresholdHintStore(importStoreRoot(app)).readFor(instanceId)
            ?: INITIAL_UIDT_HINT_BYTES
        val pending = store.items(instanceId, null).filter { it.state == IMPORT_STATE_PENDING }
        val largeOrResumable = pending.filter { it.uploadResumable || it.byteSize >= hintThreshold }

        if (Build.VERSION.SDK_INT >= 34 && largeOrResumable.isNotEmpty()) {
            val estimatedUploadBytes = largeOrResumable
                .sumOf { (it.byteSize - it.uploadOffset).coerceAtLeast(0L) }
                .coerceAtLeast(INITIAL_UIDT_HINT_BYTES)
            if (scheduleUidtJob(app, estimatedUploadBytes)) {
                return
            }
        }
        SyncScheduler.enqueueFollowUp(app, MAIN_STREAM)
    }

    /**
     * On foreground resume (e.g. after process kill), re-arm the API 34+ user-initiated job if any
     * paused large/resumable import is waiting and no job is currently active. No-op when idle.
     */
    fun resumePendingLargeImports(context: Context) {
        if (Build.VERSION.SDK_INT < 34 || ImportUploadJobService.isActive()) return
        val app = context.applicationContext
        val store = ImportIntake.get(app).store
        val instanceId = runCatching { syncStores(app).identityStore.load()?.instanceId }.getOrNull()
        val hintThreshold = ResumableThresholdHintStore(importStoreRoot(app)).readFor(instanceId)
            ?: INITIAL_UIDT_HINT_BYTES
        val largeOrResumable = store.items(instanceId, null).filter {
            it.state == IMPORT_STATE_PENDING && !it.inFlight && (it.uploadResumable || it.byteSize >= hintThreshold)
        }
        if (largeOrResumable.isEmpty()) return
        val estimatedUploadBytes = largeOrResumable
            .sumOf { (it.byteSize - it.uploadOffset).coerceAtLeast(0L) }
            .coerceAtLeast(INITIAL_UIDT_HINT_BYTES)
        scheduleUidtJob(app, estimatedUploadBytes)
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
