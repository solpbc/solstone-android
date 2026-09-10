// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.harness

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import app.solstone.core.identity.ClientCredential
import app.solstone.core.identity.ClientCredentialStore
import app.solstone.core.identity.IdentityMutator
import app.solstone.core.identity.IdentityStore
import app.solstone.core.identity.JournalVersionStore
import app.solstone.core.identity.PairingGeneration
import app.solstone.core.model.IdentityState
import app.solstone.core.model.PairedHome
import app.solstone.core.model.QueueState
import app.solstone.core.pl.ClientReportedDescription
import app.solstone.core.pl.DirectDialObserver
import app.solstone.core.pl.EndpointStore
import app.solstone.core.pl.JournalVersionRefreshCoordinator
import app.solstone.core.pl.PlHttpClient
import app.solstone.core.pl.PlStreamObserver
import app.solstone.core.pl.RelayAccessRefreshCoordinator
import app.solstone.core.pl.RelayDialObserver
import app.solstone.core.pl.RelayPairLink
import app.solstone.core.sources.MAIN_STREAM
import app.solstone.platform.fgs.ObserverForegroundService
import app.solstone.platform.persistence.room.SegmentDao
import app.solstone.platform.pl.transport.conscrypt.openAuthenticatedClient
import app.solstone.platform.pl.transport.conscrypt.DirectPairConnectionMode
import app.solstone.platform.pl.transport.conscrypt.RelayPairConnectionMode
import app.solstone.platform.pl.transport.conscrypt.openRelaySyncClient
import app.solstone.platform.pl.transport.conscrypt.defaultHttpsPoster
import app.solstone.platform.pl.transport.conscrypt.defaultRelayPairDialer
import app.solstone.platform.pl.transport.conscrypt.pairAndProbe as conscryptPairAndProbe
import app.solstone.platform.pl.transport.conscrypt.pairOverRelay as conscryptPairOverRelay
import app.solstone.platform.work.OpenerFailureKind
import app.solstone.platform.work.SyncTransport
import app.solstone.platform.work.classifyOpenerFailure
import app.solstone.platform.work.selectSyncTransport
import app.solstone.platform.work.SyncScheduler
import app.solstone.platform.work.transportAccessStillCurrent
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.exists
import kotlin.io.path.isDirectory

class RealHeartbeatFreshness : HeartbeatFreshness {
    override fun isFresh(): Boolean = ObserverForegroundService.isHeartbeatFresh()

    override fun hasStartEvidence(): Boolean = ObserverForegroundService.isStartObserved()
}

class RealPairProbe(
    private val credentialStore: ClientCredentialStore,
    private val identityStore: IdentityStore,
    private val endpointStore: EndpointStore,
    private val journalVersionStore: JournalVersionStore? = null,
    private val coordinator: JournalVersionRefreshCoordinator? = null,
    private val mutator: IdentityMutator? = null,
    private val relayAccessCoordinator: RelayAccessRefreshCoordinator? = null,
) : PairProbe {
    override fun pairAndProbe(pairLink: String, deviceLabel: String): HarnessPairProbeResult {
        val result = conscryptPairAndProbe(
            pairLink = pairLink,
            deviceLabel = deviceLabel,
            credentialStore = credentialStore,
            identityStore = identityStore,
            endpointStore = endpointStore,
            journalVersionStore = journalVersionStore,
            coordinator = coordinator,
            mutator = mutator,
            relayAccessCoordinator = relayAccessCoordinator,
        )
        return HarnessPairProbeResult(
            handshakePinned = result.handshakePinned,
            pairStatus = result.pairStatus,
            statusStatus = result.statusStatus,
            statusBody = result.statusBody,
            homeLabel = identityStore.load()?.homeLabel,
            endpointHost = result.endpoint.host,
            endpointPort = result.endpoint.port,
            connectionMode = when (result.connectionMode) {
                DirectPairConnectionMode.PAIRING -> PairConnectionMode.PAIRING
                DirectPairConnectionMode.ALREADY_CONNECTED -> PairConnectionMode.ALREADY_CONNECTED
                DirectPairConnectionMode.RECONNECTING -> PairConnectionMode.RECONNECTING
            },
        )
    }
}

