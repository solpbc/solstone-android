// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.work

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import app.solstone.core.identity.ClientCredential
import app.solstone.core.model.BundleFile
import app.solstone.core.pl.ClientReportedDescription
import app.solstone.core.identity.IdentityMutator
import app.solstone.core.identity.PairingGeneration
import app.solstone.core.model.PairedHome
import app.solstone.core.pl.JournalVersionRefreshCoordinator
import app.solstone.core.pl.PlHttpClient
import app.solstone.core.pl.RelayAccessRefreshCoordinator
import app.solstone.core.spool.parseManifest
import app.solstone.platform.persistence.room.ConfirmedCopyFinisher
import app.solstone.platform.persistence.room.SegmentRow
import app.solstone.platform.persistence.room.openSolstonePersistenceDatabase
import app.solstone.platform.pl.transport.conscrypt.RelayDialWaitingException
import app.solstone.platform.pl.transport.conscrypt.RelayWebSocketClosedException
import app.solstone.platform.pl.transport.conscrypt.defaultHttpsPoster
import app.solstone.platform.pl.transport.conscrypt.openAuthenticatedClient
import app.solstone.platform.pl.transport.conscrypt.openRelaySyncClient
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

import app.solstone.core.push.PushRegistrationCoordinator

import app.solstone.core.identity.JournalConfirmationPolicy
import app.solstone.core.identity.StoreInspectResult
import app.solstone.core.identity.confirmedFor
import app.solstone.core.model.QueueState
import app.solstone.platform.persistence.room.EventRow
import app.solstone.platform.persistence.room.SegmentDao
import app.solstone.platform.persistence.room.SegmentFileRow
import app.solstone.platform.persistence.room.SolstonePersistenceDatabase
import app.solstone.platform.persistence.room.SyncStateRow

private const val TAG = "SyncWorker"

fun scheduleOptionalJobsIfPairingCurrent(
    snapshotIdentity: PairedHome,
    mutator: IdentityMutator,
    journalVersionCoordinator: JournalVersionRefreshCoordinator,
    relayAccessCoordinator: RelayAccessRefreshCoordinator,
    pushRegistration: PushRegistrationCoordinator?,
    localDescriptionProvider: () -> ClientReportedDescription,
    openClient: () -> PlHttpClient,
    allowsOwnerMaterial: Boolean,
): Boolean {
    val currentPairing = mutator.currentPairingGeneration()
    val snapshotPairing = PairingGeneration(snapshotIdentity.instanceId, snapshotIdentity.clientCertFingerprint)
    if (currentPairing != snapshotPairing) {
        return false
    }
    journalVersionCoordinator.onUsableConnection(
        instanceId = snapshotIdentity.instanceId,
        caChainFingerprint = snapshotIdentity.caChainFingerprint,
        clientCertFingerprint = snapshotIdentity.clientCertFingerprint,
        localDescriptionProvider = localDescriptionProvider,
        pairingMatches = { mutator.currentPairingGeneration() == snapshotPairing },
        openClient = openClient,
    )
    relayAccessCoordinator.onUsableConnection(
        instanceId = snapshotIdentity.instanceId,
        caChainFingerprint = snapshotIdentity.caChainFingerprint,
        clientCertFingerprint = snapshotIdentity.clientCertFingerprint,
        openClient = openClient,
    )
    if (allowsOwnerMaterial) {
        pushRegistration?.onUsableConnection(
            generation = snapshotPairing,
            openClient = openClient,
            pairingNow = { mutator.currentPairingGeneration() },
        )
    }
    return true
}

@Volatile
internal var workerLog: (level: String, message: String, throwable: Throwable?) -> Unit = { level, message, throwable ->
    try {
        when (level) {
            "i" -> if (throwable != null) Log.i(TAG, message, throwable) else Log.i(TAG, message)
            "w" -> if (throwable != null) Log.w(TAG, message, throwable) else Log.w(TAG, message)
            "e" -> if (throwable != null) Log.e(TAG, message, throwable) else Log.e(TAG, message)
        }
    } catch (_: RuntimeException) {
        // Fallback for JVM unit tests where android.util.Log is not mocked
    }
}

data class SyncRunExecutionResult(
    val outcome: SyncOutcome,
    val heldUnconfirmed: Boolean,
    val gateBusy: Boolean = false,
)

