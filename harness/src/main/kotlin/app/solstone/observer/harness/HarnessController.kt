// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.harness

import app.solstone.core.diagnostics.PairingFact
import app.solstone.core.diagnostics.pairingFactOf
import app.solstone.core.identity.ClientCredentialStore
import app.solstone.core.identity.IdentityStore
import app.solstone.core.model.IdentityState
import app.solstone.core.model.ReasonCode
import app.solstone.core.model.SilencedFact
import app.solstone.core.model.SourceState
import app.solstone.core.pl.EndpointStore
import app.solstone.core.pl.DirectPairLink
import app.solstone.core.pl.DirectEndpoint
import app.solstone.core.pl.RelayPairLink
import app.solstone.core.pl.looksLikePairLink
import app.solstone.core.pl.parsePairLink
import app.solstone.core.sources.ContinuousSourceEngine
import app.solstone.core.sources.SourceCondition
import app.solstone.platform.camera.still.CameraLock
import app.solstone.platform.fgs.PermissionStatus
import app.solstone.platform.fgs.PermissionStatusReader
import app.solstone.platform.pl.transport.conscrypt.relayPairEndpoint

enum class ObserverStartMode { VisibleStart, Rehydrate, ForegroundServiceStart }

sealed interface PairLinkDispatchResult {
    data object NoLink : PairLinkDispatchResult
    data object InvalidLink : PairLinkDispatchResult
    data object Busy : PairLinkDispatchResult
    data class Attempted(val outcome: PairAttemptOutcome) : PairLinkDispatchResult
}

data class ObserverStartReadiness(
    val allowed: Boolean,
    val blockers: Set<ReasonCode>,
)

