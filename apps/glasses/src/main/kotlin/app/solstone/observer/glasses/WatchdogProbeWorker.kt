// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.glasses

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.solstone.core.diagnostics.DiagEvent
import app.solstone.observer.harness.SharedPreferencesDesiredObservingStore
import app.solstone.platform.fgs.ObserverForegroundService

fun interface WatchdogProbeReschedule {
    fun scheduleNext()
}

internal fun runWatchdogProbe(
    run: Long,
    observingUp: Boolean,
    expedited: Boolean,
    diag: (DiagEvent) -> Unit,
    reschedule: WatchdogProbeReschedule,
) {
    diag(DiagEvent.WatchdogProbe(run, observingUp, expedited))
    reschedule.scheduleNext()
}

private class WatchdogProbeRunCounter(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun next(): Long {
        val next = prefs.getLong(KEY_RUN_COUNT, 0L) + 1
        prefs.edit().putLong(KEY_RUN_COUNT, next).apply()
        return next
    }

    private companion object {
        const val PREFS_NAME = "solstone_watchdog_probe"
        const val KEY_RUN_COUNT = "run_count"
    }
}

class WatchdogProbeWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val run = WatchdogProbeRunCounter(applicationContext).next()
        val observingUp = SharedPreferencesDesiredObservingStore(applicationContext).isDesiredOn() &&
            ObserverForegroundService.isHeartbeatFresh()
        val enqueuedAtMs = inputData.getLong(
            WatchdogProbeScheduler.ENQUEUED_AT_MS_KEY,
            System.currentTimeMillis(),
        )
        val expedited = WatchdogProbeScheduler.classifyDispatch(System.currentTimeMillis() - enqueuedAtMs)
        runWatchdogProbe(
            run,
            observingUp,
            expedited,
            diag = { GlassesDiagLog.emit(it) },
            reschedule = { WatchdogProbeScheduler.enqueueNext(applicationContext) },
        )
        return Result.success()
    }
}
