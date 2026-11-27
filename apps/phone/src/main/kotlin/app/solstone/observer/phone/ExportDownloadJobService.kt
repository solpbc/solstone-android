// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Android 14+ (API 34+) user-initiated data transfer [JobService] for native journal export saves.
 * Uses `JobInfo.Builder.setUserInitiated(true)` and `setNotification(...)` with zero `dataSync`
 * foreground service. On API 26–33, [AndroidExportExecutionScheduler] runs the same resumable
 * download loop directly on an in-process daemon executor with persisted pause/resume state.
 */
class ExportDownloadJobService : JobService() {
    @Volatile
    private var currentParams: JobParameters? = null

    override fun onStartJob(params: JobParameters): Boolean {
        val app = applicationContext
        val manager = JournalExportTransferManager.get(app)
        if (manager.isLoopRunning()) {
            // Another runner (such as the direct-LAN fallback) already owns this export transfer.
            runCatching { jobFinished(params, false) }
            return false
        }
        currentParams = params
        activeService.set(this)
        startedJobCountForTests.incrementAndGet()

        val initial = manager.currentSnapshot()
        // Post mandatory UIDT notification synchronously on main thread with zero blocking I/O.
        postJobNotification(params, initial, manager.activeFilename() ?: "")

        jobExecutor.execute {
            var reschedule = false
            try {
                reschedule = manager.runTransferAttemptBlocking()
            } finally {
                activeService.compareAndSet(this, null)
                currentParams = null
                runCatching {
                    jobFinished(params, reschedule)
                    finishedJobCountForTests.incrementAndGet()
                }
                if (!reschedule && !manager.isLoopRunning()) {
                    manager.rescheduleIfTransferring()
                }
                // UIDT jobs use JOB_END_NOTIFICATION_POLICY_REMOVE, so keep a paused notification
                // with Resume and Cancel controls visible on a separate notification ID.
                repostPausedNotificationIfNeeded(manager) { snap, name ->
                    ExportDownloadNotification.postStandalone(app, snap, name)
                }
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        val app = applicationContext
        val manager = JournalExportTransferManager.get(app)
        // Preserve partial SAF document and persisted offset when the OS stops the job.
        manager.pauseForOsStop()
        activeService.compareAndSet(this, null)
        currentParams = null
        repostPausedNotificationIfNeeded(manager) { snap, name ->
            ExportDownloadNotification.postStandalone(app, snap, name)
        }
        return false
    }

    internal fun postJobNotification(
        params: JobParameters,
        snapshot: JournalExportStatusSnapshot,
        filename: String,
    ) {
        if (Build.VERSION.SDK_INT < 34) return
        val notification = ExportDownloadNotification.build(
            context = applicationContext,
            filename = filename.ifBlank { snapshot.destinationDisplay },
            bytesWritten = snapshot.bytesWritten,
            bytesTotal = snapshot.totalBytes,
            phase = snapshot.phase,
            exportId = snapshot.exportId,
        )
        runCatching {
            setNotification(
                params,
                ExportDownloadNotification.NOTIFICATION_ID,
                notification,
                JOB_END_NOTIFICATION_POLICY_REMOVE,
            )
            uidtNotificationsPostedForTests.incrementAndGet()
        }
    }

    companion object {
        private const val NOTIFY_MIN_INTERVAL_MS = 400L

        private val jobExecutor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "export-download-uidt").apply { isDaemon = true }
        }

        private val activeService = AtomicReference<ExportDownloadJobService?>(null)
        private val lastNotifyAtMs = AtomicLong(0L)
        private val lastPhase = AtomicReference<String?>(null)
        internal val startedJobCountForTests = AtomicInteger(0)
        internal val uidtNotificationsPostedForTests = AtomicInteger(0)
        internal val finishedJobCountForTests = AtomicInteger(0)

        internal fun resetTestCounters() {
            startedJobCountForTests.set(0)
            uidtNotificationsPostedForTests.set(0)
            finishedJobCountForTests.set(0)
        }

        internal fun isActive(): Boolean = activeService.get() != null

        internal fun repostPausedNotificationIfNeeded(
            manager: JournalExportTransferManager,
            postStandalone: (JournalExportStatusSnapshot, String) -> Unit,
        ): Boolean {
            val snap = manager.currentSnapshot()
            if (snap.phase != "paused") return false
            postStandalone(snap, manager.activeFilename() ?: "")
            return true
        }

        internal fun updateNotificationIfActive(snapshot: JournalExportStatusSnapshot, filename: String) {
            val svc = activeService.get() ?: return
            val params = svc.currentParams ?: return
            val nowMs = System.currentTimeMillis()
            val phaseChanged = lastPhase.getAndSet(snapshot.phase) != snapshot.phase
            val prevAt = lastNotifyAtMs.get()
            if (phaseChanged || (nowMs - prevAt) >= NOTIFY_MIN_INTERVAL_MS) {
                lastNotifyAtMs.set(nowMs)
                svc.postJobNotification(params, snapshot, filename)
            }
        }
    }
}

internal object ExportDownloadScheduler {
    const val JOB_ID = 7402

    /**
     * Returns true only when Android reports an active network satisfying JobScheduler's
     * `NETWORK_TYPE_ANY` UIDT connectivity constraint (`INTERNET` + `TRUSTED` + `VALIDATED`,
     * plus `NOT_BANDWIDTH_CONSTRAINED` on API 35+).
     *
     * On local Wi-Fi / LAN networks without validated internet (or isolated networks),
     * UIDT jobs stay queued with unsatisfied `CONNECTIVITY` even when a direct journal is
     * reachable; callers fall back to in-process execution so local saves succeed and genuine
     * disconnects pause honestly instead of hanging at 0 B.
     */
    internal fun hasValidatedNetwork(context: Context): Boolean {
        val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java)
            ?: return false
        val activeNetwork = cm.activeNetwork ?: return false
        val caps = runCatching { cm.getNetworkCapabilities(activeNetwork) }.getOrNull()
        return capsSatisfyUidtNetwork(caps, Build.VERSION.SDK_INT)
    }

    internal fun capsSatisfyUidtNetwork(
        caps: NetworkCapabilities?,
        sdkInt: Int = Build.VERSION.SDK_INT,
    ): Boolean {
        if (caps == null) return false
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return false
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_TRUSTED)) return false
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return false
        if (sdkInt >= 35 && !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_BANDWIDTH_CONSTRAINED)) {
            return false
        }
        return true
    }

    internal fun scheduleUidtJob(context: Context, estimatedDownloadBytes: Long): Boolean {
        if (Build.VERSION.SDK_INT < 34) return false
        val app = context.applicationContext
        val scheduler = app.getSystemService(JobScheduler::class.java) ?: return false
        if (ExportDownloadJobService.isActive()) {
            return true
        }
        return runCatching {
            val info = JobInfo.Builder(
                JOB_ID,
                ComponentName(app, ExportDownloadJobService::class.java),
            )
                .setUserInitiated(true)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setEstimatedNetworkBytes(estimatedDownloadBytes.coerceAtLeast(1L), 1024L * 1024L)
                .build()
            scheduler.schedule(info) == JobScheduler.RESULT_SUCCESS
        }.getOrDefault(false)
    }

    fun cancelJob(context: Context) {
        val scheduler = context.applicationContext.getSystemService(JobScheduler::class.java) ?: return
        runCatching { scheduler.cancel(JOB_ID) }
    }
}
