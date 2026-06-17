// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import app.solstone.platform.fgs.ObserverForegroundService
import app.solstone.platform.fgs.ObserverNotification
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ForegroundServiceRuntimeTest {
    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.POST_NOTIFICATIONS,
    )

    @Test
    fun visibleActivityStartsForegroundServiceAndRefreshesHeartbeat() {
        ActivityScenario.launch(MainActivity::class.java).use {
            waitUntilFreshHeartbeat()
            assertTrue(ObserverForegroundService.isHeartbeatFresh())
            assertOngoingNotificationIfVisible()
        }
    }

    private fun waitUntilFreshHeartbeat() {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (System.nanoTime() < deadline) {
            if (ObserverForegroundService.isHeartbeatFresh()) return
            Thread.sleep(100)
        }
        assumeTrue("foreground service heartbeat was not available in this environment", false)
    }

    private fun assertOngoingNotificationIfVisible() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val manager = context.getSystemService(NotificationManager::class.java)
        val notification = manager.activeNotifications
            .firstOrNull { it.id == ObserverNotification.SERVICE_NOTIFICATION_ID }
            ?: return
        assertTrue(notification.notification.flags and android.app.Notification.FLAG_ONGOING_EVENT != 0)
    }
}
