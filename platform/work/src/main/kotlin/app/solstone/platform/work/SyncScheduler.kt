// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object SyncScheduler {
    const val PERIODIC_WORK_NAME = "solstone-sync-periodic"
    const val NOW_WORK_NAME = "solstone-sync-now"
    const val STREAM_TYPE_KEY = "stream_type"
    internal val PERIODIC_WORK_POLICY = ExistingPeriodicWorkPolicy.UPDATE

    fun enqueuePeriodic(context: Context, streamType: String) {
        val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(networkConstraints())
            .setInputData(streamInputData(streamType))
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
            PERIODIC_WORK_NAME,
            PERIODIC_WORK_POLICY,
            request,
        )
    }

    fun enqueueNow(context: Context, streamType: String) {
        val workManager = WorkManager.getInstance(context.applicationContext)
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setConstraints(networkConstraints())
            .setInputData(streamInputData(streamType))
            .build()
        // The policy depends on what is already there, read off the caller's thread: this is
        // called from the main thread at startup and from the owner's own "sync now".
        val existing = workManager.getWorkInfosForUniqueWork(NOW_WORK_NAME)
        existing.addListener(
            {
                val policy = nowWorkPolicy(runCatching { existing.get() }.getOrNull())
                workManager.enqueueUniqueWork(NOW_WORK_NAME, policy, request)
            },
            Runnable::run,
        )
    }

    /**
     * A request replaces a sync that is only waiting, and never one that is running.
     *
     * ⛔ Not KEEP throughout: after an unreachable journal the waiting sync sits in retry backoff,
     * which grows to hours, and KEEP dropped every request behind it, the owner's own "sync now"
     * and each finished segment's included. A running sync is kept, because replacing it would
     * cancel a drain mid-upload, and a running drain picks up what sealed under it. Unknown state
     * keeps too, the old behaviour.
     */
    internal fun nowWorkPolicy(existing: List<WorkInfo>?): ExistingWorkPolicy =
        when {
            existing == null -> ExistingWorkPolicy.KEEP
            existing.any { it.state == WorkInfo.State.RUNNING } -> ExistingWorkPolicy.KEEP
            else -> ExistingWorkPolicy.REPLACE
        }

    internal fun networkConstraints(): Constraints =
        Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

    internal fun streamInputData(streamType: String): Data =
        Data.Builder()
            .putString(STREAM_TYPE_KEY, streamType)
            .build()
}
