// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.solstone.observer.scaffold.ObserverAppContainer
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecoveryStillStartsCaptureTest {
    @After
    fun reset() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        resetObserverRuntime()
        context.filesDir.resolve("gap").deleteRecursively()
        context.filesDir.resolve("audio-source").deleteRecursively()
        context.filesDir.resolve(".audio-recovery-step").delete()
        resetPersistence(context)
    }

    @Test
    fun aThrowingRecoveryStepAndAFailingGapStoreStillLetCaptureStart() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val pkg = InstrumentationRegistry.getInstrumentation().targetContext.packageName
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        listOf(
            "android.permission.RECORD_AUDIO",
            "android.permission.CAMERA",
            "android.permission.ACCESS_COARSE_LOCATION",
            "android.permission.ACCESS_FINE_LOCATION",
        ).forEach { permission ->
            runCatching { automation.grantRuntimePermission(pkg, permission) }
        }
        resetObserverRuntime()
        resetPersistence(context)
        val gap = context.filesDir.resolve("gap")
        gap.deleteRecursively()
        assertTrue(gap.createNewFile())
        val draft = context.filesDir.resolve("spool/.draft/2026-10-25/main/leaf")
        assertTrue(draft.mkdirs())
        val heard = AtomicInteger()
        val container = ObserverAppContainer(
            context,
            phoneSpec,
            gapListener = { _, _, reason, _ ->
                if (reason == "missing manifest") {
                    heard.incrementAndGet()
                    throw IllegalStateException("listener")
                }
            },
        )
        try {
            assertTrue(waitForRecovery(container))
            assertFalse(container.noticesMayShow)
            assertTrue(heard.get() > 0)
            container.captureAuthority.acquire()
            assertTrue(container.controller.start())
            val deadline = System.currentTimeMillis() + 5_000L
            while (System.currentTimeMillis() < deadline && !container.controller.globalFactInputs().engineRunning) {
                Thread.sleep(50)
            }
            assertTrue(container.controller.globalFactInputs().engineRunning)
        } finally {
            container.close()
        }
    }

    @Test
    fun aThrowingAudioRecoveryStepAndAFailingGapStoreStillLetCaptureStart() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val pkg = InstrumentationRegistry.getInstrumentation().targetContext.packageName
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        listOf(
            "android.permission.RECORD_AUDIO",
            "android.permission.CAMERA",
            "android.permission.ACCESS_COARSE_LOCATION",
            "android.permission.ACCESS_FINE_LOCATION",
        ).forEach { permission ->
            runCatching { automation.grantRuntimePermission(pkg, permission) }
        }
        resetObserverRuntime()
        resetPersistence(context)
        val gap = context.filesDir.resolve("gap")
        gap.deleteRecursively()
        assertTrue(gap.createNewFile())
        val audio = context.filesDir.resolve("audio-source")
        audio.deleteRecursively()
        assertTrue(audio.createNewFile())
        val marker = context.filesDir.resolve(".audio-recovery-step")
        marker.delete()
        val container = ObserverAppContainer(
            context,
            phoneSpec,
            gapListener = { _, _, _, _ -> },
        )
        try {
            assertTrue(waitForRecovery(container))
            assertTrue(marker.isFile)
            assertFalse(container.noticesMayShow)
            container.captureAuthority.acquire()
            assertTrue(container.controller.start())
            val deadline = System.currentTimeMillis() + 5_000L
            while (System.currentTimeMillis() < deadline && !container.controller.globalFactInputs().engineRunning) {
                Thread.sleep(50)
            }
            assertTrue(container.controller.globalFactInputs().engineRunning)
        } finally {
            container.close()
            audio.delete()
            marker.delete()
        }
    }
}
