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
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import java.io.InputStream
import java.util.concurrent.Executors
import java.util.concurrent.ConcurrentHashMap

class PhoneWatchCourierService : WearableListenerService() {
    private val courierExecutor = Executors.newSingleThreadExecutor()
    private val activeChannels = ConcurrentHashMap.newKeySet<WearChannelDeadline>()

    override fun onChannelOpened(channel: ChannelClient.Channel) {
        if (channel.path != WatchCourierProtocol.CHANNEL_PATH_BUNDLE) {
            super.onChannelOpened(channel)
            return
        }

        courierExecutor.execute {
            val deadline = WearChannelDeadline {
                WearTaskPolicy.createTask { Wearable.getChannelClient(this).close(channel) }
            }
            activeChannels.add(deadline)
            try {
                val inputStream: InputStream = deadline.own(WearTaskPolicy.await { Wearable.getChannelClient(this).getInputStream(channel) })
                val app = applicationContext
                val spoolDir = app.filesDir.toPath().resolve("spool")
                val stagingDir = app.filesDir.toPath().resolve("staging")
                val dao = openSolstonePersistenceDatabase(app).segmentDao()
                val publisher = WatchCustodyPublisher(spoolDir, stagingDir, dao)

                val result = inputStream.use { stream ->
                    publisher.publish(stream) {
                        SyncScheduler.enqueueFollowUp(app, MAIN_STREAM)
                    }
                }

                when (result) {
                    is PublishBundleResult.Ack -> {
                        PhoneWatchContactStore(this).recordContact(System.currentTimeMillis())
                        // A previous process may have committed custody just before losing its scheduler callback.
                        SyncScheduler.enqueueFollowUp(app, MAIN_STREAM)
                        val receiptBytes = WatchCourierCodec.encodeReceipt(result.receipt)
                        WearTaskPolicy.await { Wearable.getMessageClient(this).sendMessage(channel.nodeId, WatchCourierProtocol.MESSAGE_PATH_RECEIPT, receiptBytes) }
                        WearTaskPolicy.await { Wearable.getCapabilityClient(this).addLocalCapability(WatchCourierProtocol.CAPABILITY_COURIER) }
                    }
                    is PublishBundleResult.Conflict -> {
                        WearTaskPolicy.await { Wearable.getCapabilityClient(this).addLocalCapability(WatchCourierProtocol.CAPABILITY_COURIER) }
                        PhoneWatchContactStore(this).recordContact(System.currentTimeMillis())
                    }
                    is PublishBundleResult.VersionMismatch -> {
                        WearTaskPolicy.await { Wearable.getCapabilityClient(this).addLocalCapability(WatchCourierProtocol.CAPABILITY_COURIER) }
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
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        if (messageEvent.path != WatchCourierProtocol.MESSAGE_PATH_HEARTBEAT) {
            super.onMessageReceived(messageEvent)
            return
        }
        val hello = WatchCourierHello.decode(messageEvent.data, WatchCourierProtocol.KIND_HEARTBEAT) ?: return
        courierExecutor.execute {
            try {
                PhoneWatchContactStore(this).recordContact(System.currentTimeMillis(), hello.version, hello.sealedWaiting)
                WearTaskPolicy.await { Wearable.getCapabilityClient(this).addLocalCapability(WatchCourierProtocol.CAPABILITY_COURIER) }
                val reply = WatchCourierHello(WatchCourierProtocol.KIND_STATUS, WatchCourierProtocol.PROTOCOL_VERSION, -1).encode()
                WearTaskPolicy.await { Wearable.getMessageClient(this).sendMessage(messageEvent.sourceNodeId, WatchCourierProtocol.MESSAGE_PATH_STATUS, reply) }
            } catch (_: Exception) {
                // No receipt was sent; the watch retains all material and retries.
            }
        }
    }

    override fun onDestroy() {
        activeChannels.forEach { it.close() }
        activeChannels.clear()
        courierExecutor.shutdownNow()
        super.onDestroy()
    }
}
