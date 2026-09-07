// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import app.solstone.core.model.ReasonCode
import app.solstone.core.model.SourceState
import app.solstone.observer.formfactor.phone.resolveSourceDetailReason
import app.solstone.observer.formfactor.phone.sourceDetailRule
import app.solstone.observer.harness.SourceWish
import app.solstone.platform.fgs.CaptureForegroundType
import app.solstone.platform.fgs.ObserverForegroundService
import app.solstone.platform.fgs.ObserverNotification
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhoneRuntimeRecoveryTest {
    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.CAMERA,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.POST_NOTIFICATIONS,
    )

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        resetObserverRuntime()
        resetPersistence(context)
    }

    @After
    fun tearDown() {
        ObserverForegroundService.heldCaptureForegroundTypes = null
        resetObserverRuntime()
    }

    @Test
    fun visibleTypeMissingRecoveryExercisesRestartAndPreservesWishes() {
        ObserverForegroundService.heldCaptureForegroundTypes = setOf(CaptureForegroundType.MICROPHONE)
        val container = obtainObserverContainer()
        assertTrue(waitForRecovery(container))

        container.sources.setWish("audio", SourceWish.On)
        container.sources.setWish("location", SourceWish.On)

        val beforeSnapshot = container.sources.snapshot()
        val locBefore = beforeSnapshot.sources.single { it.sourceId == "location" }
        assertEquals(SourceState.NEEDS_ATTENTION, locBefore.state)
        assertEquals(ReasonCode.FOREGROUND_TYPE_NOT_HELD, locBefore.reason)

        ActivityScenario.launch(PhoneShellActivity::class.java).use {
            container.controller.ensureObserving()

            val afterSnapshot = container.sources.snapshot()
            val locAfter = afterSnapshot.sources.single { it.sourceId == "location" }
            val audioAfter = afterSnapshot.sources.single { it.sourceId == "audio" }

            assertEquals(SourceWish.On, locAfter.wish)
            assertEquals(SourceWish.On, audioAfter.wish)
        }
    }

    @Test
    fun blockedStartOnDetailAfterRealRefusal() {
        val container = obtainObserverContainer()
        assertTrue(waitForRecovery(container))

        container.sources.setWish("audio", SourceWish.On)

        // Without a visible owner, ensureObserving cannot start and records refusal
        container.controller.ensureObserving()

        assertTrue(container.controller.lastStartRefused)

        val snapshot = container.sources.snapshot()
        val audioRow = snapshot.sources.single { it.sourceId == "audio" }
        assertEquals(SourceState.NEEDS_ATTENTION, audioRow.state)
        assertEquals(ReasonCode.FOREGROUND_START_NOT_ALLOWED, audioRow.reason)

        val resolvedReason = resolveSourceDetailReason(audioRow, snapshot.observer)
        assertEquals(ReasonCode.FOREGROUND_START_NOT_ALLOWED, resolvedReason)
        val rule = sourceDetailRule(resolvedReason)
        assertTrue(rule.retryHonest)
    }

    @Test
    fun nonAudioAttentionUpdatesPosted101ContentWhileAudioOn() {
        ObserverForegroundService.heldCaptureForegroundTypes = setOf(CaptureForegroundType.MICROPHONE)
        ActivityScenario.launch(PhoneShellActivity::class.java).use {
            val container = obtainObserverContainer()
            assertTrue(waitForRecovery(container))

            container.controller.start()
            assertTrue(container.controller.desiredOn)

            container.sources.setWish("audio", SourceWish.On)
            container.sources.setWish("location", SourceWish.On)

            val snapshot = container.sources.snapshot()
            val locRow = snapshot.sources.single { it.sourceId == "location" }
            assertEquals(SourceState.NEEDS_ATTENTION, locRow.state)

            container.refreshServiceNotification()

            val manager = context.getSystemService(NotificationManager::class.java)
            val notification101 = manager.activeNotifications.singleOrNull { it.id == ObserverNotification.SERVICE_NOTIFICATION_ID }
            assertNotNull(notification101)

            val text = notification101!!.notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            assertEquals(ObserverNotification.TEXT_NEEDS_ATTENTION, text)
        }
    }
}
