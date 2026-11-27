// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.work

import app.solstone.core.identity.ClientCredential
import app.solstone.core.identity.JournalConfirmationPolicy
import app.solstone.core.identity.PairingGeneration
import app.solstone.core.identity.confirmedFor
import app.solstone.core.pl.JournalAttempt
import app.solstone.core.pl.PlHttpClient
import app.solstone.core.queue.resumableChunkRetryDelayMs
import app.solstone.platform.persistence.room.ImportCustodyStore
import app.solstone.platform.pl.transport.conscrypt.RelayDialWaitingException
import app.solstone.platform.pl.transport.conscrypt.RelayWebSocketClosedException
import app.solstone.platform.pl.transport.conscrypt.defaultHttpsPoster
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random

/**
 * Hooks for waking an active user-initiated import upload job on genuine network recovery
 * (installed by the phone app process).
 */
object ImportUploadWake {
    @Volatile
    var onNetworkRecovery: (() -> Unit)? = null

    fun notifyNetworkRecovery() {
        runCatching { onNetworkRecovery?.invoke() }
    }
}

/**
 * Control handle for one active background import upload session (such as an API 34+
 * user-initiated data transfer job). Safe to signal from the main thread (`onStopJob`,
 * `onNetworkChanged`, or `Cancel` broadcast).
 */
class ResumableImportSessionControl {
    private val stopped = AtomicBoolean(false)
    private val networkResetPending = AtomicBoolean(false)
    private val activeClient = AtomicReference<Closeable?>(null)
    private val waitLock = Object()

    val isStopped: Boolean get() = stopped.get()

    internal fun attachClient(client: Closeable?) {
        activeClient.set(client)
        if (stopped.get() && client != null) {
            runCatching { client.close() }
        }
    }

    internal fun detachClient(client: Closeable?) {
        activeClient.compareAndSet(client, null)
    }

    /** Stop the active session cleanly (e.g. from `JobService.onStopJob`), closing any wedged socket. */
    fun requestStop() {
        stopped.set(true)
        activeClient.getAndSet(null)?.let { runCatching { it.close() } }
        synchronized(waitLock) { waitLock.notifyAll() }
    }

    /**
     * Signal that the underlying network switched or recovered. Wakes any retry sleep immediately;
     * when [interruptActiveConnection] is true, also closes the currently open socket so a wedged
     * transfer on the dead interface re-dials on the new network without waiting for socket timeout.
     */
    fun signalNetworkChange(interruptActiveConnection: Boolean) {
        networkResetPending.set(true)
        if (interruptActiveConnection) {
            activeClient.getAndSet(null)?.let { runCatching { it.close() } }
        }
        synchronized(waitLock) { waitLock.notifyAll() }
    }

    internal fun consumeNetworkReset(): Boolean = networkResetPending.getAndSet(false)

