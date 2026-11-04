// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.harness

import app.solstone.core.diagnostics.PairingFact
import app.solstone.core.diagnostics.pairingFactOf
import app.solstone.core.identity.ClientCredentialStore
import app.solstone.core.identity.IdentityMutator
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
import app.solstone.platform.fgs.CaptureForegroundType
import app.solstone.platform.fgs.ObserverForegroundService
import app.solstone.platform.fgs.PermissionStatus
import app.solstone.platform.fgs.PermissionStatusReader
import app.solstone.platform.fgs.satisfiableCaptureForegroundTypes
import app.solstone.platform.pl.transport.conscrypt.relayPairEndpoint
import app.solstone.platform.work.SyncDrainGate

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
    private val identityMutator: IdentityMutator? = null,
    private val publisher: app.solstone.core.identity.PairingPublisher? = null,
    private val diag: (String) -> Unit = {},
    private val declaredCaptureForegroundTypes: Set<CaptureForegroundType> = setOf(
        CaptureForegroundType.MICROPHONE,
        CaptureForegroundType.LOCATION,
        CaptureForegroundType.CAMERA,
    ),
    private val monotonicMs: () -> Long = { System.nanoTime() / 1_000_000 },
    // Whether the foreground service is up and holding its capture types — intake that started.
    private val foregroundServiceLive: () -> Boolean = { ObserverForegroundService.heldCaptureForegroundTypes != null },
    var mediaProjectionPromotable: () -> Boolean = { false },
    var onPairingCommitted: (() -> Unit)? = null,
    private val consultsConfirmation: () -> Boolean = { app.solstone.core.identity.JournalConfirmationPolicy.consults },
    /** Whether the phone has any network at all. No network is the one hard drop a phone gets. */
    private val hasNetwork: () -> Boolean = { true },
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

    var sourcesReader: SourcesReader? = null
    var blockStartWhenEffectiveEmpty: (() -> Boolean)? = null
    var onEffectiveCaptureEmpty: (() -> Unit)? = null

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
        val subset = satisfiableCaptureForegroundTypes(
            microphoneGranted = permissionStatus.microphoneGranted,
            cameraGranted = permissionStatus.cameraGranted,
            locationGranted = permissionStatus.locationGranted,
            declared = declaredCaptureForegroundTypes,
            mediaProjectionPromotable = mediaProjectionPromotable(),
        )
        if (subset.isEmpty()) {
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
            if (blockers.isEmpty() && currentPlStatus() !is HarnessPlStatus.Reachable) {
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
        lastStartRefused = false
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
        // 🔴 **A live foreground service is intake that started, so a refusal recorded before it is
        // history.** The flag outranks every other reason on every source row, and nothing else
        // cleared it while intake ran: an external tester's phone read `needs attention: intake
        // couldn't start from the background` on all three sources, in the app and the shade, while
        // the service held all three types and segments kept syncing. Only `stop intake` and a
        // restart from the app cleared it.
        if (lastStartRefused && foregroundServiceLive()) {
            lastStartRefused = false
            emitDiag("reconcile mode=$mode start-refusal cleared reason=foreground-live")
        }
        val sources = sourcesReader?.snapshot()?.sources
        // 🔴 **A granted capture permission is not a reason to bring intake up; a source the owner
        // wants on is.** Start readiness is computed from permissions alone, so with a permission
        // held and every source off this started the foreground service, under that permission's
        // capture type, with nothing to take in. That is how allowing the camera to scan a pairing
        // code put a camera-typed service and its notification up behind an owner who had turned
        // nothing on. ⚠ A reader with no sources at all is a surface without per-source wishes, and
        // keeps the old behaviour.
        if (sources != null && sources.isNotEmpty() && sources.none { it.wish == SourceWish.On }) {
            emitDiag("reconcile mode=$mode result=idle reason=no-source-on")
            return
        }
        if (blockStartWhenEffectiveEmpty?.invoke() == true) {
            return
        }
        val hasTypeNotHeld = sources?.any {
            it.wish == SourceWish.On && it.reason == ReasonCode.FOREGROUND_TYPE_NOT_HELD
        } == true
        if (diagnostics().state == SourceState.ON && !hasTypeNotHeld) return

        if (hasTypeNotHeld) {
            // A periodic refresh reports this condition; a visible owner action recovers it.
            if (mode == ObserverStartMode.VisibleStart && visibleCaptureAuthority.isVisibleOwnerPresent()) {
                observerLifecycle.restartCaptureForHeldTypes()
            }
            return
        }

        val readiness = startReadiness(mode)
        if (!readiness.allowed) {
            // ⚠ Not a refusal while the service is live. Any state short of `on` reaches here — the
            // live case was a microphone another app took over (a screen recording with audio), which
            // reads `paused` — and from the background the visibility check blocks the "start". But
            // nothing was refused: Android was never asked, and intake was already running.
            if (readiness.blockers.contains(ReasonCode.FOREGROUND_START_NOT_ALLOWED) && !foregroundServiceLive()) {
                lastStartRefused = true
            }
            emitDiag("reconcile mode=$mode result=blocked blockers=${readiness.blockers.map { it.name }.sorted().joinToString(",")}")
            return
        }
        emitDiag("reconcile mode=$mode result=started")
        if (mode == ObserverStartMode.ForegroundServiceStart) {
            observerLifecycle.startWhenAlreadyForeground()
        } else {
            observerLifecycle.start()
        }
        lastStartRefused = false
        opportunisticSync?.start()
    }

    fun ensureObserving() {
        if (blockStartWhenEffectiveEmpty?.invoke() == true) {
            val blocked = onEffectiveCaptureEmpty
            if (blocked != null) blocked() else stop()
            return
        }
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

    /**
     * Runs one pairing attempt, and puts back the pairing it replaced unless it [succeeded]. An
     * attempt that failed before installing anything leaves the committed pairing as it found it, so
     * there is nothing to put back.
     */
    private fun <T> restoringPriorUnless(succeeded: (T) -> Boolean, attempt: () -> T): T {
        val before = committedPairing()
        val outcome = try {
            attempt()
        } catch (t: Throwable) {
            restorePriorIfReplaced(before)
            throw t
        }
        if (!succeeded(outcome)) restorePriorIfReplaced(before)
        return outcome
    }

    private fun committedPairing(): app.solstone.core.identity.PairingGeneration? =
        (publisher?.currentSnapshot() as? app.solstone.core.identity.PairingGraphSnapshot.Committed)?.pairing

    private fun restorePriorIfReplaced(before: app.solstone.core.identity.PairingGeneration?) {
        val activePublisher = publisher ?: return
        val current = committedPairing() ?: return
        if (current != before && activePublisher.canRestorePrior(current)) activePublisher.restorePrior(current)
    }

    fun onScannedPairLink(rawText: String): HarnessPairProbeResult? {
        if (!looksLikePairLink(rawText)) return null
        val link = parsePairLink(rawText)
        val result = withPairLock {
            if (replacementPreflight() != null) {
                null
            } else {
                restoringPriorUnless({ it.pairStatus in 200..299 && it.statusStatus in 200..299 }) {
                    when (link) {
                        is DirectPairLink -> runPairProbe(rawText)
                        is RelayPairLink -> runRelayPairProbe(link)
                    }
                }
            }
        }
        if (result != null && result.pairStatus in 200..299 && result.statusStatus in 200..299) {
            notePairingCommitted()
        }
        return result
    }

    fun onScannedPairLinkClassified(rawText: String): PairAttemptOutcome {
        if (!looksLikePairLink(rawText)) return PairAttemptOutcome.Retry
        val link = parsePairLink(rawText)
        val outcome = withPairLock {
            replacementPreflight() ?: restoringPriorUnless({
                it is PairAttemptOutcome.Linked && it.result.pairStatus in 200..299 && it.result.statusStatus in 200..299
            }) {
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
            }
        } ?: return PairAttemptOutcome.Retry
        if (
            outcome is PairAttemptOutcome.Linked &&
            outcome.result.pairStatus in 200..299 &&
            outcome.result.statusStatus in 200..299
        ) {
            notePairingCommitted()
        }
        return outcome
    }

    /**
     * A revoked pairing is deliberately not treated as current: pairing a fresh link is its repair.
     * A current pairing, however, must prove it is dead before this device changes credentials.
     *
     * A peer answering where the paired journal should be, with no leg reaching that journal, is the
     * one state where an unreachable pairing may be replaced. Only the owner can tell a reset journal
     * from another device holding the address, and a changed certificate is a re-pair: refusing here
     * would leave the notice's own `pair again` a dead end. A journal refusing this device has proven
     * its pairing dead, the same as a revoked one. Both rest on the mark question, which can put the
     * prior pairing back, so an app that asks no question replaces nothing this way.
     */
    private fun replacementPreflight(): PairAttemptOutcome? {
        if (pairingFact() != PairingFact.PAIRED) return null
        val status = probePlStatus()
        val answerable = consultsConfirmation()
        if (answerable && status.journalRefusesThisDevice()) return null
        return when (status) {
            is HarnessPlStatus.Reachable -> PairAttemptOutcome.ExistingPairingActive
            is HarnessPlStatus.PairedButUnreachable ->
                if (answerable && status.unknownJournal != null && !status.journalAnswered) {
                    null
                } else {
                    PairAttemptOutcome.ExistingPairingUnreachable
                }
            // The persisted pairing fact and the probe disagree: the current journal record is
            // gone, so this is the same repair path as a journal-side revocation.
            HarnessPlStatus.NotPaired -> null
        }
    }

    private fun notePairingCommitted() {
        opportunisticSync?.onPairingSuccess()
        onPairingCommitted?.invoke()
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

    /** A fresh check, never the cache: an owner's own connection check and a pairing preflight. */
    fun probePlStatus(): HarnessPlStatus {
        val key = routineProbeKey()
        val startedAt = monotonicMs()
        val status = plStatusProbe.probe()
        val finishedAt = monotonicMs()
        lastPlStatus = status
        routineProbe = RoutineProbe(key, startedAt, finishedAt, status)
        synchronized(connection) {
            followPairing(key, finishedAt)
            if (status.journalHeard()) connection.heard(finishedAt) else connection.unheard(finishedAt)
        }
        return status
    }

    /**
     * The last check for the current pairing, without dialling. Only a pairing with no check yet is
     * checked here, so a status read never waits on the network otherwise.
     */
    fun currentPlStatus(): HarnessPlStatus {
        val key = routineProbeKey()
        routineProbe?.takeIf { it.key == key }?.let { return it.status }
        return synchronized(checkLock) {
            routineProbe?.takeIf { it.key == key }?.status ?: probePlStatus()
        }
    }

    /**
     * Checks the journal when one is due. Returns whether the connection may have changed: it
     * checked, or the network came or went. With the app in the
     * foreground a check may start [FOREGROUND_CHECK_SPACING_MS] after the previous one started.
     * In the background it starts [ROUTINE_PROBE_MAX_AGE_MS] after the previous one finished, the
     * same budget the status tick always had. A new pairing is checked at once. With no network at
     * all there is nothing to dial, and the presenter already reads offline.
     *
     * ⛔ Not a fresh check per status read. The 5 s status tick once probed twice a tick, a new TLS
     * session to the journal every 2.5 s for as long as the app ran.
     */
    fun checkIfDue(): Boolean = synchronized(checkLock) {
        val now = monotonicMs()
        val networkPresent = hasNetwork()
        val networkChanged = networkPresent != lastNetworkPresent
        lastNetworkPresent = networkPresent
        synchronized(connection) { connection.network(networkPresent, now) }
        if (!networkPresent) return networkChanged
        val last = routineProbe
        val due = last == null || last.key != routineProbeKey() || if (foreground) {
            now - last.startedMs !in 0 until FOREGROUND_CHECK_SPACING_MS
        } else {
            now - last.finishedMs !in 0 until ROUTINE_PROBE_MAX_AGE_MS
        }
        if (due) probePlStatus()
        due || networkChanged
    }

    /** The app came to the foreground or left it. Coming forward starts a new connecting episode. */
    fun onForeground(inForeground: Boolean) {
        foreground = inForeground
        synchronized(connection) { connection.foreground(inForeground, monotonicMs()) }
    }

    /** What every status surface shows about the connection now, and the waiting count beside it. */
    fun presentConnection(pendingCount: Int): PresentedStatus {
        val key = routineProbeKey()
        val now = monotonicMs()
        return synchronized(connection) {
            followPairing(key, now)
            connection.present(now, pendingCount)
        }
    }

    /** How long until the presented connection changes on its own, or null when only evidence changes it. */
    fun connectionChangeInMs(): Long? {
        val now = monotonicMs()
        return synchronized(connection) { connection.nextChangeAt(now) }?.let { it - now }
    }

    private fun followPairing(key: Any, nowMs: Long) {
        if (connectionKey == key) return
        connectionKey = key
        connection.reset(nowMs)
    }

    @Volatile private var routineProbe: RoutineProbe? = null
    @Volatile private var foreground = false
    private var lastNetworkPresent = true
    private val checkLock = Any()
    private val connection = ConnectionPresenter(monotonicMs())
    private var connectionKey: Any? = null

    private data class RoutineProbe(val key: Any, val startedMs: Long, val finishedMs: Long, val status: HarnessPlStatus)

    private fun routineProbeKey(): Any =
        identityStore.load().let { Triple(pairingFact(), it?.instanceId, it?.clientCertFingerprint) }

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
        if (publisher != null) {
            return when (val snap = publisher.currentSnapshot()) {
                is app.solstone.core.identity.PairingGraphSnapshot.Absent -> PairingFact.UNPAIRED
                is app.solstone.core.identity.PairingGraphSnapshot.Uncertain -> PairingFact.UNPAIRED
                is app.solstone.core.identity.PairingGraphSnapshot.Committed -> {
                    when (snap.home.state) {
                        IdentityState.REVOKED -> PairingFact.REVOKED
                        IdentityState.PAIRED -> PairingFact.PAIRED
                        else -> PairingFact.UNPAIRED
                    }
                }
            }
        }
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
        val identityPersistenceOk = identityMutator?.lastPersistenceIssue() == null
        return HarnessFactInputs(
            desiredOn = desiredOn,
            engineRunning = snapshot.engineRunning,
            permissionStatus = permissionStatusReader.read().also { permissionStatus = it },
            fgsHeartbeatFresh = heartbeatFreshness.isFresh(),
            fgsStartEvidence = heartbeatFreshness.hasStartEvidence(),
            providerEmitting = snapshot.providerEmitting,
            storageOk = snapshot.storageOk,
            identityPersistenceOk = identityPersistenceOk,
            credentialPresent = credentialPresent,
            endpointPresent = endpointPresent,
            relayOriginPresent = identity?.relayOrigin != null,
            identityState = identity?.state,
            silenced = snapshot.silenced,
            engineStartIssued = snapshot.engineStartIssued,
            declaredCaptureForegroundTypes = declaredCaptureForegroundTypes,
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
        val acquiredCamera = !scanSessionHeld
        if (acquiredCamera && !cameraLock.tryAcquire()) return null
        if (!SyncDrainGate.tryAcquire()) {
            if (acquiredCamera) cameraLock.release()
            return null
        }
        return try {
            block()
        } finally {
            SyncDrainGate.release()
            if (acquiredCamera) cameraLock.release()
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

/** In the background, how long after a check finishes the next one may start. */
const val ROUTINE_PROBE_MAX_AGE_MS = 60_000L

/** With the app in the foreground, how long after a check starts the next one may start. */
const val FOREGROUND_CHECK_SPACING_MS = 15_000L