internal fun executeSyncRun(
    stores: SyncStores,
    context: Context? = null,
    db: SolstonePersistenceDatabase? = null,
    spoolDir: File? = null,
    deviceLabel: String = "android",
    drainStore: DrainStore? = null,
    openClient: ((SyncTransport, ClientCredential) -> PlHttpClient)? = null,
    afterCredentialsFrozen: (() -> Unit)? = null,
    localDescriptionProvider: (() -> ClientReportedDescription)? = null,
): SyncRunExecutionResult {
    if (!SyncDrainGate.tryAcquire()) {
        workerLog("i", "identity boundary already in use; deferring", null)
        return SyncRunExecutionResult(SyncOutcome.RETRY, false, gateBusy = true)
    }
    try {
        val (credentials, allowsOwnerMaterial) = stores.publisher.withMutationBoundary {
            val creds = recoverSyncCredentials(stores.publisher)
            val allows = when (creds) {
                is SyncCredentials.Ready -> confirmedFor(
                    JournalConfirmationPolicy.consults,
                    stores.journalConfirmationStore,
                    creds.identity.clientCertFingerprint,
                )
                else -> true
            }
            creds to allows
        }
        afterCredentialsFrozen?.invoke()
        when (credentials) {
            is SyncCredentials.NeedsRepair -> {
                workerLog("w", "sync credentials need repair: ${credentials.reason}", null)
                return SyncRunExecutionResult(SyncOutcome.FAILURE, false)
            }
            is SyncCredentials.Ready -> {
                val outcome = sync(
                    stores = stores,
                    credentials = credentials,
                    allowsOwnerMaterial = allowsOwnerMaterial,
                    context = context,
                    persistenceDb = db,
                    customSpoolDir = spoolDir,
                    deviceLabel = deviceLabel,
                    drainStore = drainStore,
                    customOpenClient = openClient,
                    localDescriptionProvider = localDescriptionProvider,
                )
                val held = outcome == SyncOutcome.SUCCESS && !allowsOwnerMaterial
                return SyncRunExecutionResult(outcome, held)
            }
        }
    } finally {
        SyncDrainGate.release()
    }
}

internal fun completeSyncRun(
    openStores: () -> SyncStores,
    context: Context? = null,
    db: SolstonePersistenceDatabase? = null,
    spoolDir: File? = null,
    deviceLabel: String = "android",
    drainStore: DrainStore? = null,
    openClient: ((SyncTransport, ClientCredential) -> PlHttpClient)? = null,
    afterCredentialsFrozen: (() -> Unit)? = null,
    localDescriptionProvider: (() -> ClientReportedDescription)? = null,
): SyncOutcome {
    var outcome = SyncOutcome.FAILURE
    var heldUnconfirmed = false
    var gateBusy = false
    try {
        val stores = openStores()
        val result = executeSyncRun(
            stores = stores,
            context = context,
            db = db,
            spoolDir = spoolDir,
            deviceLabel = deviceLabel,
            drainStore = drainStore,
            openClient = openClient,
            afterCredentialsFrozen = afterCredentialsFrozen,
            localDescriptionProvider = localDescriptionProvider,
        )
        outcome = result.outcome
        heldUnconfirmed = result.heldUnconfirmed
        gateBusy = result.gateBusy
    } catch (e: Throwable) {
        workerLog("e", "sync threw before its own outcome", e)
        outcome = SyncOutcome.FAILURE
        heldUnconfirmed = false
        gateBusy = false
    } finally {
        val diagLine = if (heldUnconfirmed) {
            "kind=sync outcome=success held=unconfirmed"
        } else {
            "kind=sync outcome=${outcome.name.lowercase()}"
        }
        runCatching { SyncWorker.syncDiag?.invoke(diagLine) }
    }
    return outcome
}

class SyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    private var testOpenStores: (() -> SyncStores)? = null
    private var testOpenClient: ((SyncTransport, ClientCredential) -> PlHttpClient)? = null
    private var testDrainStore: DrainStore? = null
    private var testSpoolDir: File? = null
    private var testAfterCredentialsFrozen: (() -> Unit)? = null
    private var testLocalDescriptionProvider: (() -> ClientReportedDescription)? = null
    private var testDeviceLabel: String? = null

    internal constructor(
        context: Context,
        params: WorkerParameters,
        openStores: (() -> SyncStores)? = null,
        openClient: ((SyncTransport, ClientCredential) -> PlHttpClient)? = null,
        drainStore: DrainStore? = null,
        spoolDir: File? = null,
        afterCredentialsFrozen: (() -> Unit)? = null,
        localDescriptionProvider: (() -> ClientReportedDescription)? = null,
        deviceLabel: String? = null,
    ) : this(context, params) {
        this.testOpenStores = openStores
        this.testOpenClient = openClient
        this.testDrainStore = drainStore
        this.testSpoolDir = spoolDir
        this.testAfterCredentialsFrozen = afterCredentialsFrozen
        this.testLocalDescriptionProvider = localDescriptionProvider
        this.testDeviceLabel = deviceLabel
    }

    override suspend fun doWork(): Result =
        withContext(Dispatchers.IO) {
            val outcome = completeSyncRun(
                openStores = testOpenStores ?: { syncStores(applicationContext) },
                context = if (testOpenStores != null) null else applicationContext,
                spoolDir = testSpoolDir,
                deviceLabel = testDeviceLabel ?: deviceLabel(),
                drainStore = testDrainStore,
                openClient = testOpenClient,
                afterCredentialsFrozen = testAfterCredentialsFrozen,
                localDescriptionProvider = testLocalDescriptionProvider,
            )
            outcome.toWorkResult()
        }

    private fun SyncOutcome.toWorkResult(): Result =
        when (this) {
            SyncOutcome.SUCCESS -> Result.success()
            SyncOutcome.RETRY -> Result.retry()
            SyncOutcome.FAILURE -> Result.failure()
        }

    private fun deviceLabel(): String =
        listOf(Build.MANUFACTURER, Build.MODEL)
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .ifBlank { "android" }

    companion object {
        /**
         * Where a sync outcome goes so the owner can see one.
         *
         * The app process installs this; this module owns no diagnostics sink of its own, and a
         * worker with no app process behind it simply writes nowhere. Same shape as the
         * foreground service's lifecycle hook, for the same reason.
         */
        @Volatile
        @JvmStatic
        var syncDiag: ((String) -> Unit)? = null
    }
}

private interface CloseablePlHttpClient : PlHttpClient, java.io.Closeable

private class CloseablePlHttpClientAdapter(private val delegate: PlHttpClient) : CloseablePlHttpClient, PlHttpClient by delegate {
    override fun close() {
        if (delegate is java.io.Closeable) {
            delegate.close()
        }
    }
}

private fun toCloseableClient(client: PlHttpClient): CloseablePlHttpClient =
    if (client is CloseablePlHttpClient) client else CloseablePlHttpClientAdapter(client)

