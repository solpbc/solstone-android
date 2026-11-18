// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The "from your journal" channel belongs to push. App start, a configuration change and a locale
 * change only rename it once a push has created it; they never create it, so an owner who has not
 * turned push on sees no push category in the system's notification settings.
 */
@RunWith(AndroidJUnit4::class)
class JournalPushChannelRuntimeTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager = context.getSystemService(NotificationManager::class.java)

    @After
    fun removeChannel() {
        manager.deleteNotificationChannel(JournalPushPoster.CHANNEL_ID)
    }

    @Test
    fun refreshDoesNotCreateTheChannel() {
        manager.deleteNotificationChannel(JournalPushPoster.CHANNEL_ID)
        JournalPushPoster(context).renameChannelIfPresent()
        PhoneLocaleReceiver().onReceive(context, android.content.Intent(android.content.Intent.ACTION_LOCALE_CHANGED))
        assertNull(manager.getNotificationChannel(JournalPushPoster.CHANNEL_ID))
    }

    @Test
    fun refreshRenamesAnExistingChannel() {
        manager.createNotificationChannel(
            NotificationChannel(JournalPushPoster.CHANNEL_ID, "stale", NotificationManager.IMPORTANCE_DEFAULT),
        )
        JournalPushPoster(context).renameChannelIfPresent()
        assertEquals(JournalPushPoster.CHANNEL_NAME, manager.getNotificationChannel(JournalPushPoster.CHANNEL_ID)?.name)
    }
}