class HarnessController(
    private val permissionStatusReader: PermissionStatusReader,
    private val desiredObservingStore: DesiredObservingStore,
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
    private val visibleCaptureAuthority: VisibleCaptureAuthority,
    private val isUsableNetworkPresent: () -> Boolean,
    private val opportunisticSync: OpportunisticSync? = null,
    private val diag: (String) -> Unit = {},
) {
    var desiredOn: Boolean
        get() = desiredObservingStore.isDesiredOn()
        private set(value) {
            val old = desiredObservingStore.isDesiredOn()
            desiredObservingStore.setDesiredOn(value)
            if (old != value) {
                emitDiag("desired on=$value")
            }
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
    private var reconcileInFlight = false
    private var reconcileRerunRequested = false

    fun refreshPermissions(): PermissionStatus {
        permissionStatus = permissionStatusReader.read()
        return permissionStatus
    }

    fun onPermissionsRequested(): PermissionStatus = refreshPermissions()

    fun isVisibleCaptureOwnerPresent(): Boolean = visibleCaptureAuthority.isVisibleOwnerPresent()

    fun startReadiness(mode: ObserverStartMode): ObserverStartReadiness {
        refreshPermissions()
        val blockers = linkedSetOf<ReasonCode>()
        if (!permissionStatus.allRequiredGranted) {
            blockers += ReasonCode.PERMISSION_REVOKED
        }
        if (mode != ObserverStartMode.ForegroundServiceStart && !visibleCaptureAuthority.isVisibleOwnerPresent()) {
            blockers += ReasonCode.FOREGROUND_START_NOT_ALLOWED
        }
        if (mode == ObserverStartMode.Rehydrate) {
            if (!desiredOn) {
                blockers += ReasonCode.DESIRED_OFF
            }
            if (pairingFact() != PairingFact.PAIRED) {
                blockers += ReasonCode.UNPAIRED
            }
            if (blockers.isEmpty() && probePlStatus() !is HarnessPlStatus.Reachable) {
                blockers += ReasonCode.TRANSPORT_UNAVAILABLE
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

    fun startWhenAlreadyForeground(): ObserverStartReadiness {
        val readiness = startReadiness(ObserverStartMode.ForegroundServiceStart)
        if (!readiness.allowed) {
            lastStartRefused = true
            return readiness
        }
        observerLifecycle.startWhenAlreadyForeground()
        desiredOn = true
        lastStartRefused = false
        opportunisticSync?.start()
        return readiness
    }

    fun recordStartRefusal() {
        lastStartRefused = true
    }

    fun stop() {
        opportunisticSync?.stop()
        observerLifecycle.stop()
        desiredOn = false
    }

    fun reconcile(mode: ObserverStartMode) {
        if (reconcileInFlight) {
            // Carry the caller's mode into the rerun. Repeating the in-flight mode instead would
            // silently downgrade an owner-initiated start that overlapped a background pass, and a
            // background mode is gated by blockers a visible start does not carry.
            pendingReconcileMode = mode
            reconcileRerunRequested = true
            return
        }
        reconcileInFlight = true
        try {
            var current = mode
            do {
                reconcileRerunRequested = false
                reconcileOnce(current)
                val queued = pendingReconcileMode
                pendingReconcileMode = null
                if (reconcileRerunRequested && queued != null) {
                    current = queued
                }
            } while (reconcileRerunRequested)
        } finally {
            reconcileInFlight = false
            reconcileRerunRequested = false
            pendingReconcileMode = null
        }
    }

    @Volatile private var pendingReconcileMode: ObserverStartMode? = null

    private fun reconcileOnce(mode: ObserverStartMode) {
        if (!desiredOn) return
        if (diagnostics().state == SourceState.ON) return
        val readiness = startReadiness(mode)
        if (!readiness.allowed) {
            emitDiag("reconcile mode=$mode result=blocked blockers=${readiness.blockers.map { it.name }.sorted().joinToString(",")}")
            return
        }
        emitDiag("reconcile mode=$mode result=started")
        if (mode == ObserverStartMode.ForegroundServiceStart) {
            observerLifecycle.startWhenAlreadyForeground()
        } else {
            observerLifecycle.start()
        }
        opportunisticSync?.start()
    }

    fun ensureObserving() {
        desiredOn = true
        reconcile(ObserverStartMode.VisibleStart)
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

    fun onScannedPairLinkClassified(rawText: String): PairAttemptOutcome {
        if (!looksLikePairLink(rawText)) return PairAttemptOutcome.Retry
        val link = parsePairLink(rawText)
        val outcome = withPairLock {
            when (link) {
                is RelayPairLink -> try {
                    PairAttemptOutcome.Linked(runRelayPairProbe(link))
                } catch (e: Throwable) {
                    val endpoint = runCatching { relayPairEndpoint(link) }
                        .getOrElse { DirectEndpoint("", 443) }
                    classifyPairException(e, endpoint.host, endpoint.port, PairRoute.RELAY, isUsableNetworkPresent)
                }
                is DirectPairLink -> try {
                    PairAttemptOutcome.Linked(runPairProbe(rawText))
                } catch (e: Throwable) {
                    val endpoint = link.endpoint()
                    classifyPairException(e, endpoint.host, endpoint.port, PairRoute.DIRECT, isUsableNetworkPresent)
                }
            }
        } ?: return PairAttemptOutcome.Retry
        if (
            outcome is PairAttemptOutcome.Linked &&
            outcome.result.pairStatus in 200..299 &&
            outcome.result.statusStatus in 200..299
        ) {
            opportunisticSync?.onPairingSuccess()
        }
        return outcome
    }

    fun dispatchPairLink(rawText: String?): PairLinkDispatchResult {
        if (rawText == null) return PairLinkDispatchResult.NoLink
        if (!looksLikePairLink(rawText)) return PairLinkDispatchResult.InvalidLink
        try {
            parsePairLink(rawText)
        } catch (_: IllegalArgumentException) {
            return PairLinkDispatchResult.InvalidLink
        }
        return when (val outcome = onScannedPairLinkClassified(rawText)) {
            PairAttemptOutcome.Retry -> PairLinkDispatchResult.Busy
            else -> PairLinkDispatchResult.Attempted(outcome)
        }
    }

    fun probePlStatus(): HarnessPlStatus {
        lastPlStatus = plStatusProbe.probe()
        return lastPlStatus
    }

    fun syncNow(): SyncNowResult {
        val fact = pairingFact()
        if (fact != PairingFact.PAIRED) return SyncNowResult.NotPaired(fact)
        return try {
            syncEnqueue.enqueueNow()
            SyncNowResult.Enqueued
        } catch (throwable: Throwable) {
            SyncNowResult.EnqueueFailed(throwable.message)
        }
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

    fun globalFactInputs(): HarnessFactInputs {
        val snapshot = sourceSnapshot()
        val identity = identityStore.load()
        val credentialPresent = credentialStore.load() != null
        val endpointPresent = endpointStore.load() != null
        return HarnessFactInputs(
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
            silenced = snapshot.silenced,
            engineStartIssued = snapshot.engineStartIssued,
        )
    }

    fun diagnostics(): HarnessDiagnostics = assembleDiagnostics(globalFactInputs())

    private fun runPairProbe(rawText: String): HarnessPairProbeResult {
        val result = pairProbe.pairAndProbe(rawText, deviceLabel)
        lastPairProbe = result
        return result
    }

    private fun runRelayPairProbe(link: RelayPairLink): HarnessPairProbeResult {
        val result = relayPairProbe.pairOverRelay(link, deviceLabel)
        lastPairProbe = result
        return result
    }

    private fun <T> withPairLock(block: () -> T): T? {
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

    private fun emitDiag(line: String) {
        runCatching { diag(line) }
    }
}

data class SourceRuntimeSnapshot(
    val engineRunning: Boolean,
    val providerEmitting: Boolean,
    val storageOk: Boolean,
    val silenced: SilencedFact,
    val engineStartIssued: Boolean = true,
)

fun sourceRuntimeSnapshotFromEngines(
    engines: Collection<ContinuousSourceEngine>,
    providerEmitting: Boolean,
    storageOk: Boolean,
    engineStartIssued: Boolean,
): SourceRuntimeSnapshot {
    val conditions = engines.mapNotNull { engine -> runCatching { engine.condition() }.getOrNull() }
    return sourceRuntimeSnapshotOf(
        engineRunning = conditions.any { it.running },
        providerEmitting = providerEmitting,
        storageOk = storageOk,
        conditions = conditions,
        engineStartIssued = engineStartIssued,
    )
}

fun sourceRuntimeSnapshotOf(
    engineRunning: Boolean,
    providerEmitting: Boolean,
    storageOk: Boolean,
    conditions: Collection<SourceCondition>,
    engineStartIssued: Boolean = true,
): SourceRuntimeSnapshot =
    SourceRuntimeSnapshot(
        engineRunning = engineRunning,
        providerEmitting = providerEmitting,
        storageOk = storageOk,
        silenced = when {
            conditions.isEmpty() -> SilencedFact.UNKNOWN
            conditions.any { it.silenced == SilencedFact.SILENCED } -> SilencedFact.SILENCED
            conditions.any { it.silenced == SilencedFact.UNKNOWN } -> SilencedFact.UNKNOWN
            else -> SilencedFact.NOT_SILENCED
        },
        engineStartIssued = engineStartIssued,
    )
