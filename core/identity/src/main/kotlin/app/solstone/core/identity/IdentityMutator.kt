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
    data class PersistenceFailed(val cause: Throwable) : AccessMutationResult
    data class DurabilityUncertain(val cause: Throwable) : AccessMutationResult
}

enum class PersistenceIssue {
    PERSISTENCE_FAILED,
    DURABILITY_UNCERTAIN,
}

interface IdentityMutator {
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
