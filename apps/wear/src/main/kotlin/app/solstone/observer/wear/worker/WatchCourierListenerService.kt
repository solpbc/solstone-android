// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc
package app.solstone.observer.wear.worker

import app.solstone.core.watch.courier.WatchCourierHello
import app.solstone.core.watch.courier.WatchCourierProtocol
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.CapabilityInfo
import com.google.android.gms.wearable.WearableListenerService

/** Reconnects schedule delivery; incoming messages cannot start capture. */
class WatchCourierListenerService : WearableListenerService() {
    override fun onCapabilityChanged(capability: CapabilityInfo) {
        if (capability.name == WatchCourierProtocol.CAPABILITY_COURIER && capability.nodes.isNotEmpty()) {
            WatchTransferWorker.enqueue(applicationContext, urgent = true)
        }
    }
    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != WatchCourierProtocol.MESSAGE_PATH_STATUS) return
        val reply = WatchCourierHello.decode(event.data, WatchCourierProtocol.KIND_STATUS) ?: return
        WatchPeerStatusStore(this).heard(reply.version)
    }
}
