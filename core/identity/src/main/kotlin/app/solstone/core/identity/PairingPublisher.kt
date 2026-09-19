// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.identity

import app.solstone.core.model.DirectEndpoint
import app.solstone.core.model.IdentityState
import app.solstone.core.model.PairedHome

/**
 * Process-local monotonic revisions tracking three distinct mutation domains.
 * Revisions strictly increment and never roll back across A -> Absent -> A or A -> B -> A.
 */
data class GraphRevisions(
    val pairingRevision: Long,
    val directRouteRevision: Long,
    val relayAccessRevision: Long,
) {
    override fun toString(): String =
        "GraphRevisions(p=$pairingRevision, d=$directRouteRevision, r=$relayAccessRevision)"
}

/**
 * Canonical pairing graph snapshot type.
 */
sealed interface PairingGraphSnapshot {
    val sequenceNumber: Long

    data class Absent(
        override val sequenceNumber: Long,
    ) : PairingGraphSnapshot {
        override fun toString(): String = "PairingGraphSnapshot.Absent(seq=$sequenceNumber)"
    }

    data class Uncertain(
        override val sequenceNumber: Long,
        val reason: PersistenceIssue,
    ) : PairingGraphSnapshot {
        override fun toString(): String =
            "PairingGraphSnapshot.Uncertain(seq=$sequenceNumber, reason=$reason)"
    }

    data class Committed(
        override val sequenceNumber: Long,
        val revisions: GraphRevisions,
        val home: PairedHome,
        val hasDirectEndpoint: Boolean,
        val directAssociated: Boolean,
        val relayLiveEligible: Boolean,
    ) : PairingGraphSnapshot {
        val pairing: PairingGeneration
            get() = PairingGeneration(home.instanceId, home.clientCertFingerprint)
        val isDirectEligible: Boolean
            get() = hasDirectEndpoint && directAssociated && home.state == IdentityState.PAIRED
        val isRelayEligible: Boolean
            get() = relayLiveEligible && home.relayOrigin != null && home.deviceToken != null && home.state == IdentityState.PAIRED

        override fun toString(): String =
            "PairingGraphSnapshot.Committed(seq=$sequenceNumber, rev=$revisions, state=${home.state}, direct=$isDirectEligible, relay=$isRelayEligible)"
    }
}

/**
 * Ephemeral pairing lease capability for transport openers.
 */
sealed interface PairingLease {
    val snapshot: PairingGraphSnapshot.Committed
    val credential: ClientCredential

    data class Direct(
        override val snapshot: PairingGraphSnapshot.Committed,
        override val credential: ClientCredential,
        val endpoint: DirectEndpoint,
    ) : PairingLease {
        override fun toString(): String =
            "PairingLease.Direct(seq=${snapshot.sequenceNumber}, rev=${snapshot.revisions})"
    }

    data class Relay(
        override val snapshot: PairingGraphSnapshot.Committed,
        override val credential: ClientCredential,
        val relayOrigin: String,
        val instanceId: String,
        val deviceToken: String,
    ) : PairingLease {
        override fun toString(): String =
            "PairingLease.Relay(seq=${snapshot.sequenceNumber}, rev=${snapshot.revisions})"
    }
}

/**
 * Results of atomic mutations against the pairing publisher.
 */
sealed interface GraphMutationResult {
    data class Applied(val snapshot: PairingGraphSnapshot.Committed) : GraphMutationResult {
        override fun toString(): String = "GraphMutationResult.Applied(seq=${snapshot.sequenceNumber})"
    }
    data class Cleared(val snapshot: PairingGraphSnapshot.Absent) : GraphMutationResult {
        override fun toString(): String = "GraphMutationResult.Cleared(seq=${snapshot.sequenceNumber})"
    }
    data class Conflict(val reason: String) : GraphMutationResult {
        override fun toString(): String = "GraphMutationResult.Conflict(reason=$reason)"
    }
    data class PersistenceFailed(val cause: Throwable) : GraphMutationResult {
        override fun toString(): String = "GraphMutationResult.PersistenceFailed(${cause.javaClass.simpleName})"
    }
    data class DurabilityUncertain(val cause: Throwable) : GraphMutationResult {
        override fun toString(): String = "GraphMutationResult.DurabilityUncertain(${cause.javaClass.simpleName})"
    }
}

/**
 * Typed inspection result for individual artifact stores.
 */
sealed interface StoreInspectResult<out T> {
    data object Missing : StoreInspectResult<Nothing> {
        override fun toString(): String = "StoreInspectResult.Missing"
    }
    data class Ready<out T>(val value: T) : StoreInspectResult<T> {
        override fun toString(): String = "StoreInspectResult.Ready"
    }
    data class Unreadable(val issue: PersistenceIssue, val detail: String) : StoreInspectResult<Nothing> {
        override fun toString(): String = "StoreInspectResult.Unreadable($issue)"
    }
}


/**
 * Idempotent subscription cancellation handle.
 */
fun interface SubscriptionHandle {
    fun cancel()
}

/**
 * Named durable transaction steps for recovery testing and crash injection.
 */
enum class DurableTxnStep {
    STAGING_WRITE,
    RENAME_REPLACE,
    DURABILITY_ACK,
    READ_BACK,
    DURABLE_COMMIT_DECISION,
    CLEANUP,
}

fun interface DurableTxnHook {
    fun onStep(step: DurableTxnStep, operation: String)
}

/**
 * Canonical pairing authority and mutation boundary.
 */
interface PairingPublisher {
    fun currentSnapshot(): PairingGraphSnapshot
    fun subscribe(observer: (PairingGraphSnapshot) -> Unit): SubscriptionHandle
    fun <T> withMutationBoundary(block: () -> T): T

    fun acquireDirectLease(): PairingLease.Direct?
    fun acquireRelayLease(): PairingLease.Relay?
    fun validateLease(lease: PairingLease): Boolean

    fun installOrReplace(
        home: PairedHome,
        credential: ClientCredential,
        directEndpoint: DirectEndpoint?,
        isDirectAssociated: Boolean,
    ): GraphMutationResult

    fun updateRelayAccess(
        expectedPairing: PairingGeneration,
        relayOrigin: String,
        deviceToken: String,
        expiresAt: String?,
    ): GraphMutationResult

    fun revokeRelayAccess(
        expectedPairing: PairingGeneration,
    ): GraphMutationResult

    fun forget(): GraphMutationResult

    fun associateDirectIfProven(
        expectedPairing: PairingGeneration,
        endpoint: DirectEndpoint,
        proof: () -> Boolean,
    ): Boolean
}
