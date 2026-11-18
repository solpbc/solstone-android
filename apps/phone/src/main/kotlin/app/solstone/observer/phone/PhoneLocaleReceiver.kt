// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc
package app.solstone.observer.phone

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.solstone.platform.fgs.ObserverNotification

/** Rename existing channels without starting an intake service. */
class PhoneLocaleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_LOCALE_CHANGED) return
        ObserverNotification.ensureChannel(context.applicationContext)
        JournalPushPoster(context.applicationContext).renameChannelIfPresent()
    }
}