private fun sync(
    stores: SyncStores,
    credentials: SyncCredentials.Ready,
    allowsOwnerMaterial: Boolean,
    context: Context? = null,
    persistenceDb: SolstonePersistenceDatabase? = null,
    customSpoolDir: File? = null,
    deviceLabel: String = "android",
    drainStore: DrainStore? = null,
    customOpenClient: ((SyncTransport, ClientCredential) -> PlHttpClient)? = null,
    localDescriptionProvider: (() -> ClientReportedDescription)? = null,
): SyncOutcome {
    val spoolDir = customSpoolDir ?: (context?.let { File(it.filesDir, "spool") } ?: File("spool"))
    var shouldCloseDb = false
    var dbToClose: SolstonePersistenceDatabase? = null
    val (store, finisher) = if (drainStore != null) {
        val f = ConfirmedCopyFinisher(
            spoolRoot = spoolDir.toPath(),
            dao = object : SegmentDao() {
                override fun insertSegment(segment: SegmentRow) = Unit
                override fun insertFiles(files: List<SegmentFileRow>) = Unit
                override fun insertEvents(events: List<EventRow>) = Unit
                override fun segmentsByState(state: QueueState): List<SegmentRow> = emptyList()
                override fun segmentsForDrain(stream: String): List<SegmentRow> = drainStore.segmentsForDrain()
                override fun segmentsByDay(day: String): List<SegmentRow> = emptyList()
                override fun segmentById(id: String): SegmentRow? = drainStore.segmentRow(id)
                override fun duplicateBySha256(sha256: String): List<SegmentFileRow> = emptyList()
                override fun filesBySegmentId(segmentId: String): List<SegmentFileRow> = drainStore.filesBySegmentId(segmentId)
                override fun recordAttempt(id: String, attempts: Int, at: Long): Int = drainStore.recordAttempt(id, attempts, at)
                override fun recordUploaded(id: String): Int = drainStore.recordUploaded(id)
                override fun recordFailure(id: String, code: Int?, error: String?): Int = drainStore.recordFailure(id, code, error)
                override fun upsertSyncState(row: SyncStateRow) = drainStore.upsertSyncState(row)
                override fun syncState(): SyncStateRow? = drainStore.syncState()
                override fun pendingCount(stream: String): Int = drainStore.pendingCount(stream)
                override fun pendingSourceIds(stream: String): List<String> = emptyList()
                override fun segmentState(id: String): QueueState? = drainStore.segmentRow(id)?.state
                override fun updateState(id: String, state: QueueState): Int {
                    val event = when (state) {
                        QueueState.EVICTED -> app.solstone.core.queue.QueueEvent.FINISH
                        QueueState.UPLOADING -> app.solstone.core.queue.QueueEvent.START_UPLOAD
                        QueueState.UPLOADED -> app.solstone.core.queue.QueueEvent.MARK_UPLOADED
                        QueueState.FAILED -> app.solstone.core.queue.QueueEvent.MARK_FAILED
                        QueueState.SEALED -> app.solstone.core.queue.QueueEvent.SEAL
                        QueueState.RECORDING -> error("illegal")
                    }
                    drainStore.advanceState(id, event)
                    return 1
                }
                override fun deleteFilesBySegmentId(segmentId: String): Int = 0
                override fun deleteFilesBySegmentIds(segmentIds: List<String>): Int = 0
                override fun deleteFilesBySource(sourceId: String): Int = 0
            },
            log = { message -> workerLog("w", message, null) },
        )
        drainStore to f
    } else {
        val db = persistenceDb ?: (context?.let { openSolstonePersistenceDatabase(it) } ?: throw IllegalStateException("Database or Context required for sync"))
        if (persistenceDb == null) {
            shouldCloseDb = true
            dbToClose = db
        }
        val f = ConfirmedCopyFinisher(
            spoolRoot = spoolDir.toPath(),
            dao = db.segmentDao(),
            log = { message -> workerLog("w", message, null) },
        )
        RoomDrainStore(db.segmentDao()) to f
    }
    val poster = defaultHttpsPoster()
    try {
        val syncTransport: (SyncTransport) -> SyncOutcome = transportAttempt@{ selectedTransport ->
            val access = stores.identityMutator.accessSnapshot() ?: return@transportAttempt SyncOutcome.RETRY
            val pairingMatches = access.pairing == PairingGeneration(credentials.identity.instanceId, credentials.identity.clientCertFingerprint)
            val relayMatches = if (selectedTransport is SyncTransport.Relay) {
                relaySnapshot(credentials.identity, selectedTransport, stores.identityMutator) == access
            } else {
                true
            }
            val accessStillCurrent = transportAccessStillCurrent(selectedTransport, credentials.identity, access, stores.identityMutator)
            if (!pairingMatches || !relayMatches || !accessStillCurrent) {
                return@transportAttempt if (allowsOwnerMaterial) SyncOutcome.RETRY else SyncOutcome.SUCCESS
            }
            syncWithTransport(
                transport = selectedTransport,
                openClient = {
                    if (customOpenClient != null) {
                        toCloseableClient(customOpenClient(selectedTransport, credentials.credential))
                    } else {
                        toCloseableClient(
                            recordDial(DialDiagnostics.events, selectedTransport) {
                                openSyncClient(selectedTransport, credentials.credential)
                            }
                        )
                    }
                },
                store = store,
                finisher = finisher,
                readPayload = { segment, file -> readPayloadFor(spoolDir, segment, file) },
                spoolDir = spoolDir,
                host = deviceLabel,
                now = System::currentTimeMillis,
                log = { message, throwable -> workerLog("w", message, throwable) },
                onUsableConnection = {
                    val currentTransport = currentOptionalTransport(selectedTransport, credentials.identity, stores.identityMutator)
                    if (currentTransport == null) {
                        if (allowsOwnerMaterial) {
                            throw IOException("missing identity")
                        }
                        return@syncWithTransport
                    }
                    scheduleOptionalJobsIfPairingCurrent(
                        snapshotIdentity = credentials.identity,
                        mutator = stores.identityMutator,
                        journalVersionCoordinator = stores.journalVersionCoordinator,
                        relayAccessCoordinator = stores.relayAccessCoordinator,
                        pushRegistration = stores.pushRegistration,
                        localDescriptionProvider = localDescriptionProvider
                            ?: { context?.let { currentPhoneDeviceDescription(it) } ?: throw IllegalStateException("localDescriptionProvider or Context required for sync") },
                        openClient = {
                            if (customOpenClient != null) {
                                customOpenClient(currentTransport, credentials.credential)
                            } else {
                                openSyncClient(currentTransport, credentials.credential)
                            }
                        },
                        allowsOwnerMaterial = allowsOwnerMaterial,
                    )
                },
                allowsOwnerMaterial = allowsOwnerMaterial,
                pairingCurrent = {
                    stores.identityMutator.currentPairingGeneration() ==
                        PairingGeneration(credentials.identity.instanceId, credentials.identity.clientCertFingerprint)
                },
            )
        }

        var outcome = when (val transport = credentials.transport) {
            is SyncTransport.Direct -> syncTransport(transport)
            is SyncTransport.Relay -> {
                val maintained = maintainRelayToken(
                    identity = credentials.identity,
                    transport = transport,
                    poster = poster,
                    mutator = stores.identityMutator,
                )
                when (maintained) {
                    is RelayTokenResult.Ready -> dialWithReactiveRefresh(
                        identity = credentials.identity,
                        transport = maintained.transport,
                        poster = poster,
                        mutator = stores.identityMutator,
                        dial = RelayDial { relayTransport -> syncTransport(relayTransport) },
                        log = { message, throwable -> workerLog("w", message, throwable) },
                    )
                    RelayTokenResult.ReconnectNeeded -> return SyncOutcome.FAILURE
                    RelayTokenResult.Obsolete -> return SyncOutcome.RETRY
                }
            }
        }

        if (outcome == SyncOutcome.RETRY && credentials.transport is SyncTransport.Direct) {
            val relayTransport = relayFallbackTransport(
                identity = credentials.identity,
                relayLiveEligible = stores.identityMutator.accessSnapshot()?.let { it.pairing == PairingGeneration(credentials.identity.instanceId, credentials.identity.clientCertFingerprint) && it.relayLiveEligible } ?: false,
            )
            if (relayTransport != null) {
                val maintained = maintainRelayToken(
                    identity = credentials.identity,
                    transport = relayTransport,
                    poster = poster,
                    mutator = stores.identityMutator,
                )
                if (maintained is RelayTokenResult.Ready) {
                    outcome = dialWithReactiveRefresh(
                        identity = credentials.identity,
                        transport = maintained.transport,
                        poster = poster,
                        mutator = stores.identityMutator,
                        dial = RelayDial { t -> syncTransport(t) },
                        log = { message, throwable -> workerLog("w", message, throwable) },
                    )
                }
            }
        }

        return outcome
    } catch (e: RelayDialWaitingException) {
        workerLog("i", "home offline, waiting; will retry", e)
        return SyncOutcome.RETRY
    } catch (e: RelayWebSocketClosedException) {
        workerLog("w", "relay ws closed; retry", e)
        return SyncOutcome.RETRY
    } catch (e: IOException) {
        workerLog("w", "sync io; retry", e)
        return SyncOutcome.RETRY
    } catch (e: Exception) {
        workerLog("e", "sync failed", e)
        return SyncOutcome.FAILURE
    } finally {
        if (shouldCloseDb) {
            dbToClose?.close()
        }
    }
}

