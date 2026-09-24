// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone

import app.solstone.core.push.PushEventSerial
import app.solstone.core.push.PushMessageHandler
import app.solstone.platform.work.syncStores
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.INSTANCE_DEFAULT
import org.unifiedpush.android.connector.PushService
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage

class PhonePushService : PushService() {

    private val serial: PushEventSerial? by lazy {
        val stores = syncStores(applicationContext)
        val coordinator = stores.pushRegistration ?: return@lazy null
        val handler = PushMessageHandler(
            pushKeys = stores.pushKeys,
            pairingNow = { stores.identityMutator.currentPairingGeneration() },
            notifier = JournalPushPoster(applicationContext),
            log = { PhoneDiagLog.appendRaw(it) },
        )
        PushEventSerial(coordinator, handler)
    }

    override fun onNewEndpoint(endpoint: PushEndpoint, instance: String) {
        if (instance != INSTANCE_DEFAULT) return
        val coordinator = syncStores(applicationContext).pushRegistration ?: return
        if (!coordinator.enabled) return
        serial?.onNewEndpoint(
            url = endpoint.url,
            p256dh = endpoint.pubKeySet?.pubKey ?: "",
            auth = endpoint.pubKeySet?.auth ?: "",
        )
    }

    override fun onUnregistered(instance: String) {
        if (instance != INSTANCE_DEFAULT) return
        val coordinator = syncStores(applicationContext).pushRegistration ?: return
        if (!coordinator.enabled) return
        serial?.onUnregistered()
    }

    override fun onRegistrationFailed(reason: FailedReason, instance: String) {
        if (instance != INSTANCE_DEFAULT) return
        val coordinator = syncStores(applicationContext).pushRegistration ?: return
        if (!coordinator.enabled) return
        serial?.onRegistrationFailed(reason.name)
    }

    override fun onMessage(message: PushMessage, instance: String) {
        if (instance != INSTANCE_DEFAULT) return
        val coordinator = syncStores(applicationContext).pushRegistration ?: return
        if (!coordinator.enabled) return
        serial?.onMessage(message.content, message.decrypted)
    }
}
