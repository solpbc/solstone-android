// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone.wear

import app.solstone.core.sources.MAIN_STREAM
import app.solstone.core.watch.courier.CodecResult
import app.solstone.core.watch.courier.WatchCourierHello
import app.solstone.core.watch.courier.WatchCourierCodec
import app.solstone.core.watch.courier.WatchCourierProtocol
import app.solstone.platform.persistence.room.openSolstonePersistenceDatabase
import app.solstone.platform.work.PublishBundleResult
import app.solstone.platform.work.SyncScheduler
import app.solstone.platform.work.WatchCustodyPublisher
import app.solstone.platform.wearable.WearTaskPolicy
import app.solstone.platform.wearable.WearChannelDeadline
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.wearable.WearableStatusCodes
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException

class PhoneWatchCourierService : WearableListenerService() {
    private val activeChannels = ConcurrentHashMap.newKeySet<WearChannelDeadline>()

    override fun onChannelOpened(channel: ChannelClient.Channel) {
        if (channel.path != WatchCourierProtocol.CHANNEL_PATH_BUNDLE) {
            super.onChannelOpened(channel)
            return
        }

        // The SDK invokes this on its background callback looper and retains the
        // service binding until it returns. Keep custody work inside that lifetime.
        val deadline = WearChannelDeadline {
            WearTaskPolicy.createTask { Wearable.getChannelClient(this).close(channel) }
        }
        activeChannels.add(deadline)
        try {
            val inputStream: InputStream = deadline.own(WearTaskPolicy.await { Wearable.getChannelClient(this).getInputStream(channel) })
            val app = applicationContext
            val spoolDir = app.filesDir.toPath().resolve("spool")
            val stagingDir = app.filesDir.toPath().resolve("staging")
            val database = openSolstonePersistenceDatabase(app)
            val result = try {
                val publisher = WatchCustodyPublisher(spoolDir, stagingDir, database.segmentDao())
                inputStream.use { stream ->
                    publisher.publish(stream) {
                        SyncScheduler.enqueueFollowUp(app, MAIN_STREAM)
                    }
                }
            } finally {
                database.close()
            }

            when (result) {
                is PublishBundleResult.Ack -> {
                    PhoneWatchContactStore(this).recordContact(System.currentTimeMillis())
                    // A previous process may have committed custody just before losing its scheduler callback.
                    SyncScheduler.enqueueFollowUp(app, MAIN_STREAM)
                    val receiptBytes = WatchCourierCodec.encodeReceipt(result.receipt)
                    WearTaskPolicy.await { Wearable.getMessageClient(this).sendMessage(channel.nodeId, WatchCourierProtocol.MESSAGE_PATH_RECEIPT, receiptBytes) }
                    ensureCourierCapability()
                }
                is PublishBundleResult.Conflict -> {
                    ensureCourierCapability()
                    PhoneWatchContactStore(this).recordContact(System.currentTimeMillis())
                }
                is PublishBundleResult.VersionMismatch -> {
                    ensureCourierCapability()
                    PhoneWatchContactStore(this).recordContact(System.currentTimeMillis(), result.version)
                }
                is PublishBundleResult.Rejected -> {
                    // Malformed or invalid payload: does not count as contact
                }
            }
        } catch (_: Exception) {
        } finally {
            activeChannels.remove(deadline)
            deadline.close()
        }
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        if (messageEvent.path != WatchCourierProtocol.MESSAGE_PATH_HEARTBEAT) {
            super.onMessageReceived(messageEvent)
            return
        }
        val hello = WatchCourierHello.decode(messageEvent.data, WatchCourierProtocol.KIND_HEARTBEAT) ?: return
        try {
            PhoneWatchContactStore(this).recordContact(System.currentTimeMillis(), hello.version, hello.sealedWaiting)
            ensureCourierCapability()
            val reply = WatchCourierHello(WatchCourierProtocol.KIND_STATUS, WatchCourierProtocol.PROTOCOL_VERSION, -1).encode()
            WearTaskPolicy.await { Wearable.getMessageClient(this).sendMessage(messageEvent.sourceNodeId, WatchCourierProtocol.MESSAGE_PATH_STATUS, reply) }
        } catch (_: Exception) {
            // No receipt was sent; the watch retains all material and retries.
        }
    }

    private fun ensureCourierCapability() = acceptExistingCourierCapability {
        WearTaskPolicy.await<Void> {
            Wearable.getCapabilityClient(this).addLocalCapability(WatchCourierProtocol.CAPABILITY_COURIER)
        }
    }

    override fun onDestroy() {
        activeChannels.forEach { it.close() }
        activeChannels.clear()
        super.onDestroy()
    }
}

/** Re-advertising an existing capability must not prevent the status reply. */
internal fun acceptExistingCourierCapability(register: () -> Unit) {
    try {
        register()
    } catch (error: ExecutionException) {
        if ((error.cause as? ApiException)?.statusCode != WearableStatusCodes.DUPLICATE_CAPABILITY) throw error
    }
}
