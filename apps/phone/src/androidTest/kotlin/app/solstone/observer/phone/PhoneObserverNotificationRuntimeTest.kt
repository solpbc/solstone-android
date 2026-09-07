// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import app.solstone.observer.formfactor.phone.EXTRA_PHONE_ROUTE
import app.solstone.observer.formfactor.phone.PhoneRoute
import app.solstone.observer.formfactor.phone.decodePhoneRoute
import app.solstone.observer.formfactor.phone.PhoneStatusModel
import app.solstone.observer.formfactor.phone.statusPillText
import app.solstone.observer.harness.HarnessBacklogStatus
import app.solstone.observer.harness.HarnessPlStatus
import app.solstone.observer.harness.SourceWish
import app.solstone.platform.fgs.ObserverForegroundService
import app.solstone.platform.fgs.ObserverNotification
import app.solstone.testing.validDirectPairLink
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhoneObserverNotificationRuntimeTest {
    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.CAMERA,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.POST_NOTIFICATIONS,
    )

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val application: PhoneApplication
        get() = context.applicationContext as PhoneApplication

    @Before
    fun setUp() {
        resetObserverRuntime()
        resetPersistence(context)
    }

    @After
    fun tearDown() {
        resetObserverRuntime()
    }

    @Test
    fun stopActionOfferedOnLiveForegroundServiceAndExcludedFromStopped() {
        val liveNotification = ObserverNotification.ongoing(context, decorate = true)
        val liveActions = liveNotification.actions.orEmpty()
        assertTrue(liveActions.any(::isStopAction))

        val stoppedNotification = ObserverNotification.ongoing(context, stopped = true)
        val stoppedActions = stoppedNotification.actions.orEmpty()
        assertFalse(stoppedActions.any(::isStopAction))

        val undecoratedNotification = ObserverNotification.ongoing(context, decorate = false)
        val undecoratedActions = undecoratedNotification.actions.orEmpty()
        assertFalse(undecoratedActions.any(::isStopAction))

        // Start action is offered when not running and targets the foreground service
        assertTrue(liveActions.any(::isStartAction) || stoppedActions.any(::isStartAction))
    }

    @Test
    fun stopActionBroadcastStopsObserverAndRemovesForegroundServiceNotification() {
        // A visible start requires a visible capture owner. Without an Activity in the
        // foreground startReadiness blocks and the service never enters the foreground, so the
        // wait below times out before this test reaches the behaviour it is about.
        ActivityScenario.launch(app.solstone.observer.scaffold.ObserverActivity::class.java).use {
        val container = obtainObserverContainer()
        assertTrue(waitForRecovery(container))
        assertTrue(container.controller.onScannedPairLink(validDirectPairLink()) != null)

        container.controller.start()
        waitUntil("service notification visible") {
            serviceNotification() != null
        }

        // Seed a non-audio source's wish on, so an implementation that only turns audio off
        // leaves it on and fails here. Uses location rather than camera: the mock flavor this
        // gate runs registers audio and location only, and has no camera source at all.
        container.sources.setWish("location", SourceWish.On)

        val stopIntent = Intent(context, PhoneObserverStopReceiver::class.java)
        context.sendBroadcast(stopIntent)

        waitUntil("controller desiredOn false") { !container.controller.desiredOn }
        waitUntil("service notification removed") { serviceNotification() == null }

        assertFalse(container.controller.desiredOn)
        val locationStatus = container.sources.snapshot().sources.singleOrNull { it.sourceId == "location" }
        assertEquals(SourceWish.On, locationStatus?.wish)
        }
    }

    @Test
    fun decoratedNotificationExtrasDeriveFromCaptureAndExcludeSyncText() {
        val container = obtainObserverContainer()
        assertTrue(waitForRecovery(container))
        assertTrue(container.controller.onScannedPairLink(validDirectPairLink()) != null)

        val distinctiveStatusModel = PhoneStatusModel(
            paired = true,
            online = true,
            pendingCount = 42,
            hasContentPending = true,
        )
        val syncText = statusPillText(distinctiveStatusModel)
        PhoneStatusSupplier.override = {
            HarnessBacklogStatus(
                plStatus = HarnessPlStatus.Reachable(200),
                pendingCount = 42,
                pendingSourceIds = listOf("audio"),
            )
        }

        try {
            val model = application.widgetModel()
            val notification = ObserverNotification.ongoing(context, decorate = true)

            val title = notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
            val text = notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()

            assertEquals(model.stateWord, text)
            assertFalse(text.contains(syncText))
            assertFalse(title.contains(syncText))
        } finally {
            PhoneStatusSupplier.override = null
        }
    }

    @Test
    fun startActionTargetsForegroundServiceWithIntakeStartAndNoSourceId() {
        val container = obtainObserverContainer()
        assertTrue(waitForRecovery(container))
        container.sources.setWish("audio", SourceWish.On)

        val notification = ObserverNotification.ongoing(context, decorate = true)
        val startAction = notification.actions.orEmpty().firstOrNull(::isStartAction)
        assertNotNull(startAction)
        val intent = actionIntent(startAction!!)
        if (intent != null) {
            assertTrue(intent.getBooleanExtra(ObserverForegroundService.EXTRA_INTAKE_START, false))
            assertFalse(intent.hasExtra(ObserverForegroundService.EXTRA_WIDGET_SOURCE_ID))
        }
    }

    @Test
    fun startActionOmittedWhenNoSourcesWishOn() {
        val container = obtainObserverContainer()
        assertTrue(waitForRecovery(container))
        container.sources.snapshot().sources.forEach {
            container.sources.setWish(it.sourceId, SourceWish.Off)
        }
        val notification = ObserverNotification.ongoing(context, decorate = true)
        val actions = notification.actions.orEmpty()
        assertFalse(actions.any(::isStartAction))
    }

    @Test
    fun notification101NotPostedWhenFgsDownEvenWithDesiredOn() {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.cancel(ObserverNotification.SERVICE_NOTIFICATION_ID)
        ObserverForegroundService.heldCaptureForegroundTypes = null

        ObserverForegroundService.refreshOngoingNotification(context, needsAttention = false)
        val serviceNotif = manager.activeNotifications.firstOrNull { it.id == ObserverNotification.SERVICE_NOTIFICATION_ID }
        assertNull(serviceNotif)
    }

    @Test
    fun stoppedNotification102NotPostedWhenAudioStopsWhileAnotherSourceIsOn() {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.cancel(ObserverNotification.BOOT_NOTIFICATION_ID)

        val container = obtainObserverContainer()
        assertTrue(waitForRecovery(container))
        container.sources.setWish("audio", SourceWish.On)
        container.sources.setWish("location", SourceWish.On)

        // Turn only audio off
        application.turnAudioOffFromWidget()

        val attentionNotif = manager.activeNotifications.firstOrNull { it.id == ObserverNotification.BOOT_NOTIFICATION_ID }
        assertNull(attentionNotif)
    }

    @Test
    fun contentIntentCarriesExpectedRouteExtraForAttentionSource() {
        val container = obtainObserverContainer()
        assertTrue(waitForRecovery(container))
        container.sources.setWish("location", SourceWish.On)
        container.controller.recordStartRefusal()

        val notification = ObserverNotification.ongoing(context, decorate = true)
        val contentIntent = notification.contentIntent
        assertNotNull(contentIntent)
        val intent = runCatching {
            val method = contentIntent.javaClass.getDeclaredMethod("getIntent")
            method.isAccessible = true
            method.invoke(contentIntent) as? Intent
        }.getOrNull()
        if (intent != null) {
            val routeExtra = intent.getStringExtra(EXTRA_PHONE_ROUTE)
            assertNotNull(routeExtra)
            assertTrue(routeExtra!!.startsWith("sd/"))
            assertEquals(PhoneRoute.SourceDetail("location"), decodePhoneRoute(routeExtra))
        } else {
            assertEquals(PhoneRoute.SourceDetail("location"), application.intakeModel().route)
        }
    }

    private fun isStopAction(action: Notification.Action): Boolean {
        val intent = actionIntent(action)
        if (intent != null) {
            return intent.component?.className == PhoneObserverStopReceiver::class.java.name
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return action.actionIntent?.isBroadcast == true
        }
        return false
    }

    private fun isStartAction(action: Notification.Action): Boolean {
        val intent = actionIntent(action)
        if (intent != null) {
            return intent.component?.className == ObserverForegroundService::class.java.name
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return action.actionIntent?.isForegroundService == true
        }
        return false
    }

    private fun actionIntent(action: Notification.Action): Intent? {
        val pendingIntent = action.actionIntent ?: return null
        return runCatching {
            val method = pendingIntent.javaClass.getDeclaredMethod("getIntent")
            method.isAccessible = true
            method.invoke(pendingIntent) as? Intent
        }.getOrNull()
    }

    private fun serviceNotification(): android.service.notification.StatusBarNotification? {
        val manager = context.getSystemService(NotificationManager::class.java)
        return manager.activeNotifications.firstOrNull { it.id == ObserverNotification.SERVICE_NOTIFICATION_ID }
    }
}
