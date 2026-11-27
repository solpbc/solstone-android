// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone.garmin

import android.content.Context
import android.os.Handler
import android.os.Looper
import app.solstone.core.sources.MAIN_STREAM
import app.solstone.core.watch.courier.DecodedBundle
import app.solstone.core.watch.courier.GarminSlabSpool
import app.solstone.core.watch.courier.GarminSlv3Protocol
import app.solstone.observer.phone.AgeSignalGate
import app.solstone.platform.persistence.room.openSolstonePersistenceDatabase
import app.solstone.platform.work.PublishBundleResult
import app.solstone.platform.work.SyncScheduler
import app.solstone.platform.work.WatchCustodyPublisher
import com.garmin.android.connectiq.ConnectIQ
import com.garmin.android.connectiq.IQApp
import com.garmin.android.connectiq.IQDevice
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Lazy-initialized Garmin Connect IQ companion courier for the solstone phone app.
 *
 * Invariants:
 * - Zero Garmin SDK calls ever occur on a phone until `GarminWatchContactStore.isActivated()` is true.
 * - All slab writes and Room custody commits run on a dedicated single-threaded IO executor, never
 *   blocking the main thread, and strictly before sending any half-duplex cumulative ACK.
 * - Background-origin (`FLAG_BG_ORIGIN`) ACKs are delayed by 2.5 seconds so the watch background VM
 *   exits cleanly before the phone's wake doorbell arrives.
 */
