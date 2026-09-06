// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class PhoneObserverStopReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        (context.applicationContext as? PhoneApplication)?.stopObserverFromNotification()
    }
}