    /** Sleep up to [delayMs], returning early if stopped or a network change wakes the job. */
    internal fun awaitRetryDelay(delayMs: Long) {
        if (delayMs <= 0L || stopped.get() || networkResetPending.get()) return
        val deadline = System.currentTimeMillis() + delayMs
        synchronized(waitLock) {
            while (!stopped.get() && !networkResetPending.get()) {
                val remaining = deadline - System.currentTimeMillis()
                if (remaining <= 0L) break
                try {
                    waitLock.wait(remaining)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        }
    }
}

enum class ResumableImportRunOutcome {
    /** Every deliverable import finished, entered attention/review, or cannot run right now. */
    FINISHED,

    /** Stopped early (for example via `onStopJob`) while deliverable items remain; reschedule. */
    RESCHEDULE,
}

/**
 * Run continuous resumable import delivery for an API 34+ user-initiated data transfer job without
 * holding [SyncDrainGate] (so concurrent segment syncs are never starved by a multi-gigabyte
 * upload). Respects [SyncDrainGate.closed] (age-signal stop) before reading credentials or dialing,
 * uses per-item [ImportCustodyStore] `inFlight` locking, and retries transient disconnects using
 * the contract's `1s -> 2s -> 5s -> 10s -> 30s -> 60s` (±20%) ladder.
 */
fun runResumableImportUploadLoop(
    stores: SyncStores,
    importStore: ImportCustodyStore,
    control: ResumableImportSessionControl,
    onObservedThreshold: ((Long) -> Unit)? = null,
    openClient: ((SyncTransport, ClientCredential) -> PlHttpClient)? = null,
    now: () -> Long = System::currentTimeMillis,
    jitterUnit: () -> Double = { Random.nextDouble() },
    waitRetry: (Long) -> Unit = { control.awaitRetryDelay(it) },
    onRetryWaiting: ((attempt: Int) -> Boolean)? = null,
    diag: (String) -> Unit = { line ->
        SyncWorker.syncDiag?.invoke(line)
        workerLog("w", line, null)
    },
    log: (String, Throwable?) -> Unit = { msg, t -> workerLog("w", msg, t) },
): ResumableImportRunOutcome {
    val retriedItems = linkedSetOf<String>()
    var retryAttempt = 0
    while (!control.isStopped) {
        if (SyncDrainGate.closed()) {
            return ResumableImportRunOutcome.FINISHED
        }
        val (credentials, allowsOwnerMaterial) = stores.publisher.withMutationBoundary {
            val creds = recoverSyncCredentials(stores.publisher)
            val allows = when (creds) {
                is SyncCredentials.Ready -> confirmedFor(
                    JournalConfirmationPolicy.consults,
                    stores.journalConfirmationStore,
                    creds.identity.clientCertFingerprint,
                )
                else -> false
            }
            creds to allows
        }
        if (credentials !is SyncCredentials.Ready || !allowsOwnerMaterial) {
            return ResumableImportRunOutcome.FINISHED
        }

        val instanceId = credentials.identity.instanceId
        val attemptPairing = PairingGeneration(instanceId, credentials.identity.clientCertFingerprint)
        if (stores.pairingRefusalStore?.refusedFor(attemptPairing) == true) {
            return ResumableImportRunOutcome.FINISHED
        }
        val guard = stores.handshakeGuard
        if (guard != null && guard.stopped(attemptPairing)) {
            return ResumableImportRunOutcome.FINISHED
        }

        importStore.resetReviewItemsForInstanceChange(instanceId)
        importStore.resetUploadSessionsForInstanceChange(instanceId)

        val record = stores.journalVersionStore.load()
        val journalVersion = record?.version.orEmpty()
        val gate = importVersionGate(record, instanceId, credentials.identity.caChainFingerprint)
        if (gate != ImportVersionGate.SUPPORTED) {
            return ResumableImportRunOutcome.FINISHED
        }
        if (!hasDeliverableImports(importStore, instanceId, journalVersion, now())) {
            return ResumableImportRunOutcome.FINISHED
        }

        val progressBefore = snapshotConfirmedBytes(importStore, instanceId, journalVersion)
        val stageOutcome = executeSingleImportPass(
            stores = stores,
            retriedItems = retriedItems,
            credentials = credentials,
            attemptPairing = attemptPairing,
            importStore = importStore,
            journalVersion = journalVersion,
            gate = gate,
            control = control,
            onObservedThreshold = onObservedThreshold,
            openClient = openClient,
            now = now,
            diag = diag,
            log = log,
        )
        val progressAfter = snapshotConfirmedBytes(importStore, instanceId, journalVersion)
        val madeProgress = progressAfter != progressBefore

        if (control.isStopped) {
            val stillHasWork = hasDeliverableImports(importStore, instanceId, journalVersion, now())
            return if (stillHasWork) ResumableImportRunOutcome.RESCHEDULE else ResumableImportRunOutcome.FINISHED
        }

        when (stageOutcome) {
            ImportStageResult.AUTH_STOP,
            ImportStageResult.HELD,
            -> return ResumableImportRunOutcome.FINISHED

            ImportStageResult.PAIRING_LEFT -> {
                retriedItems.clear()
                retryAttempt = 0
                continue
            }

            ImportStageResult.CLEAN -> {
                retryAttempt = 0
                if (!hasDeliverableImports(importStore, instanceId, journalVersion, now())) {
                    return ResumableImportRunOutcome.FINISHED
                }
            }

            ImportStageResult.RETRY -> {
                if (madeProgress || control.consumeNetworkReset()) {
                    retryAttempt = 0
                    importStore.clearRetrySchedule()
                    continue
                }
                retryAttempt++
                if (onRetryWaiting != null && !onRetryWaiting(retryAttempt)) {
                    importStore.clearRetrySchedule()
                    return ResumableImportRunOutcome.RESCHEDULE
                }
                val delayMs = resumableChunkRetryDelayMs(retryAttempt, jitterUnit())
                waitRetry(delayMs)
                if (control.consumeNetworkReset()) {
                    retryAttempt = 0
                }
                // Clear item-level backoff schedule because this job manages the retry loop directly.
                importStore.clearRetrySchedule()
            }
        }
    }
    return ResumableImportRunOutcome.RESCHEDULE
}

private fun snapshotConfirmedBytes(
    store: ImportCustodyStore,
    instanceId: String,
    journalVersion: String,
): Map<String, Long> =
    store.items(instanceId, journalVersion).associate { it.id to it.uploadOffset }

private fun executeSingleImportPass(
    stores: SyncStores,
    retriedItems: MutableSet<String>,
    credentials: SyncCredentials.Ready,
    attemptPairing: PairingGeneration,
    importStore: ImportCustodyStore,
    journalVersion: String,
    gate: ImportVersionGate,
    control: ResumableImportSessionControl,
    onObservedThreshold: ((Long) -> Unit)?,
    openClient: ((SyncTransport, ClientCredential) -> PlHttpClient)?,
    now: () -> Long,
    diag: (String) -> Unit,
    log: (String, Throwable?) -> Unit,
): ImportStageResult {
    val pairingCurrent = { stores.identityMutator.currentPairingGeneration() == attemptPairing }
    val poster = defaultHttpsPoster()
    val guard = stores.handshakeGuard
    val attempt = guard?.begin(attemptPairing) ?: JournalAttempt.untracked(attemptPairing)
    val importRun = ImportRun(cap = Int.MAX_VALUE, retried = retriedItems)

    try {
        val tryTransport: (SyncTransport, Boolean) -> ImportStageResult = transportAttempt@{ selectedTransport, propagateWsClosed ->
            if (control.isStopped) return@transportAttempt ImportStageResult.RETRY
            val access = stores.identityMutator.accessSnapshot() ?: return@transportAttempt ImportStageResult.RETRY
            if (access.pairing != attemptPairing) return@transportAttempt ImportStageResult.PAIRING_LEFT
            if (!transportAccessStillCurrent(selectedTransport, credentials.identity, access, stores.identityMutator)) {
                return@transportAttempt ImportStageResult.PAIRING_LEFT
            }
            val closeable = try {
                toCloseableClient(
                    recordDial(DialDiagnostics.events, selectedTransport) {
                        if (openClient != null) {
                            openClient(selectedTransport, credentials.credential)
                        } else {
                            openSyncClient(selectedTransport, credentials.credential, attempt)
                        }
                    },
                )
            } catch (e: RelayDialWaitingException) {
                attempt.legFailed(e)
                return@transportAttempt ImportStageResult.RETRY
            } catch (e: RelayWebSocketClosedException) {
                if (propagateWsClosed) throw e
                return@transportAttempt ImportStageResult.RETRY
            } catch (t: Throwable) {
                attempt.legFailed(t)
                return@transportAttempt ImportStageResult.RETRY
            }
            control.attachClient(closeable)
            try {
                closeable.use { client ->
                    val status = try {
                        client.request("GET", "/app/network/api/status", emptyMap(), ByteArray(0)).status
                    } catch (e: RelayWebSocketClosedException) {
                        if (propagateWsClosed) throw e
                        return@use ImportStageResult.RETRY
                    } catch (t: Throwable) {
                        attempt.legFailed(t)
                        return@use ImportStageResult.RETRY
                    }
                    attempt.answered()
                    when (status) {
                        200 -> stores.pairingRefusalStore?.markAuthorized(attemptPairing)
                        401, 403 -> {
                            stores.pairingRefusalStore?.markRefused(attemptPairing)
                            return@use ImportStageResult.AUTH_STOP
                        }
                        else -> return@use ImportStageResult.RETRY
                    }
                    val stage = deliverImports(
                        http = client,
                        store = importStore,
                        run = importRun,
                        instanceId = credentials.identity.instanceId,
                        journalVersion = journalVersion,
                        gate = gate,
                        pairingCurrent = pairingCurrent,
                        now = now,
                        log = log,
                        diag = diag,
                        clientCertFingerprint = credentials.identity.clientCertFingerprint,
                        onObservedThreshold = onObservedThreshold,
                        shouldYield = { control.isStopped },
                    )
                    if (stage == ImportStageResult.AUTH_STOP) {
                        stores.pairingRefusalStore?.markRefused(attemptPairing)
                    }
                    stage
                }
            } catch (e: RelayWebSocketClosedException) {
                if (propagateWsClosed) throw e
                ImportStageResult.RETRY
            } catch (_: IOException) {
                ImportStageResult.RETRY
            } finally {
                control.detachClient(closeable)
            }
        }

        val tryRelay: (SyncTransport.Relay) -> ImportStageResult = { relayTransport ->
            val maintained = maintainRelayToken(
                identity = credentials.identity,
                transport = relayTransport,
                poster = poster,
                mutator = stores.identityMutator,
            )
            when (maintained) {
                is RelayTokenResult.Ready -> {
                    var stageResult = ImportStageResult.RETRY
                    dialWithReactiveRefresh(
                        identity = credentials.identity,
                        transport = maintained.transport,
                        poster = poster,
                        mutator = stores.identityMutator,
                        dial = RelayDial { refreshed ->
                            stageResult = tryTransport(refreshed, true)
                            if (stageResult == ImportStageResult.CLEAN) SyncOutcome.SUCCESS else SyncOutcome.RETRY
                        },
                        log = log,
                    )
                    stageResult
                }
                RelayTokenResult.ReconnectNeeded -> ImportStageResult.AUTH_STOP
                RelayTokenResult.Obsolete -> ImportStageResult.RETRY
            }
        }

        var outcome = when (val transport = credentials.transport) {
            is SyncTransport.Direct -> {
                var result = ImportStageResult.RETRY
                for (endpoint in credentials.directCandidates) {
                    if (control.isStopped) break
                    result = tryTransport(SyncTransport.Direct(endpoint), false)
                    if (result != ImportStageResult.RETRY || attempt.decided) break
                }
                result
            }
            is SyncTransport.Relay -> tryRelay(transport)
        }

        if (!control.isStopped && outcome == ImportStageResult.RETRY && credentials.transport is SyncTransport.Direct && !attempt.decided) {
            val relayTransport = relayFallbackTransport(
                identity = credentials.identity,
                relayLiveEligible = stores.identityMutator.accessSnapshot()?.let {
                    it.pairing == attemptPairing && it.relayLiveEligible
                } ?: false,
            )
            if (relayTransport != null) {
                outcome = tryRelay(relayTransport)
            }
        }
        return outcome
    } finally {
        attempt.finish()
    }
}
