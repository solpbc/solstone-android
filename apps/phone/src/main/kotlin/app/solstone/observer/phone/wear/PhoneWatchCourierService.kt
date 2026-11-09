// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone.wear

import app.solstone.core.sources.MAIN_STREAM
import app.solstone.core.watch.courier.CodecResult
import app.solstone.core.watch.courier.WatchCourierCodec
import app.solstone.core.watch.courier.WatchCourierProtocol
import app.solstone.platform.persistence.room.openSolstonePersistenceDatabase
import app.solstone.platform.work.PublishBundleResult
import app.solstone.platform.work.SyncScheduler
import app.solstone.platform.work.WatchCustodyPublisher
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.InputStream
import java.util.concurrent.Executors

class PhoneWatchCourierService : WearableListenerService() {
    private val courierExecutor = Executors.newSingleThreadExecutor()

    override fun onChannelOpened(channel: ChannelClient.Channel) {
        if (channel.path != WatchCourierProtocol.CHANNEL_PATH_BUNDLE) {
            super.onChannelOpened(channel)
            return
        }

        courierExecutor.execute {
            val channelClient = Wearable.getChannelClient(this)
            val messageClient = Wearable.getMessageClient(this)
            val capabilityClient = Wearable.getCapabilityClient(this)
            try {
                val inputStream: InputStream = Tasks.await(channelClient.getInputStream(channel))
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
                        val receiptBytes = WatchCourierCodec.encodeReceipt(result.receipt)
                        Tasks.await(messageClient.sendMessage(channel.nodeId, WatchCourierProtocol.MESSAGE_PATH_RECEIPT, receiptBytes))
                        Tasks.await(capabilityClient.addLocalCapability(WatchCourierProtocol.CAPABILITY_COURIER))
                        PhoneWatchContactStore(this).recordContact(System.currentTimeMillis())
                    }
                    is PublishBundleResult.Conflict -> {
                        Tasks.await(capabilityClient.addLocalCapability(WatchCourierProtocol.CAPABILITY_COURIER))
                        PhoneWatchContactStore(this).recordContact(System.currentTimeMillis())
                    }
                    is PublishBundleResult.VersionMismatch -> {
                        Tasks.await(capabilityClient.addLocalCapability(WatchCourierProtocol.CAPABILITY_COURIER))
                        PhoneWatchContactStore(this).recordContact(System.currentTimeMillis())
                    }
                    is PublishBundleResult.Rejected -> {
                        // Malformed or invalid payload: does not count as contact
                    }
                }
            } catch (_: Exception) {
            } finally {
                try {
                    Tasks.await(channelClient.close(channel))
                } catch (_: Exception) {
                }
            }
        }
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        val path = messageEvent.path
        if (path != WatchCourierProtocol.MESSAGE_PATH_HEARTBEAT &&
            path != WatchCourierProtocol.MESSAGE_PATH_STATUS &&
            path != WatchCourierProtocol.MESSAGE_PATH_RECEIPT
        ) {
            super.onMessageReceived(messageEvent)
            return
        }

        courierExecutor.execute {
            try {
                val isFramed = when (path) {
                    WatchCourierProtocol.MESSAGE_PATH_RECEIPT -> {
                        when (WatchCourierCodec.decodeReceipt(messageEvent.data)) {
                            is CodecResult.Success, is CodecResult.VersionMismatch -> true
                            is CodecResult.Malformed -> false
                        }
                    }
                    else -> {
                        if (messageEvent.data.size >= 8) {
                            val dis = DataInputStream(ByteArrayInputStream(messageEvent.data))
                            val kind = dis.readInt()
                            val version = dis.readInt()
                            kind in listOf(
                                WatchCourierProtocol.KIND_BUNDLE_CONTROL,
                                WatchCourierProtocol.KIND_RECEIPT,
                                WatchCourierProtocol.KIND_HEARTBEAT,
                                WatchCourierProtocol.KIND_STATUS,
                            )
                        } else false
                    }
                }

                if (isFramed) {
                    Tasks.await(Wearable.getCapabilityClient(this).addLocalCapability(WatchCourierProtocol.CAPABILITY_COURIER))
                    PhoneWatchContactStore(this).recordContact(System.currentTimeMillis())
                }
            } catch (_: Exception) {
            }
        }
    }

    override fun onDestroy() {
        courierExecutor.shutdown()
        super.onDestroy()
    }
}
