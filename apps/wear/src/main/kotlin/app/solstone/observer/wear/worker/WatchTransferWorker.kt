// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.wear.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.solstone.core.watch.courier.CodecResult
import app.solstone.core.watch.courier.TransferPlanReason
import app.solstone.core.watch.courier.WatchBundleReceiptRule
import app.solstone.core.watch.courier.WatchCourierHello
import app.solstone.core.watch.courier.WatchCourierCodec
import app.solstone.core.watch.courier.WatchCourierProtocol
import app.solstone.core.watch.courier.WatchTransferPlanner
import app.solstone.observer.wear.spool.WatchSpoolStore
import app.solstone.platform.wearable.WearTaskPolicy
import app.solstone.platform.wearable.WearChannelDeadline
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import java.io.FileInputStream
import java.io.OutputStream
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class WatchTransferWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    companion object {
        private const val WORK_NAME = "WatchTransferWorker"

        private val transferMutex = Mutex()

        fun enqueue(context: Context, urgent: Boolean = false) {
            // A persisted fallback also recovers an unfinished recording after process loss/reboot.
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "WatchCourierRecovery", ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<WatchTransferWorker>(15, TimeUnit.MINUTES).build(),
            )
            val req = OneTimeWorkRequestBuilder<WatchTransferWorker>().build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                if (urgent) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
                req,
            )
        }
    }

    override suspend fun doWork(): Result = transferMutex.withLock { transfer() }

    private suspend fun transfer(): Result = withContext(Dispatchers.IO) {
        val activeChannel = AtomicReference<WearChannelDeadline?>()
        val cancellationCloser = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                activeChannel.getAndSet(null)?.close()
            }
        }
        try {
        val store = WatchSpoolStore(applicationContext)
        store.recoverAbandonedSessions()
        val stagedList = store.listStagedBundles()
        val reachableCapabilityNodes = try {
            val cap = WearTaskPolicy.await(5) {
                Wearable.getCapabilityClient(applicationContext).getCapability(
                    WatchCourierProtocol.CAPABILITY_COURIER,
                    CapabilityClient.FILTER_REACHABLE,
                )
            }
            cap.nodes.map { it.id }.toSet()
        } catch (_: Exception) {
            emptySet()
        }

        val currentNodes = try {
            WearTaskPolicy.await(5) { Wearable.getNodeClient(applicationContext).connectedNodes }
        } catch (_: Exception) {
            emptyList()
        }
        val connectedNodes = currentNodes.map { it.id }.toSet()
        val nearbyNodes = currentNodes.filter { it.isNearby }.map { it.id }.toSet()

        runCatching { WatchPeerStatusStore(applicationContext).attempted() }
        currentNodes.sortedWith(compareByDescending<com.google.android.gms.wearable.Node> { it.isNearby }.thenBy { it.id }).firstOrNull()?.id?.let { node ->
            runCatching {
                val hello = WatchCourierHello(WatchCourierProtocol.KIND_HEARTBEAT,
                    WatchCourierProtocol.PROTOCOL_VERSION, stagedList.size).encode()
                WearTaskPolicy.await(5) { Wearable.getMessageClient(applicationContext).sendMessage(node, WatchCourierProtocol.MESSAGE_PATH_HEARTBEAT, hello) }
            }
        }
        if (stagedList.isEmpty()) return@withContext Result.success()

        val stagedBundles = stagedList.map { it.second }
        val plan = WatchTransferPlanner.plan(
            stagedBundles = stagedBundles,
            reachableCapabilityNodes = reachableCapabilityNodes,
            connectedNodes = connectedNodes,
            cachedNodeId = null,
            batchCap = WatchCourierProtocol.DEFAULT_BUNDLE_BATCH_CAP,
            nearbyNodeIds = nearbyNodes,
        )

        val destinationNode = plan.destinationNodeId
        if (destinationNode == null) {
            return@withContext if (plan.reason == TransferPlanReason.UNREACHABLE) {
                Result.retry()
            } else {
                Result.success()
            }
        }

        val fileMap = stagedList.associate { it.second.bundleId to it.first }

        for (staged in plan.bundlesToSend) {
            ensureActive()
            val file = fileMap[staged.bundleId] ?: continue
            var transferSucceeded = false
            try {
                val channel = WearTaskPolicy.await {
                    Wearable.getChannelClient(applicationContext).openChannel(destinationNode, WatchCourierProtocol.CHANNEL_PATH_BUNDLE)
                }

                val receiptLatch = CountDownLatch(1)
                val deadline = WearChannelDeadline {
                    try {
                        WearTaskPolicy.createTask { Wearable.getChannelClient(applicationContext).close(channel) }
                    } finally {
                        receiptLatch.countDown()
                    }
                }
                activeChannel.set(deadline)
                try {
                    ensureActive()
                var receivedReceiptBytes: ByteArray? = null
                val messageListener = MessageClient.OnMessageReceivedListener { event: MessageEvent ->
                    if (event.path == WatchCourierProtocol.MESSAGE_PATH_RECEIPT &&
                        event.sourceNodeId == destinationNode
                    ) {
                        val decoded = WatchCourierCodec.decodeReceipt(event.data)
                        if (decoded is CodecResult.Success && WatchBundleReceiptRule.mayDelete(staged, decoded.value)) {
                            receivedReceiptBytes = event.data
                            receiptLatch.countDown()
                        }
                    }
                }
                try {
                    WearTaskPolicy.await(5) { Wearable.getMessageClient(applicationContext).addListener(messageListener) }
                    val outputStream: OutputStream = deadline.own(WearTaskPolicy.await {
                        Wearable.getChannelClient(applicationContext).getOutputStream(channel)
                    })
                    val maxBytesToCopy = file.length()
                    if (maxBytesToCopy <= 0 || maxBytesToCopy > WatchCourierProtocol.MAX_BUNDLE_BYTES) throw IOException("invalid staged bundle size")
                    outputStream.use { os ->
                        FileInputStream(file).use { fis ->
                            val buffer = ByteArray(8192)
                            var totalWritten = 0L
                            while (totalWritten < maxBytesToCopy) {
                                ensureActive()
                                val toRead = minOf(buffer.size.toLong(), maxBytesToCopy - totalWritten).toInt()
                                val r = fis.read(buffer, 0, toRead)
                                if (r == -1) break
                                os.write(buffer, 0, r)
                                totalWritten += r
                            }
                            os.flush()
                        }
                    }

                    // Await receipt from phone
                    if (receiptLatch.await(15, TimeUnit.SECONDS)) {
                        receivedReceiptBytes?.let { bytes ->
                            val decodedReceipt = WatchCourierCodec.decodeReceipt(bytes)
                            if (decodedReceipt is CodecResult.Success) {
                                val receipt = decodedReceipt.value
                                if (WatchBundleReceiptRule.mayDelete(staged, receipt)) {
                                    if (store.deleteBundle(file, receipt)) {
                                        transferSucceeded = true
                                    }
                                }
                            }
                        }
                    }
                } finally {
                    try {
                        WearTaskPolicy.await(5) { Wearable.getMessageClient(applicationContext).removeListener(messageListener) }
                    } catch (_: Exception) {
                    }
                }
                } finally {
                    activeChannel.compareAndSet(deadline, null)
                    deadline.close()
                }
            } catch (_: Exception) {
            }

            if (!transferSucceeded) {
                // On failure, stop. Do not send younger bundles in that run.
                break
            }
        }

        if (plan.reschedule || store.listPendingFiles().isNotEmpty()) {
            Result.retry()
        } else {
            Result.success()
        }
        } catch (_: IOException) {
            Result.retry()
        } finally {
            activeChannel.getAndSet(null)?.close()
            cancellationCloser.cancelAndJoin()
        }
    }
}
