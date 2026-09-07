// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.identity

import app.solstone.core.model.PairedHome

data class PairingGeneration(
    val instanceId: String,
    val clientCertFingerprint: String,
)

sealed interface AccessMutationResult {
    data class Applied(val home: PairedHome, val accessMutationGen: Long) : AccessMutationResult
    data class Conflict(val reason: String) : AccessMutationResult
    data class PersistenceFailed(val cause: Throwable, val accessMutationGen: Long? = null) : AccessMutationResult
    data class DurabilityUncertain(val cause: Throwable, val accessMutationGen: Long? = null) : AccessMutationResult
}

enum class PersistenceIssue {
    PERSISTENCE_FAILED,
    DURABILITY_UNCERTAIN,
}

data class AccessSnapshot(val home: PairedHome, val generation: Long, val relayLiveEligible: Boolean) {
    val pairing: PairingGeneration get() = PairingGeneration(home.instanceId, home.clientCertFingerprint)
    override fun toString(): String = "AccessSnapshot(generation=$generation, relayLiveEligible=$relayLiveEligible)"
}

interface IdentityMutator {
    fun <T> withMutationBoundary(block: () -> T): T = synchronized(this) { block() }
    fun accessSnapshot(): AccessSnapshot? = current()?.let {
        AccessSnapshot(it, currentAccessMutationGen(), isRelayLiveEligible())
    }
    fun mutateIfCurrent(
        snapshot: AccessSnapshot,
        stillCurrent: () -> Boolean = { true },
        transform: (PairedHome) -> PairedHome,
    ): AccessMutationResult {
        if (!stillCurrent() || accessSnapshot() != snapshot) return AccessMutationResult.Conflict("obsolete access")
        return mutate(snapshot.pairing, snapshot.generation, transform)
    }
    fun clearRelayAccess(snapshot: AccessSnapshot, stillCurrent: () -> Boolean = { true }): AccessMutationResult {
        if (!stillCurrent() || accessSnapshot() != snapshot) return AccessMutationResult.Conflict("obsolete access")
        disableRelayLive()
        return mutate(snapshot.pairing, snapshot.generation) { it.copy(relayOrigin = null, deviceToken = null, expiresAt = null) }
    }

    fun current(): PairedHome?
    fun currentPairingGeneration(): PairingGeneration?
    fun currentAccessMutationGen(): Long
    fun isRelayLiveEligible(): Boolean
    fun disableRelayLive()
    fun installNewPairing(home: PairedHome): Boolean
    fun lastPersistenceIssue(): PersistenceIssue?
    fun mutate(
        expectedPairing: PairingGeneration,
        expectedAccessMutationGen: Long,
        transform: (PairedHome) -> PairedHome,
    ): AccessMutationResult
}
