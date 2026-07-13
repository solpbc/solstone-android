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
import app.solstone.core.diagnostics.PairingFact
import app.solstone.core.model.SourceState
import app.solstone.observer.harness.SyncNowResult
import app.solstone.observer.scaffold.ObserverActivity
import app.solstone.platform.fgs.ObserverNotification
import app.solstone.testing.validDirectPairLink
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhoneForegroundServiceRuntimeTest {
    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.CAMERA,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.POST_NOTIFICATIONS,
    )

    @After
    fun tearDown() {
        resetObserverRuntime()
    }

    @Test
    fun startStopStaleHeartbeatAndSyncNowAreHarnessBound() {
        ActivityScenario.launch(ObserverActivity::class.java).use {
            val container = waitForObserverContainer()
            assertTrue(container.controller.refreshPermissions().allRequiredGranted)
            val sync = requireNotNull(container.flavor.syncControl)

            val unpairedBaseline = sync.enqueueNowCalls
            assertEquals(SyncNowResult.NotPaired(PairingFact.UNPAIRED), container.controller.syncNow())
            assertEquals(unpairedBaseline, sync.enqueueNowCalls)

            assertTrue(container.controller.onScannedPairLink(validDirectPairLink()) != null)
            val pairedBaseline = sync.enqueueNowCalls

            container.controller.start()
            waitUntilNotificationVisible()
            assertOngoingNotificationIfVisible()

            container.flavor.heartbeatControl?.setFresh(false)
            assertEquals(SourceState.NEEDS_ATTENTION, container.controller.diagnostics().state)

            assertEquals(SyncNowResult.Enqueued, container.controller.syncNow())
            assertEquals(pairedBaseline + 1, sync.enqueueNowCalls)

            container.controller.stop()
        }
    }

    private fun waitUntilNotificationVisible() {
        repeat(50) {
            if (serviceNotification() != null) return
            Thread.sleep(100)
        }
    }

    private fun assertOngoingNotificationIfVisible() {
        val notification = serviceNotification() ?: return
        assertTrue(notification.notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
    }

    private fun serviceNotification(): android.service.notification.StatusBarNotification? {
        val context: Context = ApplicationProvider.getApplicationContext()
        val manager = context.getSystemService(NotificationManager::class.java)
        return manager.activeNotifications
            .firstOrNull { it.id == ObserverNotification.SERVICE_NOTIFICATION_ID }
    }
}