class RealRelayPairProbe(
    private val credentialStore: ClientCredentialStore,
    private val identityStore: IdentityStore,
    private val streamObserver: PlStreamObserver? = null,
    private val dialObserver: RelayDialObserver? = null,
    private val journalVersionStore: JournalVersionStore? = null,
    private val coordinator: JournalVersionRefreshCoordinator? = null,
    private val mutator: IdentityMutator? = null,
    private val relayAccessCoordinator: RelayAccessRefreshCoordinator? = null,
    private val endpointStore: EndpointStore? = null,
) : RelayPairProbe {
    override fun pairOverRelay(link: RelayPairLink, deviceLabel: String): HarnessPairProbeResult {
        val result = conscryptPairOverRelay(
            link = link,
            deviceLabel = deviceLabel,
            httpsPoster = defaultHttpsPoster(),
            relayPairDialer = defaultRelayPairDialer(streamObserver, dialObserver),
            credentialStore = credentialStore,
            identityStore = identityStore,
            journalVersionStore = journalVersionStore,
            coordinator = coordinator,
            mutator = mutator,
            relayAccessCoordinator = relayAccessCoordinator,
            endpointStore = endpointStore,
        )
        return HarnessPairProbeResult(
            handshakePinned = result.handshakePinned,
            pairStatus = result.pairStatus,
            statusStatus = result.enrollStatus ?: result.pairStatus,
            statusBody = "",
            homeLabel = result.homeLabel,
            endpointHost = result.relayHost,
            endpointPort = app.solstone.core.pl.parseProductionRelayOrigin(result.relayOrigin)?.effectivePort ?: 443,
            connectionMode = when (result.connectionMode) {
                RelayPairConnectionMode.PAIRING -> PairConnectionMode.PAIRING
                RelayPairConnectionMode.ALREADY_CONNECTED -> PairConnectionMode.ALREADY_CONNECTED
                RelayPairConnectionMode.RECONNECTING -> PairConnectionMode.RECONNECTING
            },
        )
    }
}

