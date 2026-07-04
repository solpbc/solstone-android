// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.harness

import app.solstone.platform.camera.still.SingleHolderCameraLock
import app.solstone.platform.camera.still.StillCamera
import app.solstone.platform.camera.still.StillCaptureEngine
import app.solstone.platform.camera.still.StillCaptureResult
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HarnessCameraLockIdentityTest {
    @Test
    fun scannerHeldSharedLockMakesStillEngineEmitCameraBusyGapAndThenRelease() {
        val lock = SingleHolderCameraLock()
        val sink = CapturingSink()
        assertTrue(lock.tryAcquire())

        val engine = StillCaptureEngine(
            stillCamera = object : StillCamera {
                override fun takeStill(): StillCaptureResult = StillCaptureResult.Image("unused".encodeToByteArray())
            },
            cameraLock = lock,
            nowProvider = { 1_000L },
            sleeper = { throw InterruptedException() },
        )
        engine.start(sink)
        waitForEmission(sink)
        engine.stop()

        assertEquals("camera_busy", sink.emissions.single().gaps.single().detail)
        lock.release()
        assertTrue(lock.tryAcquire())
        lock.release()
    }

    @Test
    fun controllerScanSessionReleasesRealLock() {
        val lock = SingleHolderCameraLock()
        val f = fixture(cameraLock = object : RecordingCameraLock() {
            override fun tryAcquire(): Boolean = lock.tryAcquire()
            override fun release() = lock.release()
        })
        assertTrue(f.controller.withScanSession {})
        assertTrue(lock.tryAcquire())
        lock.release()
    }

    private fun waitForEmission(sink: CapturingSink) {
        repeat(50) {
            if (sink.emissions.isNotEmpty()) return
            Thread.sleep(20)
        }
        assertFalse(sink.emissions.isEmpty(), "expected an emission")
    }

    private class CapturingSink : app.solstone.core.sources.EmissionSink {
        val emissions = CopyOnWriteArrayList<app.solstone.core.sources.SourceEmission>()
        override fun emit(emission: app.solstone.core.sources.SourceEmission) {
            emissions += emission
        }
    }
}
