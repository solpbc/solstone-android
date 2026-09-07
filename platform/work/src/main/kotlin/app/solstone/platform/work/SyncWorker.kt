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

private const val TAG = "SyncWorker"

class SyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result =
        withContext(Dispatchers.IO) {
            val stores = syncStores(applicationContext)
            when (
                val credentials = recoverSyncCredentials(
                    endpointStore = stores.endpointStore,
                    credentialStore = stores.credentialStore,
                    identityStore = stores.identityStore,
                    relayLiveEligible = stores.identityMutator.isRelayLiveEligible(),
                )
            ) {
                is SyncCredentials.NeedsRepair -> {
                    Log.w(TAG, "sync credentials need repair: ${credentials.reason}")
                    Result.failure()
                }
                is SyncCredentials.Ready -> {
                    if (!SyncDrainGate.tryAcquire()) {
                        Log.i(TAG, "drain already running; deferring")
                        Result.retry()
                    } else {
                        try {
                            sync(stores, credentials)
                        } finally {
                            SyncDrainGate.release()
                        }
                    }
                }
            }
        }

    private fun sync(stores: SyncStores, credentials: SyncCredentials.Ready): Result {
        val db = openSolstonePersistenceDatabase(applicationContext)
        val poster = defaultHttpsPoster()
        try {
            val store = RoomDrainStore(db.segmentDao())
            val spoolDir = File(applicationContext.filesDir, "spool")
            val syncTransport: (SyncTransport) -> SyncOutcome = { selectedTransport ->
                syncWithTransport(
                    transport = selectedTransport,
                    openClient = { openSyncClient(it, credentials.credential) },
                    store = store,
                    readPayload = { segment, file -> readPayloadFor(spoolDir, segment, file) },
                    host = deviceLabel(),
                    now = System::currentTimeMillis,
                    log = { message, throwable -> Log.w(TAG, message, throwable) },
                    onUsableConnection = {
                        val currentHome = stores.identityMutator.current() ?: credentials.identity
                        stores.journalVersionCoordinator.onUsableConnection(
                            instanceId = currentHome.instanceId,
                            caChainFingerprint = currentHome.caChainFingerprint,
                            clientCertFingerprint = currentHome.clientCertFingerprint,
                            localDescriptionProvider = { currentPhoneDeviceDescription(applicationContext) },
                        ) {
                            openSyncClient(selectedTransport, credentials.credential)
                        }
                        stores.relayAccessCoordinator.onUsableConnection(
                            instanceId = currentHome.instanceId,
                            caChainFingerprint = currentHome.caChainFingerprint,
                            clientCertFingerprint = currentHome.clientCertFingerprint,
                        ) {
                            openSyncClient(selectedTransport, credentials.credential)
                        }
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
                        nowEpochMs = System.currentTimeMillis(),
                    )
                    when (maintained) {
                        is RelayTokenResult.Ready -> dialWithReactiveRefresh(
                            identity = credentials.identity,
                            transport = maintained.transport,
                            poster = poster,
                            mutator = stores.identityMutator,
                            dial = RelayDial { relayTransport -> syncTransport(relayTransport) },
                            log = { message, throwable -> Log.w(TAG, message, throwable) },
                        )
                        RelayTokenResult.ReconnectNeeded -> return Result.failure()
                    }
                }
            }

            // If direct probe failed and relay is live-eligible, retry once via Relay
            if (outcome != SyncOutcome.SUCCESS && credentials.transport is SyncTransport.Direct && stores.identityMutator.isRelayLiveEligible()) {
                val currentHome = stores.identityMutator.current()
                val relayOrigin = currentHome?.relayOrigin
                val deviceToken = currentHome?.deviceToken
                if (relayOrigin != null && deviceToken != null) {
                    val relayTransport = SyncTransport.Relay(relayOrigin, currentHome.instanceId, deviceToken)
                    val maintained = maintainRelayToken(
                        identity = currentHome,
                        transport = relayTransport,
                        poster = poster,
                        mutator = stores.identityMutator,
                        nowEpochMs = System.currentTimeMillis(),
                    )
                    if (maintained is RelayTokenResult.Ready) {
                        outcome = dialWithReactiveRefresh(
                            identity = currentHome,
                            transport = maintained.transport,
                            poster = poster,
                            mutator = stores.identityMutator,
                            dial = RelayDial { t -> syncTransport(t) },
                            log = { message, throwable -> Log.w(TAG, message, throwable) },
                        )
                    }
                }
            }

            return outcome.toWorkResult()
        } catch (e: RelayDialWaitingException) {
            Log.i(TAG, "home offline, waiting; will retry", e)
            return Result.retry()
        } catch (e: RelayWebSocketClosedException) {
            Log.w(TAG, "relay ws closed; retry", e)
            return Result.retry()
        } catch (e: IOException) {
            Log.w(TAG, "sync io; retry", e)
            return Result.retry()
        } catch (e: Exception) {
            Log.e(TAG, "sync failed", e)
            return Result.failure()
        } finally {
            db.close()
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
