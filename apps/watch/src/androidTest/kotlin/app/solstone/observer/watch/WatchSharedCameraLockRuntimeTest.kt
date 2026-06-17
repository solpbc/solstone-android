// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.watch

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.solstone.platform.camera.still.StillCamera
import app.solstone.platform.camera.still.StillCaptureEngine
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WatchSharedCameraLockRuntimeTest {
    @Test
    fun scanHeldSharedLockMakesStillEngineEmitCameraBusyAndRelease() {
        ActivityScenario.launch(MainActivity::class.java).use {
            val container = waitForContainer()
            assertTrue(container.controller.beginScanSession())
            val sink = CapturingSink()
            val engine = StillCaptureEngine(
                stillCamera = object : StillCamera {
                    override fun takeStill(): ByteArray = "unused".encodeToByteArray()
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

    private fun waitForContainer(): WatchAppContainer {
        repeat(50) {
            WatchHarnessRuntime.container?.let { return it }
            Thread.sleep(100)
        }
        assumeTrue("watch harness container was not created", false)
        error("unreachable")
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
