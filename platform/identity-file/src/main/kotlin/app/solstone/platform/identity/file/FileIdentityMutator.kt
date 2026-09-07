// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.identity.file

import app.solstone.core.identity.AccessMutationResult
import app.solstone.core.identity.IdentityMutator
import app.solstone.core.identity.IdentityStore
import app.solstone.core.identity.PairingGeneration
import app.solstone.core.model.PairedHome
import java.util.concurrent.atomic.AtomicLong

class FileIdentityMutator(
    private val store: IdentityStore,
) : IdentityMutator {
    private val lock = Any()
    private val accessMutationGen = AtomicLong(0)
    @Volatile private var relayLiveEligible: Boolean = false
    init {
        val initial = store.load()
        relayLiveEligible = initial?.relayOrigin != null && initial.deviceToken != null
    }

    override fun current(): PairedHome? = synchronized(lock) {
        store.load()
    }

    override fun currentPairingGeneration(): PairingGeneration? = synchronized(lock) {
        val home = store.load() ?: return null
        PairingGeneration(home.instanceId, home.clientCertFingerprint)
    }

    override fun currentAccessMutationGen(): Long = accessMutationGen.get()

    override fun isRelayLiveEligible(): Boolean = relayLiveEligible

    override fun disableRelayLive() {
        relayLiveEligible = false
    }

    override fun installNewPairing(home: PairedHome): Boolean {
        synchronized(lock) {
            store.save(home)
            accessMutationGen.incrementAndGet()
            relayLiveEligible = home.relayOrigin != null && home.deviceToken != null
        }
        return true
    }

    override fun mutate(
        expectedPairing: PairingGeneration,
        expectedAccessMutationGen: Long,
        transform: (PairedHome) -> PairedHome,
    ): AccessMutationResult = synchronized(lock) {
        val current = store.load() ?: return AccessMutationResult.Conflict("identity not present")
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
                AccessMutationResult.DurabilityUncertain(t)
            } else {
                AccessMutationResult.PersistenceFailed(t)
            }
        }

        val newGen = accessMutationGen.incrementAndGet()
        if (transformed.relayOrigin != null && transformed.deviceToken != null) {
            relayLiveEligible = true
        }
        AccessMutationResult.Applied(transformed, newGen)
    }
}