object GarminCourierRuntime {
    private val ioExecutor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "solstone-garmin-courier").apply { isDaemon = true }
    }
    private val mainHandler = Handler(Looper.getMainLooper())

    private val sdkInitialized = AtomicBoolean(false)
    private val sdkInitRequested = AtomicBoolean(false)
    private val pendingDoorbells = ConcurrentHashMap<Long, ScheduledFuture<*>>()
    private val pendingColdAcks = ConcurrentHashMap<Long, PendingAck>()

    @Volatile
    private var connectIQ: ConnectIQ? = null

    @Volatile
    private var appContextRef: Context? = null

    private data class PendingAck(
        val device: IQDevice,
        val app: IQApp,
        val ackSeq: Long,
        val isBgOrigin: Boolean,
    )

    fun isSdkRegistered(): Boolean = sdkInitialized.get()

    /**
     * Startup hook called from `PhoneApplication.onCreate`.
     * Reads only local no-backup disk state and returns immediately with zero Garmin SDK calls
     * on every phone that has never activated or heard from a solstone Garmin watch.
     */
    fun ensureStartedIfActivated(context: Context) {
        val appCtx = context.applicationContext
        if (AgeSignalGate.closed()) return
        if (!GarminWatchContactStore(appCtx).isActivated()) return
        startSdkOnMain(appCtx)
    }

    /**
     * Explicit first-contact activation (invoked when the owner opens the companion activation link
     * from the watch or the Garmin setup flow).
     */
    fun activateAndConnect(context: Context) {
        val appCtx = context.applicationContext
        if (AgeSignalGate.closed()) return
        GarminWatchContactStore(appCtx).markActivated()
        startSdkOnMain(appCtx)
    }

    /**
     * Handles an incoming watch message decoded either by the live SDK callback or by
     * `GarminIncomingMessageReceiver` on a cold-started process wake.
     */
    fun handleIncomingMessage(
        context: Context,
        device: IQDevice,
        app: IQApp,
        messageData: List<Any?>?,
        onComplete: () -> Unit = {},
    ) {
        val appCtx = context.applicationContext
        if (AgeSignalGate.closed()) {
            onComplete()
            return
        }
        if (!GarminSlv3Protocol.matchesSolstoneAppId(app.applicationId)) {
            onComplete()
            return
        }
        val rawBytes = GarminSlv3Protocol.extractBinaryPayload(messageData)
        if (rawBytes == null) {
            onComplete()
            return
        }

        ioExecutor.execute {
            try {
                processPayloadOnIo(appCtx, device, app, rawBytes)
            } catch (_: Throwable) {
                // Watch retains unacknowledged slabs and retries cleanly.
            } finally {
                onComplete()
            }
        }
    }

    private fun processPayloadOnIo(
        appCtx: Context,
        device: IQDevice,
        app: IQApp,
        rawBytes: ByteArray,
    ) {
        val nowMs = System.currentTimeMillis()
        val contactStore = GarminWatchContactStore(appCtx)

        if (GarminSlv3Protocol.isSlvs(rawBytes)) {
            val status = GarminSlv3Protocol.parseSlvs(rawBytes) ?: return
            contactStore.recordContact(
                epochMs = nowMs,
                version = status.protoVer,
                waitingSlabs = status.pendingSlabs,
                deviceId = device.deviceIdentifier,
                deviceName = device.friendlyName,
                storageFull = status.storageFull,
                lastEndReason = status.lastEndReason,
            )
            return
        }

        if (!GarminSlv3Protocol.isSlv3(rawBytes)) return

        val spoolDir = File(appCtx.noBackupFilesDir, "garmin_slabs")
        val spool = GarminSlabSpool(spoolDir) { bundle ->
            commitToWatchCustody(appCtx, bundle)
        }
        val action = spool.onSlab(device.deviceIdentifier, rawBytes)
        if (action.rejectedReason == "version_mismatch" && action.watchProtoVer >= 0) {
            contactStore.recordContact(
                epochMs = nowMs,
                version = action.watchProtoVer,
                deviceId = device.deviceIdentifier,
                deviceName = device.friendlyName,
            )
            return
        }
        if (action.rejectedReason != null) return

        contactStore.recordContact(
            epochMs = nowMs,
            version = action.watchProtoVer,
            deviceId = device.deviceIdentifier,
            deviceName = device.friendlyName,
        )

        if (action.shouldAck && action.ackSeq >= 0L) {
            dispatchAckOrQueue(appCtx, device, app, action.ackSeq, action.isBgOrigin)
        }
    }

    private fun commitToWatchCustody(appCtx: Context, bundle: DecodedBundle): GarminSlabSpool.CommitOutcome {
        val spoolPath = appCtx.filesDir.toPath().resolve("spool")
        val stagingPath = appCtx.filesDir.toPath().resolve("staging")
        val database = openSolstonePersistenceDatabase(appCtx)
        return try {
            val publisher = WatchCustodyPublisher(spoolPath, stagingPath, database.segmentDao())
            when (val res = publisher.publishDecoded(bundle) {
                SyncScheduler.enqueueFollowUp(appCtx, MAIN_STREAM)
            }) {
                is PublishBundleResult.Ack -> {
                    SyncScheduler.enqueueFollowUp(appCtx, MAIN_STREAM)
                    GarminSlabSpool.CommitOutcome.Committed
                }
                is PublishBundleResult.Conflict -> GarminSlabSpool.CommitOutcome.Duplicate
                is PublishBundleResult.VersionMismatch -> GarminSlabSpool.CommitOutcome.Failed("version_mismatch")
                is PublishBundleResult.Rejected -> GarminSlabSpool.CommitOutcome.Failed(res.reason)
            }
        } catch (t: Throwable) {
            GarminSlabSpool.CommitOutcome.Failed(t.javaClass.simpleName)
        } finally {
            database.close()
        }
    }

    private fun dispatchAckOrQueue(
        appCtx: Context,
        device: IQDevice,
        app: IQApp,
        ackSeq: Long,
        isBgOrigin: Boolean,
    ) {
        if (!sdkInitialized.get() || connectIQ == null) {
            // Cold-woke from static BroadcastReceiver before ConnectIQ bound: queue ACK & start SDK
            pendingColdAcks[device.deviceIdentifier] = PendingAck(device, app, ackSeq, isBgOrigin)
            startSdkOnMain(appCtx)
            return
        }
        if (isBgOrigin) {
            scheduleDoorbellAck(device, app, ackSeq)
        } else {
            sendAckNow(device, app, ackSeq)
        }
    }

    private fun scheduleDoorbellAck(device: IQDevice, app: IQApp, ackSeq: Long) {
        pendingDoorbells.remove(device.deviceIdentifier)?.cancel(false)
        val future = ioExecutor.schedule(
            { sendAckNow(device, app, ackSeq) },
            GarminSlv3Protocol.BG_DOORBELL_DELAY_MS,
            TimeUnit.MILLISECONDS,
        )
        pendingDoorbells[device.deviceIdentifier] = future
    }

    private fun sendAckNow(device: IQDevice, app: IQApp, ackSeq: Long) {
        val ciq = connectIQ ?: return
        if (!sdkInitialized.get()) return
        val payload = GarminSlv3Protocol.buildAckPayload(ackSeq)
        mainHandler.post {
            try {
                ciq.sendMessage(device, app, payload) { _, _, _ -> }
            } catch (_: Throwable) {
                // Watch half-duplex timeout rewinds and retries if ACK fails.
            }
        }
    }

    private fun startSdkOnMain(appCtx: Context) {
        appContextRef = appCtx
        if (sdkInitialized.get()) {
            mainHandler.post { registerAllKnownDevices() }
            return
        }
        if (!sdkInitRequested.compareAndSet(false, true)) return

        mainHandler.post {
            try {
                if (GarminEnvironmentProbe.garminConnectStatus(appCtx) != GarminEnvironmentProbe.ConnectAppStatus.READY) {
                    sdkInitRequested.set(false)
                    return@post
                }
                val ciq = ConnectIQ.getInstance(appCtx, ConnectIQ.IQConnectType.WIRELESS)
                connectIQ = ciq
                ciq.initialize(
                    appCtx,
                    false, // Never show Garmin's intrusive install/upgrade dialog
                    object : ConnectIQ.ConnectIQListener {
                        override fun onSdkReady() {
                            sdkInitialized.set(true)
                            registerAllKnownDevices()
                            flushPendingColdAcks()
                        }

                        override fun onInitializeError(status: ConnectIQ.IQSdkErrorStatus?) {
                            sdkInitialized.set(false)
                            sdkInitRequested.set(false)
                        }

                        override fun onSdkShutDown() {
                            sdkInitialized.set(false)
                            sdkInitRequested.set(false)
                        }
                    },
                )
            } catch (_: Throwable) {
                sdkInitialized.set(false)
                sdkInitRequested.set(false)
            }
        }
    }

    private fun registerAllKnownDevices() {
        val ciq = connectIQ ?: return
        val appCtx = appContextRef ?: return
        if (!sdkInitialized.get()) return
        try {
            val watchApp = IQApp(GarminSlv3Protocol.SOLSTONE_GARMIN_APP_ID)
            val known = ciq.knownDevices ?: emptyList()
            for (dev in known) {
                ciq.registerForDeviceEvents(dev) { d, st ->
                    if (st == IQDevice.IQDeviceStatus.CONNECTED) {
                        bindWatchApp(ciq, appCtx, d, watchApp)
                    }
                }
                bindWatchApp(ciq, appCtx, dev, watchApp)
            }
        } catch (_: Throwable) {
        }
    }

    private fun bindWatchApp(ciq: ConnectIQ, appCtx: Context, device: IQDevice, watchApp: IQApp) {
        try {
            ciq.registerForAppEvents(device, watchApp) { d, a, messageData, status ->
                if (status == ConnectIQ.IQMessageStatus.SUCCESS && messageData != null) {
                    handleIncomingMessage(appCtx, d, a, messageData)
                }
            }
        } catch (_: Throwable) {
        }
    }

    private fun flushPendingColdAcks() {
        val snapshot = pendingColdAcks.values.toList()
        pendingColdAcks.clear()
        for (pending in snapshot) {
            if (pending.isBgOrigin) {
                scheduleDoorbellAck(pending.device, pending.app, pending.ackSeq)
            } else {
                sendAckNow(pending.device, pending.app, pending.ackSeq)
            }
        }
    }
}
