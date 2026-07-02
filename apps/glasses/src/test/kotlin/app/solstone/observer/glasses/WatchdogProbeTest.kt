// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.glasses

import androidx.work.NetworkType
import androidx.work.OutOfQuotaPolicy
import app.solstone.core.diagnostics.formatDiagEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WatchdogProbeTest {
    @Test
    fun probeRecordsFormattedDiagnosticLine() {
        val lines = mutableListOf<String>()

        runWatchdogProbe(
            run = 3,
            observingUp = true,
            expedited = false,
            diag = { lines += formatDiagEvent(it) },
            reschedule = {},
        )

        assertEquals(listOf("kind=watchdog-probe run=3 observing=up mode=deferred"), lines)

        lines.clear()
        runWatchdogProbe(
            run = 4,
            observingUp = false,
            expedited = true,
            diag = { lines += formatDiagEvent(it) },
            reschedule = {},
        )

        assertEquals(listOf("kind=watchdog-probe run=4 observing=down mode=expedited"), lines)
    }

    @Test
    fun probeSelfReschedulesExactlyOnce() {
        var count = 0
        val reschedule = WatchdogProbeReschedule { count += 1 }

        runWatchdogProbe(
            run = 1,
            observingUp = true,
            expedited = true,
            diag = {},
            reschedule = reschedule,
        )

        assertEquals(1, count)
    }

    @Test
    fun schedulerConfigUsesExpeditedFallbackAndNoConstraints() {
        val constraints = WatchdogProbeScheduler.probeConstraints()

        assertEquals(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST, WatchdogProbeScheduler.OUT_OF_QUOTA_POLICY)
        assertEquals(NetworkType.NOT_REQUIRED, constraints.requiredNetworkType)
        assertFalse(constraints.requiresCharging())
        assertFalse(constraints.requiresDeviceIdle())
        assertFalse(constraints.requiresBatteryNotLow())
        assertEquals(
            1234L,
            WatchdogProbeScheduler.enqueuedAtInputData(1234L)
                .getLong(WatchdogProbeScheduler.ENQUEUED_AT_MS_KEY, -1L),
        )
    }

    @Test
    fun classifyDispatchTreatsBelowThresholdAsExpedited() {
        val threshold = WatchdogProbeScheduler.EXPEDITED_LATENCY_THRESHOLD_MS

        assertTrue(WatchdogProbeScheduler.classifyDispatch(threshold - 1, threshold))
        assertFalse(WatchdogProbeScheduler.classifyDispatch(threshold, threshold))
        assertFalse(WatchdogProbeScheduler.classifyDispatch(threshold + 1, threshold))
    }
}
