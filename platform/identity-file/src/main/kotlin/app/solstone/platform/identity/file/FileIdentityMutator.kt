// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.identity.file

import app.solstone.core.identity.AccessMutationResult
import app.solstone.core.identity.IdentityMutator
import app.solstone.core.identity.IdentityStore
import app.solstone.core.identity.PairingGeneration
import app.solstone.core.identity.PersistenceIssue
import app.solstone.core.model.PairedHome
import java.util.concurrent.atomic.AtomicLong

class FileIdentityMutator(
    private val store: IdentityStore,
) : IdentityMutator {
    private val lock = Any()
    private val accessMutationGen = AtomicLong(0)
    @Volatile private var relayLiveEligible: Boolean = false
    private var accepted: PairedHome? = null
    private var uncertainCandidate: PairedHome? = null
    private var lastPersistenceIssue: PersistenceIssue? = null

    init {
        val initial = runCatching { store.load() }.getOrNull()
        accepted = initial
        relayLiveEligible = initial?.relayOrigin != null && initial.deviceToken != null
    }

    private fun reconcileLocked() {
        val disk = runCatching { store.load() }.getOrNull()
        when {
            disk == null -> {
                accepted = null
                uncertainCandidate = null
                relayLiveEligible = false
            }
            disk == accepted -> {
                uncertainCandidate = null
            }
            uncertainCandidate != null && disk == uncertainCandidate -> {
                // Uncertain disk state is not promoted to accepted; accepted stays old and live stays false
            }
            else -> {
                accepted = disk
                uncertainCandidate = null
                relayLiveEligible = false
            }
        }
    }

    override fun current(): PairedHome? = synchronized(lock) {
        reconcileLocked()
        accepted
    }

    override fun currentPairingGeneration(): PairingGeneration? = synchronized(lock) {
        reconcileLocked()
        val home = accepted ?: return null
        PairingGeneration(home.instanceId, home.clientCertFingerprint)
    }

    override fun currentAccessMutationGen(): Long = accessMutationGen.get()

    override fun isRelayLiveEligible(): Boolean = relayLiveEligible

    override fun disableRelayLive() {
        relayLiveEligible = false
    }

    override fun lastPersistenceIssue(): PersistenceIssue? = synchronized(lock) {
        lastPersistenceIssue
    }

    override fun installNewPairing(home: PairedHome): Boolean = synchronized(lock) {
        reconcileLocked()
        try {
            store.save(home)
        } catch (t: Throwable) {
            val reloaded = runCatching { store.load() }.getOrNull()
            if (reloaded == home) {
                uncertainCandidate = home
                lastPersistenceIssue = PersistenceIssue.DURABILITY_UNCERTAIN
                relayLiveEligible = false
            } else {
                lastPersistenceIssue = PersistenceIssue.PERSISTENCE_FAILED
                relayLiveEligible = false
            }
            return false
        }
        accepted = home
        uncertainCandidate = null
        lastPersistenceIssue = null
        accessMutationGen.incrementAndGet()
        relayLiveEligible = home.relayOrigin != null && home.deviceToken != null
        true
    }

    override fun mutate(
        expectedPairing: PairingGeneration,
        expectedAccessMutationGen: Long,
        transform: (PairedHome) -> PairedHome,
    ): AccessMutationResult = synchronized(lock) {
        reconcileLocked()
        val current = accepted ?: return AccessMutationResult.Conflict("identity not present")
        val currentPairing = PairingGeneration(current.instanceId, current.clientCertFingerprint)
        if (currentPairing != expectedPairing) {
            return AccessMutationResult.Conflict("pairing generation mismatch: expected $expectedPairing, current $currentPairing")
        }
        if (accessMutationGen.get() != expectedAccessMutationGen) {
            return AccessMutationResult.Conflict("access mutation generation mismatch: expected $expectedAccessMutationGen, current ${accessMutationGen.get()}")
        }

        val transformed = transform(current)
        try {
            store.save(transformed)
        } catch (t: Throwable) {
            if (transformed.relayOrigin == null || transformed.deviceToken == null) {
                relayLiveEligible = false
            }
            // Check whether new bytes became visible (e.g. post-rename exception)
            val reloaded = runCatching { store.load() }.getOrNull()
            return if (reloaded == transformed) {
                uncertainCandidate = transformed
                lastPersistenceIssue = PersistenceIssue.DURABILITY_UNCERTAIN
                relayLiveEligible = false
                AccessMutationResult.DurabilityUncertain(t)
            } else {
                lastPersistenceIssue = PersistenceIssue.PERSISTENCE_FAILED
                AccessMutationResult.PersistenceFailed(t)
            }
        }

        accepted = transformed
        uncertainCandidate = null
        lastPersistenceIssue = null
        val newGen = accessMutationGen.incrementAndGet()
        relayLiveEligible = transformed.relayOrigin != null && transformed.deviceToken != null
        AccessMutationResult.Applied(transformed, newGen)
    }
}
