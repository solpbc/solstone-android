// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.glasses

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkRequest
import java.util.concurrent.TimeUnit

object WatchdogProbeScheduler {
    const val WORK_NAME = "solstone-watchdog-probe"
    const val ENQUEUED_AT_MS_KEY = "watchdog_probe_enqueued_at_ms"
    const val EXPEDITED_LATENCY_THRESHOLD_MS = 60_000L
    internal val OUT_OF_QUOTA_POLICY = OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST

    fun enqueue(context: Context, nowMs: Long = System.currentTimeMillis()) {
        val request = buildRequest(nowMs)
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            WORK_NAME,
            ExistingWorkPolicy.KEEP,
            request,
        )
    }

    fun enqueueNext(context: Context, nowMs: Long = System.currentTimeMillis()) {
        val request = buildRequest(nowMs)
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    private fun buildRequest(nowMs: Long): OneTimeWorkRequest =
        OneTimeWorkRequestBuilder<WatchdogProbeWorker>()
            .setExpedited(OUT_OF_QUOTA_POLICY)
            .setConstraints(probeConstraints())
            .setBackoffCriteria(BackoffPolicy.LINEAR, WorkRequest.MIN_BACKOFF_MILLIS, TimeUnit.MILLISECONDS)
            .setInputData(enqueuedAtInputData(nowMs))
            .build()

    internal fun probeConstraints(): Constraints =
        // Deliberately unconstrained so the probe measures WorkManager dispatch availability.
        Constraints.Builder().build()

    internal fun enqueuedAtInputData(nowMs: Long): Data =
        Data.Builder()
            .putLong(ENQUEUED_AT_MS_KEY, nowMs)
            .build()

    internal fun classifyDispatch(
        latencyMs: Long,
        thresholdMs: Long = EXPEDITED_LATENCY_THRESHOLD_MS,
    ): Boolean =
        // Heuristic only: WorkManager exposes no in-worker quota-downgrade signal.
        latencyMs < thresholdMs
}