class RealPlStatusProbe(
    private val endpointStore: EndpointStore,
    private val credentialStore: ClientCredentialStore,
    private val identityStore: IdentityStore,
    private val streamObserver: PlStreamObserver? = null,
    private val dialObserver: RelayDialObserver? = null,
    private val directDialObserver: DirectDialObserver? = null,
    private val coordinator: JournalVersionRefreshCoordinator? = null,
    private val relayAccessCoordinator: RelayAccessRefreshCoordinator? = null,
    private val mutator: IdentityMutator? = null,
    private val localDescriptionProvider: (() -> ClientReportedDescription)? = null,
    private val openTransport: ((SyncTransport, ClientCredential) -> PlHttpClient)? = null,
) : PlStatusProbe {
    private var wasReachable: Boolean? = null

    private fun openClientFor(t: SyncTransport, credential: ClientCredential): PlHttpClient =
        openTransport?.invoke(t, credential) ?: when (t) {
            is SyncTransport.Direct -> openAuthenticatedClient(
                t.endpoint,
                credential,
                streamObserver,
                directDialObserver,
            )
            is SyncTransport.Relay -> openRelaySyncClient(
                t.relayOrigin,
                t.instanceId,
                t.deviceToken,
                credential,
                streamObserver,
                dialObserver,
            )
        }

    override fun probe(): HarnessPlStatus {
        val initial = if (mutator != null) {
            mutator.withMutationBoundary {
                val access = mutator.accessSnapshot()
                Triple(credentialStore.load(), access?.home, access)
            }
        } else {
            Triple(credentialStore.load(), identityStore.load(), null)
        }
        val (credential, identity, access) = initial
        if (credential == null && identity == null && endpointStore.load() == null) {
            handleReachabilityTransition(false, null, null)
            return HarnessPlStatus.NotPaired
        }
        if (credential == null) {
            handleReachabilityTransition(false, null, null)
            return HarnessPlStatus.PairedButUnreachable("missing credential")
        }
        if (identity == null) {
            handleReachabilityTransition(false, null, null)
            return HarnessPlStatus.PairedButUnreachable("missing identity")
        }
        if (identity.state != IdentityState.PAIRED) {
            handleReachabilityTransition(false, null, null)
            return HarnessPlStatus.PairedButUnreachable("identity not paired")
        }
        val relayLiveEligible = access?.relayLiveEligible ?: false
        val transport = selectSyncTransport(identity, endpointStore, relayLiveEligible)
        if (transport == null) {
            handleReachabilityTransition(false, null, null)
            return HarnessPlStatus.PairedButUnreachable(if (identity.relayOrigin != null) "missing device token" else "missing endpoint")
        }

        fun tryTransport(t: SyncTransport): Pair<Int?, Throwable?> {
            return try {
                if (mutator != null && !transportAccessStillCurrent(t, identity, access, mutator)) {
                    throw java.io.IOException("missing identity")
                }
                val client = openClientFor(t, credential)
                try {
                    val status = client.request("GET", "/app/network/api/status", emptyMap(), ByteArray(0)).status
                    status to null
                } finally {
                    (client as? java.io.Closeable)?.close()
                }
            } catch (e: Throwable) {
                null to e
            }
        }

        var (status, failure) = tryTransport(transport)
        var usedTransport = transport

        // If direct probe encounters availability error and relay is live-eligible, retry once with relay
        if (status == null && failure != null && transport is SyncTransport.Direct && relayLiveEligible) {
            val failureKind = classifyOpenerFailure(failure)
            if (failureKind == OpenerFailureKind.AVAILABILITY) {
                val relayOrigin = identity.relayOrigin
                val deviceToken = identity.deviceToken
                if (relayOrigin != null && deviceToken != null) {
                    val relayTransport = SyncTransport.Relay(relayOrigin, identity.instanceId, deviceToken)
                    val (relayStatus, relayFailure) = tryTransport(relayTransport)
                    if (relayStatus != null) {
                        status = relayStatus
                        failure = null
                        usedTransport = relayTransport
                    } else {
                        failure = relayFailure
                    }
                }
            }
        }

        if (mutator != null && !transportAccessStillCurrent(usedTransport, identity, access, mutator)) {
            handleReachabilityTransition(false, null, null)
            return HarnessPlStatus.PairedButUnreachable("missing identity")
        }
        if (status != null) {
            val reachable = status == 200
            handleReachabilityTransition(reachable, identity, credential) {
                val currentTransport = if (mutator != null) {
                    app.solstone.platform.work.currentOptionalTransport(usedTransport, identity, mutator)
                        ?: throw java.io.IOException("missing identity")
                } else usedTransport
                openClientFor(currentTransport, credential)
            }
            return HarnessPlStatus.Reachable(status)
        } else {
            handleReachabilityTransition(false, null, null)
            val err = failure ?: IllegalStateException("probe failed")
            return HarnessPlStatus.PairedButUnreachable(plFailureDetail(err))
        }
    }

    private fun handleReachabilityTransition(
        reachable: Boolean,
        identity: PairedHome?,
        credential: ClientCredential?,
        openClient: (() -> PlHttpClient)? = null,
    ) {
        val previous = wasReachable
        wasReachable = reachable
        if (previous != true && reachable && identity != null && openClient != null) {
            val snapshotPairing = PairingGeneration(identity.instanceId, identity.clientCertFingerprint)
            coordinator?.onUsableConnection(
                instanceId = identity.instanceId,
                caChainFingerprint = identity.caChainFingerprint,
                clientCertFingerprint = identity.clientCertFingerprint,
                localDescriptionProvider = localDescriptionProvider,
                pairingMatches = { mutator?.currentPairingGeneration() == snapshotPairing },
                openClient = openClient,
            )
            relayAccessCoordinator?.onUsableConnection(
                instanceId = identity.instanceId,
                caChainFingerprint = identity.caChainFingerprint,
                clientCertFingerprint = identity.clientCertFingerprint,
                openClient = openClient,
            )
        } else if (previous == true && !reachable) {
            coordinator?.onConnectionLost()
            relayAccessCoordinator?.onConnectionLost()
        }
    }
}

internal fun plFailureDetail(exception: Throwable): String {
    val type = exception.javaClass.simpleName
    return exception.message?.takeIf(String::isNotBlank)?.let { "$type: $it" } ?: type
}

class RealSyncEnqueue(private val context: Context, private val streamType: String) : SyncEnqueue {
    override fun enqueuePeriodic() {
        SyncScheduler.enqueuePeriodic(context, streamType)
    }

    override fun enqueueNow() {
        SyncScheduler.enqueueNow(context, streamType)
    }
}

@SuppressLint("MissingPermission")
class AndroidNetworkAvailability(context: Context) : NetworkAvailability {
    private val connectivityManager = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private var callback: ConnectivityManager.NetworkCallback? = null

