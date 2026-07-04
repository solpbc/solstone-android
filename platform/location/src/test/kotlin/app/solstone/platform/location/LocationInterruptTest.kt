// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.location

import app.solstone.core.sources.EmissionSink
import app.solstone.core.sources.SourceEmission
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class LocationInterruptTest {
    @Test
    fun stopDuringCaptureInterruptsSleepWithoutCrashingWorker() {
        val uncaught = AtomicReference<Throwable?>()
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, throwable -> uncaught.set(throwable) }

        try {
            val sink = CapturingSink()
            val engine = LocationContinuousSourceEngine(
                source = FixedLocationSource(),
                nowProvider = { BASE_EPOCH_MS },
                sampleEveryMs = 50L,
            )

            engine.start(sink)
            val worker = waitForSleepingWorker()
            engine.stop()

            assertFalse(worker.isAlive)
            assertEquals(null, uncaught.get())
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previousHandler)
        }
    }

    private class FixedLocationSource : LocationSource {
        override fun lastFix(nowEpochMs: Long): LocationFix =
            LocationFix(
                provider = "gps",
                timestampEpochMs = nowEpochMs,
                lat = 39.7392,
                lon = -104.9903,
                accuracyMeters = 8.0,
                fixAgeMs = 0L,
            )

        override fun noFixReason(): NoFixReason = NoFixReason.NO_FIX
    }

    private class CapturingSink : EmissionSink {
        val emissions = CopyOnWriteArrayList<SourceEmission>()

        override fun emit(emission: SourceEmission) {
            emissions.add(emission)
        }
    }

    private fun waitForSleepingWorker(): Thread {
        repeat(200) {
            val worker = Thread.getAllStackTraces().keys.firstOrNull { thread ->
                thread.name == WORKER_THREAD_NAME && thread.state == Thread.State.TIMED_WAITING
            }
            if (worker != null) return worker
            Thread.sleep(5L)
        }
        throw AssertionError("worker did not enter timed waiting state")
    }

    private companion object {
        const val BASE_EPOCH_MS = 1_772_582_400_000L
        const val WORKER_THREAD_NAME = "solstone-location-source"
    }
}
