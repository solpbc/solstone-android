// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.glasses

import android.app.PendingIntent

const val CONTENT_INTENT_REQUEST_CODE = 2000
const val EXTRA_COMMAND_ACTION = "action"

enum class GlassesNotificationCommand(val requestCode: Int, val actionToken: String, val label: String) {
    Stop(2001, "observe_stop", "Stop"),
    Sync(2002, "sync_now", "Sync"),
    Status(2003, "status", "Status"),
}

@Suppress("UNUSED_PARAMETER")
fun glassesPendingIntentFlags(sdkInt: Int): Int {
    // Immutable PendingIntents are required on all supported glasses levels:
    // minSdk is 26 and FLAG_IMMUTABLE exists from API 23.
    return PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
}

enum class NotificationSpeakDecision { DispatchStatus, NoOp }

// DispatchStatus routes through routeDebugRuntimeCommand(..., "status"), which returns a verdict
// and lets the existing audio layer no-op/degrade when TTS is unavailable.
fun decideNotificationSpeak(runtimeAvailable: Boolean): NotificationSpeakDecision =
    if (runtimeAvailable) NotificationSpeakDecision.DispatchStatus else NotificationSpeakDecision.NoOp
