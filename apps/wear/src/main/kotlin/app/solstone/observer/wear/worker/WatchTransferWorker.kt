// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.wear.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.solstone.core.watch.courier.CodecResult
import app.solstone.core.watch.courier.TransferPlanReason
import app.solstone.core.watch.courier.WatchBundleReceiptRule
import app.solstone.core.watch.courier.WatchCourierCodec
import app.solstone.core.watch.courier.WatchCourierProtocol
import app.solstone.core.watch.courier.WatchTransferPlanner
import app.solstone.observer.wear.spool.WatchSpoolStore
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.ChannelClient
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import java.io.FileInputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class WatchTransferWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    companion object {
        private const val WORK_NAME = "WatchTransferWorker"

        fun enqueue(context: Context) {
            val req = OneTimeWorkRequestBuilder<WatchTransferWorker>().build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.KEEP,
                req,
            )
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val store = WatchSpoolStore(applicationContext)
        store.recoverAbandonedSessions()
        val stagedList = store.listStagedBundles()
        if (stagedList.isEmpty()) {
            return@withContext Result.success()
        }

        val capabilityClient = Wearable.getCapabilityClient(applicationContext)
        val nodeClient = Wearable.getNodeClient(applicationContext)
        val channelClient = Wearable.getChannelClient(applicationContext)
        val messageClient = Wearable.getMessageClient(applicationContext)

        val reachableCapabilityNodes = try {
            val cap = Tasks.await(
                capabilityClient.getCapability(
                    WatchCourierProtocol.CAPABILITY_COURIER,
                    CapabilityClient.FILTER_REACHABLE,
                ),
                5,
                TimeUnit.SECONDS,
            )
            cap.nodes.map { it.id }.toSet()
        } catch (_: Exception) {
            emptySet()
        }

        val connectedNodes = try {
            val nodes = Tasks.await(nodeClient.connectedNodes, 5, TimeUnit.SECONDS)
            nodes.map { it.id }.toSet()
        } catch (_: Exception) {
            emptySet()
        }

        val stagedBundles = stagedList.map { it.second }
        val plan = WatchTransferPlanner.plan(
            stagedBundles = stagedBundles,
            reachableCapabilityNodes = reachableCapabilityNodes,
            connectedNodes = connectedNodes,
            cachedNodeId = null,
            batchCap = WatchCourierProtocol.DEFAULT_BUNDLE_BATCH_CAP,
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
            val file = fileMap[staged.bundleId] ?: continue
            var transferSucceeded = false
            try {
                val channel = Tasks.await(
                    channelClient.openChannel(destinationNode, WatchCourierProtocol.CHANNEL_PATH_BUNDLE),
                    10,
                    TimeUnit.SECONDS,
                )

                val receiptLatch = CountDownLatch(1)
                var receivedReceiptBytes: ByteArray? = null
                val messageListener = MessageClient.OnMessageReceivedListener { event: MessageEvent ->
                    if (event.path == WatchCourierProtocol.MESSAGE_PATH_RECEIPT &&
                        event.sourceNodeId == destinationNode
                    ) {
                        receivedReceiptBytes = event.data
                        receiptLatch.countDown()
                    }
                }
                messageClient.addListener(messageListener)

                try {
                    val outputStream: OutputStream = Tasks.await(
                        channelClient.getOutputStream(channel),
                        10,
                        TimeUnit.SECONDS,
                    )
                    val maxBytesToCopy = minOf(file.length(), WatchCourierProtocol.MAX_BUNDLE_BYTES)
                    outputStream.use { os ->
                        FileInputStream(file).use { fis ->
                            val buffer = ByteArray(8192)
                            var totalWritten = 0L
                            while (totalWritten < maxBytesToCopy) {
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
                    messageClient.removeListener(messageListener)
                    try {
                        Tasks.await(channelClient.close(channel), 5, TimeUnit.SECONDS)
                    } catch (_: Exception) {
                    }
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
    }
}
