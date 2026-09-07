// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import app.solstone.core.model.ReasonCode
import app.solstone.core.model.SourceState
import app.solstone.observer.formfactor.phone.STATUS_UNAVAILABLE
import app.solstone.observer.formfactor.phone.resolveSourceDetailReason
import app.solstone.observer.formfactor.phone.sourceDetailRule
import app.solstone.observer.harness.SourceWish
import app.solstone.platform.fgs.CaptureForegroundType
import app.solstone.platform.fgs.ObserverForegroundService
import app.solstone.platform.fgs.ObserverNotification
import org.junit.After
import org.junit.Assert.assertEquals
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

    private var declaredTypes: Set<CaptureForegroundType>? = null

    @Before
    fun setUp() {
        resetObserverRuntime()
        waitUntil("prior service destruction") { ObserverForegroundService.heldCaptureForegroundTypes == null }
        resetPersistence(context)
        context.getSystemService(NotificationManager::class.java).cancelAll()
        declaredTypes = ObserverForegroundService.declaredCaptureForegroundTypes
    }

    @After
    fun tearDown() {
        ObserverForegroundService.declaredCaptureForegroundTypes = declaredTypes
        resetObserverRuntime()
        waitUntil("service destruction") { ObserverForegroundService.heldCaptureForegroundTypes == null }
    }

    @Test
    fun visibleTypeMissingRecoveryExercisesRestartAndPreservesWishes() {
        // Start a real microphone-only foreground entry, then make location eligible.
        ObserverForegroundService.declaredCaptureForegroundTypes = setOf(CaptureForegroundType.MICROPHONE)
        ActivityScenario.launch(PhoneShellActivity::class.java).use {
            val container = obtainObserverContainer()
            assertTrue(waitForRecovery(container))
            container.sources.setWish("audio", SourceWish.On)
            container.sources.setWish("location", SourceWish.Off)
            container.controller.start()
            waitUntil("microphone foreground entry") {
                ObserverForegroundService.heldCaptureForegroundTypes == setOf(CaptureForegroundType.MICROPHONE)
            }
            ObserverForegroundService.declaredCaptureForegroundTypes = declaredTypes
            container.sources.setWish("location", SourceWish.On)
            assertEquals(ReasonCode.FOREGROUND_TYPE_NOT_HELD,
                container.sources.snapshot().sources.single { it.sourceId == "location" }.reason)
            container.controller.ensureObserving()
            waitUntil("fresh foreground entry with location") {
                ObserverForegroundService.heldCaptureForegroundTypes?.contains(CaptureForegroundType.LOCATION) == true &&
                    container.sources.snapshot().sources.single { it.sourceId == "location" }.state == SourceState.ON
            }
            val rows = container.sources.snapshot().sources
            assertEquals(SourceWish.On, rows.single { it.sourceId == "location" }.wish)
            assertEquals(SourceWish.On, rows.single { it.sourceId == "audio" }.wish)
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
        ObserverForegroundService.declaredCaptureForegroundTypes = setOf(CaptureForegroundType.MICROPHONE)
        ActivityScenario.launch(PhoneShellActivity::class.java).use { scenario ->
            val container = obtainObserverContainer()
            assertTrue(waitForRecovery(container))
            container.sources.setWish("audio", SourceWish.On)
            container.sources.setWish("location", SourceWish.Off)
            container.controller.start()
            waitUntil("posted on notification") { postedText() == ObserverNotification.TEXT_ON }
            scenario.moveToState(Lifecycle.State.CREATED)
            container.sources.setWish("location", SourceWish.On)
            waitUntil("automatically posted non-audio attention") {
                postedText() == ObserverNotification.TEXT_NEEDS_ATTENTION
            }
            assertEquals(SourceState.ON, container.sources.snapshot().sources.single { it.sourceId == "audio" }.state)
            assertTrue(container.controller.desiredOn)
        }
    }

    @Test
    fun failedSourceRefreshReplacesPreviouslyPostedOn() {
        ActivityScenario.launch(PhoneShellActivity::class.java).use {
            val container = obtainObserverContainer()
            assertTrue(waitForRecovery(container))
            container.sources.setWish("audio", SourceWish.On)
            container.sources.setWish("location", SourceWish.Off)
            container.controller.start()
            waitUntil("posted on notification") { postedText() == ObserverNotification.TEXT_ON }
            val application = context as PhoneApplication
            // Hold the underlying read in failure across every background refresh.
            // A one-shot failure can legitimately recover before the queued notify executes.
            application.sourceReadOverride = { error("source read failed") }
            try {
                application.refreshWidgetModel(container)
                waitUntil("published unavailable state") { postedText() == STATUS_UNAVAILABLE }
            } finally {
                application.sourceReadOverride = null
            }
            application.refreshWidgetModel(container)
            waitUntil("published recovered state") { postedText() == ObserverNotification.TEXT_ON }
        }
    }

    private fun postedText(): String? = context.getSystemService(NotificationManager::class.java)
        .activeNotifications.singleOrNull { it.id == ObserverNotification.SERVICE_NOTIFICATION_ID }
        ?.notification?.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()
}
