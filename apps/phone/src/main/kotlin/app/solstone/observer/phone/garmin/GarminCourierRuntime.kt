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
import app.solstone.core.watch.courier.SunZonePointLookup
import app.solstone.observer.phone.AgeSignalGate
import app.solstone.observer.phone.wear.PhoneWatchContactStore
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
import java.util.concurrent.atomic.AtomicLong

enum class GarminDeviceStatus {
    CONNECTED,
    NOT_CONNECTED,
    NOT_PAIRED,
    UNKNOWN,
}

sealed interface GarminWatchAppStatus {
    data class Installed(val version: Int) : GarminWatchAppStatus
    data object NotInstalled : GarminWatchAppStatus
    data object Unknown : GarminWatchAppStatus
}

data class GarminStatusSnapshot(
    val connectAppStatus: GarminEnvironmentProbe.ConnectAppStatus,
    val deviceStatus: GarminDeviceStatus,
    val deviceName: String?,
    val watchAppStatus: GarminWatchAppStatus,
)

/**
 * Lazy-initialized Garmin Connect IQ companion courier for the solstone phone app.
 *
 * Invariants:
 * - Zero Garmin SDK calls ever occur on a phone until `PhoneWatchContactStore.isGarminActivated()` is true.
 * - All slab writes and Room custody commits run on a dedicated single-threaded IO executor, never
 *   blocking the main thread, and strictly before sending any half-duplex cumulative ACK.
 * - ACKs carry `"a"`, `"v"`, and optional `"z": [lat100, lon100]` from the phone's static tzdb lookup
 *   (zero location APIs) for the watch sun arc.
 * - Background-origin (`FLAG_BG_ORIGIN`) ACKs are delayed by 2.5 seconds so the watch background VM
 *   exits cleanly before the phone's wake doorbell arrives.
 */
object GarminCourierRuntime {
    private const val APP_INFO_REFRESH_MIN_MS = 15_000L
    private const val COLD_WAKE_HOLD_MAX_MS = 6_000L

