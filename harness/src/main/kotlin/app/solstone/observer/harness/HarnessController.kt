// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.harness

import app.solstone.core.diagnostics.PairingFact
import app.solstone.core.diagnostics.pairingFactOf
import app.solstone.core.identity.ClientCredentialStore
import app.solstone.core.identity.IdentityStore
import app.solstone.core.model.IdentityState
import app.solstone.core.pl.EndpointStore
import app.solstone.core.pl.DirectPairLink
import app.solstone.core.pl.RelayPairLink
import app.solstone.core.pl.looksLikePairLink
import app.solstone.core.pl.parsePairLink
import app.solstone.platform.camera.still.CameraLock
import app.solstone.platform.fgs.ForegroundStartAllowed
import app.solstone.platform.fgs.PermissionStatus
import app.solstone.platform.fgs.PermissionStatusReader
import java.net.URL

enum class ObserverStartMode { VisibleStart, Rehydrate }

enum class ObserverStartBlocker {
    NotDesired,
    PermissionsMissing,
    Unpaired,
    TransportUnavailable,
    ForegroundStartNotAllowed,
}

data class ObserverStartReadiness(
    val allowed: Boolean,
    val blockers: Set<ObserverStartBlocker>,
)

class HarnessController(
    private val permissionStatusReader: PermissionStatusReader,
    private val desiredObservingStore: DesiredObservingStore,
    private val foregroundStartAllowed: ForegroundStartAllowed,
    private val cameraLock: CameraLock,
    private val observerLifecycle: ObserverLifecycle,
    private val heartbeatFreshness: HeartbeatFreshness,
    private val pairProbe: PairProbe,
    private val relayPairProbe: RelayPairProbe,
    private val plStatusProbe: PlStatusProbe,
    private val syncEnqueue: SyncEnqueue,
    private val evidenceReader: EvidenceReader,
    private val bundleExport: BundleExport,
    private val endpointStore: EndpointStore,
    private val credentialStore: ClientCredentialStore,
    private val identityStore: IdentityStore,
    private val sourceSnapshot: () -> SourceRuntimeSnapshot,
    private val deviceLabel: String,
    private val opportunisticSync: OpportunisticSync? = null,
) {
    var desiredOn: Boolean
        get() = desiredObservingStore.isDesiredOn()
        private set(value) {
            desiredObservingStore.setDesiredOn(value)
        }

    var permissionStatus: PermissionStatus = permissionStatusReader.read()
        private set

    var lastPairProbe: HarnessPairProbeResult? = null
        private set

    var lastPlStatus: HarnessPlStatus = HarnessPlStatus.NotPaired
        private set

    var lastStartRefused: Boolean = false
        private set

    private var scanSessionHeld = false

    fun refreshPermissions(): PermissionStatus {
        permissionStatus = permissionStatusReader.read()
        return permissionStatus
    }

    fun onPermissionsRequested(): PermissionStatus = refreshPermissions()

    fun startReadiness(mode: ObserverStartMode): ObserverStartReadiness {
        refreshPermissions()
        val blockers = linkedSetOf<ObserverStartBlocker>()
        if (!permissionStatus.allRequiredGranted) {
            blockers += ObserverStartBlocker.PermissionsMissing
        }
        if (mode == ObserverStartMode.Rehydrate) {
            if (!desiredOn) {
                blockers += ObserverStartBlocker.NotDesired
            }
            if (pairingFact() != PairingFact.PAIRED) {
                blockers += ObserverStartBlocker.Unpaired
            }
            if (probePlStatus() !is HarnessPlStatus.Reachable) {
                blockers += ObserverStartBlocker.TransportUnavailable
            }
            if (!foregroundStartAllowed.isForegroundStartAllowed()) {
                blockers += ObserverStartBlocker.ForegroundStartNotAllowed
            }
        }
        return ObserverStartReadiness(allowed = blockers.isEmpty(), blockers = blockers)
    }

    fun start(): Boolean {
        if (!startReadiness(ObserverStartMode.VisibleStart).allowed) {
            lastStartRefused = true
            return false
        }
        observerLifecycle.start()
        desiredOn = true
        lastStartRefused = false
        opportunisticSync?.start()
        return true
    }

    fun stop() {
        opportunisticSync?.stop()
        observerLifecycle.stop()
        desiredOn = false
    }

    fun rehydrate(): ObserverStartReadiness {
        if (!desiredOn) {
            return ObserverStartReadiness(false, setOf(ObserverStartBlocker.NotDesired))
        }
        val readiness = startReadiness(ObserverStartMode.Rehydrate)
        if (readiness.allowed) {
            observerLifecycle.start()
            opportunisticSync?.start()
        }
        return readiness
    }

    fun withScanSession(block: () -> Unit): Boolean {
        if (!beginScanSession()) return false
        try {
            block()
            return true
        } finally {
            endScanSession()
        }
    }

    fun beginScanSession(): Boolean {
        if (scanSessionHeld) return true
        if (!cameraLock.tryAcquire()) return false
        scanSessionHeld = true
        return true
    }

    fun endScanSession() {
        if (!scanSessionHeld) return
        scanSessionHeld = false
        cameraLock.release()
    }

    fun onScannedPairLink(rawText: String): HarnessPairProbeResult? {
        if (!looksLikePairLink(rawText)) return null
        val link = parsePairLink(rawText)
        val result = withPairLock {
            when (link) {
                is DirectPairLink -> runPairProbe(rawText)
                is RelayPairLink -> runRelayPairProbe(link)
            }
        }
        if (result != null && result.pairStatus in 200..299 && result.statusStatus in 200..299) {
            opportunisticSync?.onPairingSuccess()
        }
        return result
    }

    fun probePlStatus(): HarnessPlStatus {
        lastPlStatus = plStatusProbe.probe()
        return lastPlStatus
    }

    fun syncNow() {
        syncEnqueue.enqueueNow()
    }

    fun schedulePeriodicSync() {
        syncEnqueue.enqueuePeriodic()
    }

    fun listEvidence(): List<HarnessEvidenceSegment> = evidenceReader.listEvidence()

    fun syncState(): HarnessSyncState = evidenceReader.syncState()

    fun pairingFact(): PairingFact {
        val identity = identityStore.load()
        return pairingFactOf(
            credentialPresent = credentialStore.load() != null,
            endpointPresent = endpointStore.load() != null,
            relayOriginPresent = identity?.relayOrigin != null,
            identityState = identity?.state,
        )
    }

    fun exportSegment(segment: HarnessEvidenceSegment): HarnessExportResult = bundleExport.export(segment)

    fun diagnostics(): HarnessDiagnostics {
        val snapshot = sourceSnapshot()
        val identity = identityStore.load()
        val credentialPresent = credentialStore.load() != null
        val endpointPresent = endpointStore.load() != null
        return assembleDiagnostics(
            HarnessFactInputs(
                desiredOn = desiredOn,
                engineRunning = snapshot.engineRunning,
                permissionStatus = permissionStatusReader.read().also { permissionStatus = it },
                fgsHeartbeatFresh = heartbeatFreshness.isFresh(),
                providerEmitting = snapshot.providerEmitting,
                storageOk = snapshot.storageOk,
                credentialPresent = credentialPresent,
                endpointPresent = endpointPresent,
                relayOriginPresent = identity?.relayOrigin != null,
                identityState = identity?.state,
                exemptionVerified = snapshot.exemptionVerified,
            ),
        )
    }

    private fun runPairProbe(rawText: String): HarnessPairProbeResult {
        val result = pairProbe.pairAndProbe(rawText, deviceLabel)
        lastPairProbe = result
        return result
    }

    private fun runRelayPairProbe(link: RelayPairLink): HarnessPairProbeResult {
        val prior = identityStore.load()
        val priorMatches = prior?.instanceId == link.instanceId
        if (priorMatches && prior?.state == IdentityState.PAIRED) {
            val origin = prior.relayOrigin ?: link.relayOrigin ?: "https://link.solstone.app"
            val result = HarnessPairProbeResult(
                handshakePinned = false,
                pairStatus = 200,
                statusStatus = 200,
                statusBody = "",
                homeLabel = prior.homeLabel,
                endpointHost = URL(origin.trimEnd('/')).host,
                endpointPort = 443,
                connectionMode = PairConnectionMode.ALREADY_CONNECTED,
            )
            lastPairProbe = result
            return result
        }
        val mode = if (priorMatches) PairConnectionMode.RECONNECTING else PairConnectionMode.PAIRING
        val result = relayPairProbe.pairOverRelay(link, deviceLabel).copy(connectionMode = mode)
        lastPairProbe = result
        return result
    }

    private fun withPairLock(block: () -> HarnessPairProbeResult): HarnessPairProbeResult? {
        if (scanSessionHeld) {
            return block()
        }
        if (!cameraLock.tryAcquire()) return null
        return try {
            block()
        } finally {
            cameraLock.release()
        }
    }
}

data class SourceRuntimeSnapshot(
    val engineRunning: Boolean,
    val providerEmitting: Boolean,
    val storageOk: Boolean,
    val exemptionVerified: Boolean,
)
