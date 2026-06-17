// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.camera.still

import app.solstone.core.sources.EmissionSink
import app.solstone.core.sources.SourceEmission
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StillCaptureInterruptTest {
    @Test
    fun stopDuringCadenceSleepInterruptsWorkerAndReleasesLock() {
        val uncaught = AtomicReference<Throwable?>()
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, throwable -> uncaught.set(throwable) }

        try {
            val sink = CapturingSink()
            val lock = RecordingCameraLock()
            val engine = StillCaptureEngine(
                stillCamera = FixedStillCamera(),
                cameraLock = lock,
                nowProvider = { BASE_EPOCH_MS },
            )

            engine.start(sink)
            val worker = waitForSleepingWorker()
            engine.stop()

            assertFalse(worker.isAlive)
            assertEquals(null, uncaught.get())
            assertTrue(sink.emissions.isNotEmpty())
            assertFalse(lock.held.get())
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previousHandler)
        }
    }

    private class FixedStillCamera : StillCamera {
        override fun takeStill(): ByteArray = "jpeg".encodeToByteArray()
    }

    private class RecordingCameraLock : CameraLock {
        val held = AtomicBoolean(false)

        override fun tryAcquire(): Boolean = held.compareAndSet(false, true)

        override fun release() {
            held.set(false)
        }
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
                thread.name == StillCaptureEngine.WORKER_THREAD_NAME && thread.state == Thread.State.TIMED_WAITING
            }
            if (worker != null) return worker
            Thread.sleep(5L)
        }
        throw AssertionError("worker did not enter timed waiting state")
    }

    private companion object {
        const val BASE_EPOCH_MS = 1_772_582_400_000L
    }
}