    private val ioExecutor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "solstone-garmin-courier").apply { isDaemon = true }
    }
    private val mainHandler = Handler(Looper.getMainLooper())

    private val sdkInitialized = AtomicBoolean(false)
    private val sdkInitRequested = AtomicBoolean(false)
    private val pendingDoorbells = ConcurrentHashMap<Long, PendingDoorbell>()
    private val pendingColdAcks = ConcurrentHashMap<Long, PendingAck>()
    private val lastAppInfoProbeMs = AtomicLong(0L)

    @Volatile
    private var connectIQ: ConnectIQ? = null

    @Volatile
    private var appContextRef: Context? = null

    @Volatile
    private var cachedAppStatus: GarminWatchAppStatus = GarminWatchAppStatus.Unknown

    private class OnceCompletion(
        private val callback: () -> Unit,
    ) {
        private val done = AtomicBoolean(false)

        @Volatile
        private var timeoutTask: ScheduledFuture<*>? = null

        fun armTimeout(delayMs: Long) {
            timeoutTask = ioExecutor.schedule({ complete() }, delayMs, TimeUnit.MILLISECONDS)
        }

        fun complete() {
            if (done.compareAndSet(false, true)) {
                timeoutTask?.cancel(false)
                runCatching { callback() }
            }
        }
    }

    private data class PendingAck(
        val device: IQDevice,
        val app: IQApp,
        val ackSeq: Long,
        val isBgOrigin: Boolean,
        val completion: OnceCompletion?,
    )

    private data class PendingDoorbell(
        val future: ScheduledFuture<*>,
        val completion: OnceCompletion?,
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
        if (!PhoneWatchContactStore(appCtx).isGarminActivated()) return
        startSdkOnMain(appCtx)
    }

    /**
     * Explicit first-contact activation (invoked only on owner tap of `https://go.solstone.app/garmin`).
     */
    fun activateAndConnect(context: Context) {
        val appCtx = context.applicationContext
        if (AgeSignalGate.closed()) return
        PhoneWatchContactStore(appCtx).recordGarminActivated()
        startSdkOnMain(appCtx)
    }

    /**
     * Read-only Garmin status probe for the watch source page (dead-end detection).
     * Strictly gated on prior Garmin activation: returns `null` with zero PackageManager or Garmin SDK calls
     * on any phone that has never activated or heard from a solstone Garmin watch.
     */
    fun probeStatusIfActivated(context: Context): GarminStatusSnapshot? {
        val appCtx = context.applicationContext
        val contact = PhoneWatchContactStore(appCtx).read()
        if (contact == null || !contact.isGarmin) return null

        val gcmStatus = GarminEnvironmentProbe.garminConnectStatus(appCtx)
        if (gcmStatus != GarminEnvironmentProbe.ConnectAppStatus.READY) {
            return GarminStatusSnapshot(
                connectAppStatus = gcmStatus,
                deviceStatus = GarminDeviceStatus.NOT_PAIRED,
                deviceName = contact.deviceName,
                watchAppStatus = GarminWatchAppStatus.Unknown,
            )
        }

        if (!sdkInitialized.get()) {
            startSdkOnMain(appCtx)
            return GarminStatusSnapshot(
                connectAppStatus = gcmStatus,
                deviceStatus = GarminDeviceStatus.UNKNOWN,
                deviceName = contact.deviceName,
                watchAppStatus = cachedAppStatus,
            )
        }

        val ciq = connectIQ ?: return GarminStatusSnapshot(
            connectAppStatus = gcmStatus,
            deviceStatus = GarminDeviceStatus.UNKNOWN,
            deviceName = contact.deviceName,
            watchAppStatus = cachedAppStatus,
        )

        val known = runCatching { ciq.knownDevices.orEmpty() }.getOrElse { emptyList() }
        if (known.isEmpty()) {
            return GarminStatusSnapshot(
                connectAppStatus = gcmStatus,
                deviceStatus = GarminDeviceStatus.NOT_PAIRED,
                deviceName = contact.deviceName,
                watchAppStatus = GarminWatchAppStatus.Unknown,
            )
        }

        val connected = runCatching { ciq.connectedDevices.orEmpty() }.getOrElse { emptyList() }
        val preferred = connected.firstOrNull { it.deviceIdentifier == contact.deviceId }
            ?: connected.firstOrNull()
            ?: known.firstOrNull { it.deviceIdentifier == contact.deviceId }
            ?: known.first()

        val isConnected = connected.any { it.deviceIdentifier == preferred.deviceIdentifier }
        if (isConnected) {
            maybeRefreshWatchAppInfo(ciq, preferred)
        }

        return GarminStatusSnapshot(
            connectAppStatus = gcmStatus,
            deviceStatus = if (isConnected) GarminDeviceStatus.CONNECTED else GarminDeviceStatus.NOT_CONNECTED,
            deviceName = preferred.friendlyName?.takeIf { it.isNotBlank() } ?: contact.deviceName,
            watchAppStatus = cachedAppStatus,
        )
    }

    /**
     * Handles an incoming watch message decoded either by the live SDK callback or by
     * `GarminIncomingMessageReceiver` on a cold-started process wake.
     *
     * When [onComplete] is supplied (from `BroadcastReceiver.goAsync()`), it is held until any
     * required ACK has been handed off to Garmin Connect Mobile (or bounded by
     * [COLD_WAKE_HOLD_MAX_MS]) so Android's cached-app freezer cannot suspend the process mid-ACK.
     */
    fun handleIncomingMessage(
        context: Context,
        device: IQDevice,
        app: IQApp,
        messageData: List<Any?>?,
        onComplete: (() -> Unit)? = null,
    ) {
        val completion = onComplete?.let { cb ->
            OnceCompletion(cb).apply { armTimeout(COLD_WAKE_HOLD_MAX_MS) }
        }
        val appCtx = context.applicationContext
        if (AgeSignalGate.closed()) {
            completion?.complete()
            return
        }
        if (!GarminSlv3Protocol.matchesSolstoneAppId(app.applicationId)) {
            completion?.complete()
            return
        }
        val rawBytes = GarminSlv3Protocol.extractBinaryPayload(messageData)
        if (rawBytes == null) {
            completion?.complete()
            return
        }

        ioExecutor.execute {
            var handedOffToAck = false
            try {
                handedOffToAck = processPayloadOnIo(appCtx, device, app, rawBytes, completion)
            } catch (_: Throwable) {
                // Watch retains unacknowledged slabs and retries cleanly.
            } finally {
                if (!handedOffToAck) {
                    completion?.complete()
                }
            }
        }
    }

    private fun processPayloadOnIo(
        appCtx: Context,
        device: IQDevice,
        app: IQApp,
        rawBytes: ByteArray,
        completion: OnceCompletion?,
    ): Boolean {
        val nowMs = System.currentTimeMillis()
        val contactStore = PhoneWatchContactStore(appCtx)

        if (GarminSlv3Protocol.isSlvs(rawBytes)) {
            val status = GarminSlv3Protocol.parseSlvs(rawBytes) ?: return false
            contactStore.recordGarminContact(
                epochMs = nowMs,
                version = status.protoVer,
                waitingSlabs = status.pendingSlabs,
                deviceId = device.deviceIdentifier,
                deviceName = device.friendlyName,
                storageFull = status.storageFull,
                lastEndReason = status.lastEndReason,
            )
            return false
        }

        if (!GarminSlv3Protocol.isSlv3(rawBytes)) return false

        val spoolDir = File(appCtx.noBackupFilesDir, "garmin_slabs")
        val spool = GarminSlabSpool(spoolDir) { bundle ->
            commitToWatchCustody(appCtx, bundle)
        }
        val action = spool.onSlab(device.deviceIdentifier, rawBytes)
        if (action.rejectedReason == "version_mismatch" && action.watchProtoVer >= 0) {
            contactStore.recordGarminContact(
                epochMs = nowMs,
                version = action.watchProtoVer,
                deviceId = device.deviceIdentifier,
                deviceName = device.friendlyName,
            )
            return false
        }
        if (action.rejectedReason != null) return false

        contactStore.recordGarminContact(
            epochMs = nowMs,
            version = action.watchProtoVer,
            waitingSlabs = if (action.queueDrained) 0 else null,
            deviceId = device.deviceIdentifier,
            deviceName = device.friendlyName,
            storageFull = if (action.queueDrained) false else null,
        )

        if (action.shouldAck && action.ackSeq >= 0L) {
            dispatchAckOrQueue(appCtx, device, app, action.ackSeq, action.isBgOrigin, completion)
            return true
        }
        return false
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
        completion: OnceCompletion?,
    ) {
        if (!sdkInitialized.get() || connectIQ == null) {
            val prev = pendingColdAcks.put(
                device.deviceIdentifier,
                PendingAck(device, app, ackSeq, isBgOrigin, completion),
            )
            prev?.completion?.complete()
            startSdkOnMain(appCtx)
            return
        }
        if (isBgOrigin) {
            scheduleDoorbellAck(device, app, ackSeq, completion)
        } else {
            sendAckNow(device, app, ackSeq, completion)
        }
    }

    private fun scheduleDoorbellAck(
        device: IQDevice,
        app: IQApp,
        ackSeq: Long,
        completion: OnceCompletion?,
    ) {
        pendingDoorbells.remove(device.deviceIdentifier)?.let { prev ->
            prev.future.cancel(false)
            prev.completion?.complete()
        }
        val entryRef = java.util.concurrent.atomic.AtomicReference<PendingDoorbell?>(null)
        val future = ioExecutor.schedule(
            {
                entryRef.get()?.let { pendingDoorbells.remove(device.deviceIdentifier, it) }
                sendAckNow(device, app, ackSeq, completion)
            },
            GarminSlv3Protocol.BG_DOORBELL_DELAY_MS,
            TimeUnit.MILLISECONDS,
        )
        val entry = PendingDoorbell(future, completion)
        entryRef.set(entry)
        val displaced = pendingDoorbells.put(device.deviceIdentifier, entry)
        if (displaced != null) {
            displaced.future.cancel(false)
            displaced.completion?.complete()
        }
    }

    private fun sendAckNow(
        device: IQDevice,
        app: IQApp,
        ackSeq: Long,
        completion: OnceCompletion?,
    ) {
        val ciq = connectIQ
        if (ciq == null || !sdkInitialized.get()) {
            completion?.complete()
            return
        }
        val tzPoint = SunZonePointLookup.systemDefaultLatLon100()
        val payload = GarminSlv3Protocol.buildAckPayload(ackSeq, tzPoint)
        val posted = mainHandler.post {
            try {
                ciq.sendMessage(device, app, payload) { _, _, _ ->
                    completion?.complete()
                }
            } catch (_: Throwable) {
                // Watch half-duplex timeout rewinds and retries if ACK fails.
                completion?.complete()
            }
        }
        if (!posted) {
            completion?.complete()
        }
    }

    private fun startSdkOnMain(appCtx: Context) {
        appContextRef = appCtx
        if (sdkInitialized.get()) {
            mainHandler.post {
                registerAllKnownDevices()
                flushPendingColdAcks()
            }
            return
        }
        if (!sdkInitRequested.compareAndSet(false, true)) return

        mainHandler.post {
            try {
                if (GarminEnvironmentProbe.garminConnectStatus(appCtx) != GarminEnvironmentProbe.ConnectAppStatus.READY) {
                    sdkInitRequested.set(false)
                    failPendingColdAcks()
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
                            failPendingColdAcks()
                        }

                        override fun onSdkShutDown() {
                            sdkInitialized.set(false)
                            sdkInitRequested.set(false)
                            failPendingColdAcks()
                        }
                    },
                )
            } catch (_: Throwable) {
                sdkInitialized.set(false)
                sdkInitRequested.set(false)
                failPendingColdAcks()
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
                        maybeRefreshWatchAppInfo(ciq, d, force = true)
                    }
                }
                bindWatchApp(ciq, appCtx, dev, watchApp)
                maybeRefreshWatchAppInfo(ciq, dev, force = false)
            }
        } catch (_: Throwable) {
        }
    }

    private fun maybeRefreshWatchAppInfo(ciq: ConnectIQ, device: IQDevice, force: Boolean = false) {
        val now = System.currentTimeMillis()
        val prev = lastAppInfoProbeMs.get()
        if (!force && now - prev < APP_INFO_REFRESH_MIN_MS) return
        if (!lastAppInfoProbeMs.compareAndSet(prev, now)) return
        mainHandler.post {
            try {
                ciq.getApplicationInfo(
                    GarminSlv3Protocol.SOLSTONE_GARMIN_APP_ID,
                    device,
                    object : ConnectIQ.IQApplicationInfoListener {
                        override fun onApplicationInfoReceived(app: IQApp?) {
                            cachedAppStatus = if (app != null && app.status == IQApp.IQAppStatus.INSTALLED) {
                                GarminWatchAppStatus.Installed(app.version())
                            } else {
                                GarminWatchAppStatus.NotInstalled
                            }
                        }

                        override fun onApplicationNotInstalled(applicationId: String?) {
                            cachedAppStatus = GarminWatchAppStatus.NotInstalled
                        }
                    },
                )
            } catch (_: Throwable) {
            }
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
        val keys = pendingColdAcks.keys.toList()
        for (key in keys) {
            val pending = pendingColdAcks.remove(key) ?: continue
            if (pending.isBgOrigin) {
                scheduleDoorbellAck(pending.device, pending.app, pending.ackSeq, pending.completion)
            } else {
                sendAckNow(pending.device, pending.app, pending.ackSeq, pending.completion)
            }
        }
    }

    private fun failPendingColdAcks() {
        val keys = pendingColdAcks.keys.toList()
        for (key in keys) {
            pendingColdAcks.remove(key)?.completion?.complete()
        }
    }
}
