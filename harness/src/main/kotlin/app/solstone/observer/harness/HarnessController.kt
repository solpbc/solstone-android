// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.harness

import app.solstone.core.identity.ClientCredentialStore
import app.solstone.core.identity.IdentityStore
import app.solstone.core.pl.EndpointStore
import app.solstone.core.pl.looksLikePairLink
import app.solstone.core.pl.parseDirectPairLink
import app.solstone.platform.camera.still.CameraLock
import app.solstone.platform.fgs.PermissionStatus
import app.solstone.platform.fgs.PermissionStatusReader

class HarnessController(
    private val permissionStatusReader: PermissionStatusReader,
    private val cameraLock: CameraLock,
    private val observerLifecycle: ObserverLifecycle,
    private val heartbeatFreshness: HeartbeatFreshness,
    private val pairProbe: PairProbe,
    private val plStatusProbe: PlStatusProbe,
    private val syncEnqueue: SyncEnqueue,
    private val evidenceReader: EvidenceReader,
    private val bundleExport: BundleExport,
    private val endpointStore: EndpointStore,
    private val credentialStore: ClientCredentialStore,
    private val identityStore: IdentityStore,
    private val sourceSnapshot: () -> SourceRuntimeSnapshot,
    private val deviceLabel: String,
) {
    var desiredOn: Boolean = false
        private set

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

    fun start(): Boolean {
        refreshPermissions()
        if (!permissionStatus.allRequiredGranted) {
            lastStartRefused = true
            return false
        }
        observerLifecycle.start()
        desiredOn = true
        lastStartRefused = false
        return true
    }

    fun stop() {
        observerLifecycle.stop()
        desiredOn = false
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
        parseDirectPairLink(rawText)
        if (scanSessionHeld) {
            return runPairProbe(rawText)
        }
        if (!cameraLock.tryAcquire()) return null
        return try {
            runPairProbe(rawText)
        } finally {
            cameraLock.release()
        }
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
}

data class SourceRuntimeSnapshot(
    val engineRunning: Boolean,
    val providerEmitting: Boolean,
    val storageOk: Boolean,
    val exemptionVerified: Boolean,
)
