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
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setConstraints(networkConstraints())
            .setInputData(streamInputData(streamType))
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            NOW_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request,
        )
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