private fun openSyncClient(transport: SyncTransport, credential: ClientCredential) =
    when (transport) {
        is SyncTransport.Direct -> openAuthenticatedClient(transport.endpoint, credential)
        is SyncTransport.Relay -> openRelaySyncClient(
            transport.relayOrigin,
            transport.instanceId,
            transport.deviceToken,
            credential,
        )
    }


// Resamples device descriptions from platform facts at trigger time.
// Note: Android provides no system hostname/device-name broadcast listener; no polling is used.
fun currentPhoneDeviceDescription(context: Context): ClientReportedDescription {
    val manufacturer = Build.MANUFACTURER.orEmpty().trim()
    val model = Build.MODEL.orEmpty().trim()
    val name = when {
        model.isEmpty() -> manufacturer
        manufacturer.isEmpty() -> model
        model.startsWith(manufacturer, ignoreCase = true) -> model
        else -> "$manufacturer $model"
    }.trim().ifBlank { null }

    val appVersion = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }.getOrNull()

    return ClientReportedDescription(
        name = name,
        platform = "android",
        deviceType = "phone",
        appId = context.packageName,
        appVersion = appVersion,
    )
}

internal fun readPayloadFor(spoolDir: File, segment: SegmentRow, file: BundleFile): ByteArray {
    val segmentDir = File(File(File(spoolDir, segment.day), segment.stream), segment.dirSegment)
    val payload = File(segmentDir, file.name)
    require(payload.canonicalFile.parentFile == segmentDir.canonicalFile) {
        "payload name must not contain path separators: ${file.name}"
    }
    return payload.readBytes()
}

internal data class StoredSegmentZone(val zoneId: String, val utcOffsetSeconds: Int)

internal fun readStoredZoneFor(spoolDir: File, segment: SegmentRow): StoredSegmentZone? {
    val segmentDir = File(File(File(spoolDir, segment.day), segment.stream), segment.dirSegment)
    val manifest = File(segmentDir, "manifest")
    return try {
        val parsed = parseManifest(manifest.readText())
        StoredSegmentZone(parsed.zoneId, parsed.utcOffsetSeconds)
    } catch (_: Exception) {
        null
    }
}

