// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc
package app.solstone.observer.wear

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

/** An immutable, explicit face opener for an owner tap on a system surface. */
internal fun wearOpenFaceIntent(context: Context): PendingIntent {
    val options = if (Build.VERSION.SDK_INT >= 34) {
        ActivityOptions.makeBasic().apply {
            // The watch face sends this while our activity is behind it. Restricting
            // the creator to visible would defeat that return-to-session gesture.
            @Suppress("DEPRECATION")
            val mode = if (Build.VERSION.SDK_INT >= 36) {
                ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS
            } else ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
            setPendingIntentCreatorBackgroundActivityStartMode(mode)
        }.toBundle()
    } else null
    return PendingIntent.getActivity(
        context,
        // A distinct key replaces the earlier opener whose cached PendingIntent
        // has no creator opt-in; UPDATE_CURRENT does not replace its options.
        1,
        Intent(context, WearMainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        options,
    )
}
