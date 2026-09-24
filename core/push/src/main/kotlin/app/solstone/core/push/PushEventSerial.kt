// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.push

import java.io.Closeable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class PushEventSerial(
    private val coordinator: PushRegistrationCoordinator,
    private val handler: PushMessageHandler,
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "phone-push-event").apply { isDaemon = true }
    },
) : Closeable {

    fun onNewEndpoint(url: String, p256dh: String, auth: String) {
        executor.execute {
            coordinator.onNewEndpoint(url, p256dh, auth)
        }
    }

    fun onUnregistered() {
        executor.execute {
            coordinator.onUnregistered()
        }
    }

    fun onRegistrationFailed(reason: String) {
        executor.execute {
            coordinator.onRegistrationFailed(reason)
        }
    }

    fun onMessage(content: ByteArray, decrypted: Boolean) {
        executor.execute {
            handler.onMessage(content, decrypted)
        }
    }

    override fun close() {
        executor.shutdown()
    }
}
