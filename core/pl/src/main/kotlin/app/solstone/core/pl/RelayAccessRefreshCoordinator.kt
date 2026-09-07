// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.pl

import app.solstone.core.identity.AccessSnapshot
import app.solstone.core.identity.AccessMutationResult
import app.solstone.core.identity.IdentityMutator
import app.solstone.core.identity.PairingGeneration
import java.io.Closeable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

data class RelayAccessSnapshot(
    val instanceId: String,
    val caChainFingerprint: String,
    val clientCertFingerprint: String,
    val openClient: () -> PlHttpClient,
    val access: AccessSnapshot,
)

data class PendingClear(
    val pairingGen: PairingGeneration,
    val accessMutationGen: Long,
)

class RelayAccessRefreshCoordinator(
    private val mutator: IdentityMutator,
    executor: ExecutorService = Executors.newCachedThreadPool { r ->
        Thread(r, "relay-access-refresh").apply { isDaemon = true }
    },
    boundMillis: Long = 15_000L,
) : Closeable {
    private val job = CoalescingBoundedJob<RelayAccessSnapshot>(
        name = "relay-access-refresh",
        boundMillis = boundMillis,
        executor = executor,
    )

    @Volatile
    var pendingClear: PendingClear? = null
        internal set

    fun onUsableConnection(
        instanceId: String,
        caChainFingerprint: String,
        clientCertFingerprint: String,
        openClient: () -> PlHttpClient,
    ) {
        retryPendingClearIfMatching()

        val access = mutator.accessSnapshot() ?: return
        if (access.pairing != PairingGeneration(instanceId, clientCertFingerprint)) return
        val snapshot = RelayAccessSnapshot(
            instanceId = instanceId,
            caChainFingerprint = caChainFingerprint,
            clientCertFingerprint = clientCertFingerprint,
            openClient = openClient,
            access = access,
        )
        job.submit(snapshot) { snap, gen ->
            executeAccessJob(snap, gen)
        }
    }

    fun retryPendingClearIfMatching() {
        val pending = pendingClear ?: return
        val currentPairing = mutator.currentPairingGeneration()
        val currentGen = mutator.currentAccessMutationGen()
        if (currentPairing == pending.pairingGen && currentGen == pending.accessMutationGen) {
            val result = mutator.mutate(pending.pairingGen, pending.accessMutationGen) { current ->
                current.copy(
                    relayOrigin = null,
                    deviceToken = null,
                    expiresAt = null,
                )
            }
            when (result) {
                is AccessMutationResult.Applied -> {
                    pendingClear = null
                }
                is AccessMutationResult.PersistenceFailed,
                is AccessMutationResult.DurabilityUncertain -> {
                    // Keep pending clear
                }
                is AccessMutationResult.Conflict -> {
                    pendingClear = null
                }
            }
        } else {
            pendingClear = null
        }
    }

    private fun executeAccessJob(snap: RelayAccessSnapshot, gen: Long) {
        var client: PlHttpClient? = null
        try {
            if (gen != job.currentGeneration() || mutator.accessSnapshot() != snap.access) return
            client = snap.openClient()
            val result = fetchRelayAccess(client, snap.instanceId)
            val expectedPairing = PairingGeneration(snap.instanceId, snap.clientCertFingerprint)
            when (result) {
                is RelayAccessResponse.Ready -> {
                    synchronized(this) {
                        if (gen != job.currentGeneration() || mutator.currentPairingGeneration() != expectedPairing) {
                            return
                        }
                        val mutationResult = mutator.mutateIfCurrent(snap.access, { gen == job.currentGeneration() }) { current ->
                            current.copy(
                                relayOrigin = result.relayOrigin,
                                deviceToken = result.deviceToken,
                                expiresAt = result.expiresAt,
                            )
                        }
                        if (mutationResult is AccessMutationResult.Applied) {
                            pendingClear = null
                        }
                    }
                }
                is RelayAccessResponse.NotConfigured -> {
                    synchronized(this) {
                        if (gen != job.currentGeneration() || mutator.currentPairingGeneration() != expectedPairing) {
                            return
                        }
                        val mutationResult = mutator.clearRelayAccess(snap.access) { gen == job.currentGeneration() }
                        when (mutationResult) {
                            is AccessMutationResult.Applied -> {
                                pendingClear = null
                            }
                            is AccessMutationResult.PersistenceFailed,
                            is AccessMutationResult.DurabilityUncertain -> {
                                val disablingGen = when (mutationResult) {
                                    is AccessMutationResult.PersistenceFailed -> mutationResult.accessMutationGen
                                    is AccessMutationResult.DurabilityUncertain -> mutationResult.accessMutationGen
                                    else -> null
                                } ?: snap.access.generation
                                pendingClear = PendingClear(expectedPairing, disablingGen)
                            }
                            else -> {}
                        }
                    }
                }
                is RelayAccessResponse.Unavailable,
                is RelayAccessResponse.NotFound,
                is RelayAccessResponse.Malformed,
                is RelayAccessResponse.Failure -> {
                    // Preserve usable cache under unchanged pairing
                }
            }
        } catch (_: Exception) {
            // Preserve usable cache on failure/timeout
        } finally {
            try {
                (client as? Closeable)?.close()
            } catch (_: Exception) {
            }
        }
    }

    fun onConnectionLost() {
        job.bumpGeneration()
    }

    fun onIdentityChanged() {
        pendingClear = null
        job.bumpGeneration()
    }

    fun onPairingChanged() {
        pendingClear = null
        job.bumpGeneration()
    }

    override fun close() {
        job.close()
    }
}
