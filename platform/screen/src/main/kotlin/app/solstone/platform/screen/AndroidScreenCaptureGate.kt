// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.screen

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.display.DisplayManager
import android.view.Display
import android.os.Build
import android.os.PowerManager

/** isKeyguardLocked includes swipe locks; isDeviceLocked does not. */
class AndroidScreenCaptureGate(context: Context) : ScreenCaptureGate(
    eligible = {
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val keyguard = context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        val displays = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        val state = displays.getDisplay(Display.DEFAULT_DISPLAY)?.state ?: Display.STATE_UNKNOWN
        power.isInteractive && !keyguard.isKeyguardLocked &&
            state != Display.STATE_OFF && state != Display.STATE_UNKNOWN
    },
) {
    private val app = context.applicationContext
    private var registered = false
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // Invalidate even if a quick unlock happened before delivery.
            if (intent.action == Intent.ACTION_SCREEN_OFF) pause()
            else isAllowed()
        }
    }

    override fun start() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else app.registerReceiver(receiver, filter)
        registered = true
        isAllowed()
    }

    override fun stop() {
        if (registered) { app.unregisterReceiver(receiver); registered = false }
        super.stop()
    }
}
