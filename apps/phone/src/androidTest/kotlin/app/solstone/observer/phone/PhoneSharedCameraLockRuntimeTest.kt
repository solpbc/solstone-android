// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.solstone.observer.scaffold.ObserverActivity
import app.solstone.platform.camera.still.StillCamera
import app.solstone.platform.camera.still.StillCaptureEngine
import app.solstone.platform.camera.still.StillCaptureResult
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhoneSharedCameraLockRuntimeTest {
    @After
    fun tearDown() {
        resetObserverRuntime()
    }

    @Test
    fun scanHeldSharedLockMakesStillEngineEmitCameraBusyAndRelease() {
        ActivityScenario.launch(ObserverActivity::class.java).use {
            val container = waitForObserverContainer()
            assertTrue(container.controller.beginScanSession())
            val sink = CapturingSink()
            val engine = StillCaptureEngine(
                stillCamera = object : StillCamera {
                    override fun takeStill(): StillCaptureResult = StillCaptureResult.Image("unused".encodeToByteArray())
                },
                cameraLock = container.cameraLock,
                nowProvider = { 1_000L },
                sleeper = { throw InterruptedException() },
            )
            engine.start(sink)
            waitForEmission(sink)
            engine.stop()
            assertEquals("camera_busy", sink.emissions.single().gaps.single().detail)
            container.controller.endScanSession()
            assertTrue(container.cameraLock.tryAcquire())
            container.cameraLock.release()
        }
    }

    private fun waitForEmission(sink: CapturingSink) {
        repeat(50) {
            if (sink.emissions.isNotEmpty()) return
            Thread.sleep(20)
        }
    }

    private class CapturingSink : app.solstone.core.sources.EmissionSink {
        val emissions = CopyOnWriteArrayList<app.solstone.core.sources.SourceEmission>()
        override fun emit(emission: app.solstone.core.sources.SourceEmission) {
            emissions += emission
        }
    }
}
