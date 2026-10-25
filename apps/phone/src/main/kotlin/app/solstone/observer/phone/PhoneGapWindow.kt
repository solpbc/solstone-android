// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import app.solstone.observer.formfactor.phone.GapWindowScheduler

/**
 * One inexact alarm at the end of the gap window. `set` needs no exact-alarm permission.
 * A null window cancels that alarm.
 */
class PhoneGapWindowScheduler(private val context: Context) : GapWindowScheduler {
    override fun schedule(windowEndMillis: Long?) {
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pending = PendingIntent.getBroadcast(
            context,
            REQUEST,
            Intent(context, PhoneGapWindowReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        if (windowEndMillis == null) alarm.cancel(pending) else alarm.set(AlarmManager.RTC, windowEndMillis, pending)
    }

    private companion object {
        const val REQUEST = 204
    }
}

class PhoneGapWindowReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        (context.applicationContext as? PhoneApplication)?.refreshWidgetAfterGapSeen()
    }
}
