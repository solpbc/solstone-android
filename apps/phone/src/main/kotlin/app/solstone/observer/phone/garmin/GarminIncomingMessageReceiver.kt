// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone.garmin

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import app.solstone.core.watch.courier.GarminSlv3Protocol
import app.solstone.observer.phone.AgeSignalGate
import com.garmin.android.connectiq.ConnectIQ
import com.garmin.android.connectiq.IQApp
import com.garmin.android.connectiq.IQDevice
import com.garmin.android.connectiq.IQMessageHelper
import com.garmin.monkeybrains.serialization.Serializer

/**
 * Static manifest receiver for explicit `com.garmin.android.connectiq.INCOMING_MESSAGE` broadcasts
 * from Garmin Connect Mobile.
 *
 * Allows background wake delivery when the phone app process has been killed after initial
 * activation without requiring a permanent foreground service. When `GarminCourierRuntime` is already
 * live and registered with the SDK, this receiver yields immediately so every message is processed
 * and acknowledged strictly once.
 */
class GarminIncomingMessageReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ConnectIQ.INCOMING_MESSAGE) return
        if (AgeSignalGate.closed()) return

        // On API 34+, verify the broadcast originated from Garmin Connect Mobile when available.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val sender = sentFromPackage
            if (sender != null && sender != GarminEnvironmentProbe.GCM_PACKAGE_NAME) return
        }

        // If the live SDK dynamic listener is already active in this process, yield so we never
        // double-process or emit duplicate half-duplex ACKs.
        if (GarminCourierRuntime.isSdkRegistered()) return

        val device = extractParcelable<IQDevice>(intent, ConnectIQ.EXTRA_REMOTE_DEVICE) ?: return
        val app = extractParcelable<IQApp>(intent, ConnectIQ.EXTRA_REMOTE_APPLICATION) ?: return
        if (!GarminSlv3Protocol.matchesSolstoneAppId(app.applicationId)) return

        val payloadBytes = intent.getByteArrayExtra(ConnectIQ.EXTRA_PAYLOAD) ?: return
        val decodedObjects = runCatching {
            Serializer.deserialize(payloadBytes).map { IQMessageHelper.INSTANCE.convertToJava(it) }
        }.getOrNull() ?: return

        val pending = goAsync()
        GarminCourierRuntime.handleIncomingMessage(context, device, app, decodedObjects) {
            pending.finish()
        }
    }

    @Suppress("DEPRECATION")
    private inline fun <reified T> extractParcelable(intent: Intent, key: String): T? =
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(key, T::class.java)
            } else {
                intent.getParcelableExtra(key) as? T
            }
        }.getOrNull()
}