    override fun start(onUsableNetwork: () -> Unit) {
        val manager = connectivityManager ?: error("connectivity manager unavailable")
        val networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                signalIfUsable(manager.getNetworkCapabilities(network), onUsableNetwork)
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                signalIfUsable(networkCapabilities, onUsableNetwork)
            }
        }
        manager.registerNetworkCallback(
            NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build(),
            networkCallback,
        )
        callback = networkCallback
    }

    override fun stop() {
        val manager = connectivityManager ?: return
        val registered = callback ?: return
        callback = null
        runCatching { manager.unregisterNetworkCallback(registered) }
    }

    override fun isUsableNow(): Boolean {
        val manager = connectivityManager ?: return false
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun signalIfUsable(capabilities: NetworkCapabilities?, onUsableNetwork: () -> Unit) {
        if (capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true) {
            onUsableNetwork()
        }
    }
}

class RealEvidenceReader(private val dao: SegmentDao) : EvidenceReader {
    override fun listEvidence(): List<HarnessEvidenceSegment> =
        dao.segmentsByState(QueueState.SEALED).map { row ->
            HarnessEvidenceSegment(
                id = row.id,
                day = row.day,
                stream = row.stream,
                segment = row.segment,
                dirSegment = row.dirSegment,
                state = row.state,
                byteSize = row.byteSize,
                sealedAt = row.sealedAt,
                files = dao.filesBySegmentId(row.id).map { file ->
                    HarnessEvidenceFile(
                        sourceId = file.sourceId,
                        name = file.name,
                        mediaType = file.mediaType,
                        sha256 = file.sha256,
                        byteSize = file.byteSize,
                    )
                },
            )
        }

    override fun pendingCount(): Int = dao.pendingCount(MAIN_STREAM)

    override fun syncState(): HarnessSyncState {
        val row = dao.syncState()
        return HarnessSyncState(
            pendingCount = row?.pendingCount ?: pendingCount(),
            lastSuccessAt = row?.lastSuccessAt,
            lastFailureAt = row?.lastFailureAt,
        )
    }
}

class RealBacklogStatusReader(
    private val dao: SegmentDao,
    private val plStatus: () -> HarnessPlStatus,
    private val identityStore: IdentityStore? = null,
    private val coordinator: JournalVersionRefreshCoordinator? = null,
) : BacklogStatusReader {
    override fun read(): HarnessBacklogStatus {
        val status = plStatus()
        val identity = identityStore?.load()
        val journalVersion = if (identity != null && coordinator != null) {
            coordinator.currentReading(identity.instanceId, identity.caChainFingerprint)
        } else {
            null
        }
        return HarnessBacklogStatus(
            plStatus = status,
            pendingCount = dao.pendingCount(MAIN_STREAM),
            pendingSourceIds = dao.pendingSourceIds(MAIN_STREAM),
            journalVersion = journalVersion,
        )
    }
}

interface BundleFileOp {
    fun copyDirectory(source: Path, destination: Path): Int
}

class NioBundleFileOp : BundleFileOp {
    override fun copyDirectory(source: Path, destination: Path): Int {
        var count = 0
        if (destination.exists()) {
            destination.deleteRecursively()
        }
        Files.createDirectories(destination)
        Files.walk(source).use { paths ->
            paths.forEach { path ->
                val target = destination.resolve(source.relativize(path).toString())
                if (path.isDirectory()) {
                    Files.createDirectories(target)
                } else {
                    Files.createDirectories(target.parent)
                    Files.copy(path, target, StandardCopyOption.REPLACE_EXISTING)
                    count += 1
                }
            }
        }
        return count
    }
}

class RealBundleExport(
    private val spoolBaseDir: Path,
    private val externalFilesDir: Path,
    private val fileOp: BundleFileOp = NioBundleFileOp(),
) : BundleExport {
    override fun export(segment: HarnessEvidenceSegment): HarnessExportResult {
        val source = spoolBaseDir.resolve(segment.day).resolve(segment.stream).resolve(segment.dirSegment).normalize()
        require(source.startsWith(spoolBaseDir.normalize())) { "segment path escaped spool base" }
        val destination = externalFilesDir
            .resolve("exports")
            .resolve(segment.day)
            .resolve(segment.stream)
            .resolve(segment.dirSegment)
            .normalize()
        require(destination.startsWith(externalFilesDir.normalize())) { "export path escaped external files dir" }
        val count = fileOp.copyDirectory(source, destination)
        return HarnessExportResult(
            sourcePath = source.toString(),
            destinationPath = destination.toString(),
            copiedFileCount = count,
        )
    }
}

private fun Path.deleteRecursively() {
    Files.walk(this).use { paths ->
        paths.sorted(Comparator.reverseOrder()).forEach(Files::delete)
    }
}
