// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.pl.transport.conscrypt

import app.solstone.core.identity.ClientCredential
import app.solstone.core.identity.ClientCredentialStore
import app.solstone.core.identity.GraphMutationResult
import app.solstone.core.identity.GraphRevisions
import app.solstone.core.identity.IdentityStore
import app.solstone.core.identity.PairingGeneration
import app.solstone.core.identity.PairingGraphSnapshot
import app.solstone.core.identity.PairingLease
import app.solstone.core.identity.PairingPublisher
import app.solstone.core.identity.SubscriptionHandle
import app.solstone.core.model.DirectEndpoint
import app.solstone.core.model.PairedHome
import app.solstone.core.pl.EndpointStore
import java.util.concurrent.atomic.AtomicLong

class FakePairingPublisher(
    private val identityStore: IdentityStore,
    private val credentialStore: ClientCredentialStore,
    private val endpointStore: EndpointStore? = null,
    private val failPublication: Boolean = false,
) : PairingPublisher {
    private val seq = AtomicLong(0)
    private var pRev = 1L
    private var dRev = 1L
    private var rRev = 1L

    override fun currentSnapshot(): PairingGraphSnapshot {
        val home = identityStore.load() ?: return PairingGraphSnapshot.Absent(seq.incrementAndGet())
        val ep = endpointStore?.load()
        return PairingGraphSnapshot.Committed(
            sequenceNumber = seq.incrementAndGet(),
            revisions = GraphRevisions(pRev, dRev, rRev),
            home = home,
            hasDirectEndpoint = ep != null,
            directAssociated = ep != null,
            relayLiveEligible = home.relayOrigin != null && home.deviceToken != null,
        )
    }

    override fun <T> withMutationBoundary(block: () -> T): T = block()

    override fun installOrReplace(
        home: PairedHome,
        credential: ClientCredential,
        directEndpoint: DirectEndpoint?,
        isDirectAssociated: Boolean,
    ): GraphMutationResult {
        if (failPublication) {
            return GraphMutationResult.PersistenceFailed(java.io.IOException("simulated publisher failure"))
        }
        val oldHome = identityStore.load()
        val oldCred = credentialStore.load()
        val oldEp = endpointStore?.load()
        try {
            credentialStore.save(credential)
            identityStore.save(home)
            if (directEndpoint != null && endpointStore != null) {
                endpointStore.save(app.solstone.core.pl.DirectEndpoint(directEndpoint.host, directEndpoint.port))
            } else {
                endpointStore?.clear()
            }
            pRev++
            dRev++
            rRev++
            val snap = currentSnapshot()
            return if (snap is PairingGraphSnapshot.Committed) {
                GraphMutationResult.Applied(snap)
            } else {
                GraphMutationResult.PersistenceFailed(IllegalStateException("identity not committed"))
            }
        } catch (e: Exception) {
            if (oldCred != null) credentialStore.save(oldCred) else credentialStore.clear()
            if (oldHome != null) identityStore.save(oldHome) else identityStore.clear()
            if (oldEp != null) endpointStore?.save(oldEp) else endpointStore?.clear()
            return GraphMutationResult.PersistenceFailed(e)
        }
    }

    override fun updateRelayAccess(
        expectedPairing: PairingGeneration,
        relayOrigin: String,
        deviceToken: String,
        expiresAt: String?,
    ): GraphMutationResult {
        val cur = identityStore.load() ?: return GraphMutationResult.Conflict("missing identity")
        if (cur.instanceId != expectedPairing.instanceId || cur.clientCertFingerprint != expectedPairing.clientCertFingerprint) {
            return GraphMutationResult.Conflict("pairing mismatch")
        }
        val updated = cur.copy(relayOrigin = relayOrigin, deviceToken = deviceToken, expiresAt = expiresAt)
        identityStore.save(updated)
        rRev++
        val snap = currentSnapshot()
        return if (snap is PairingGraphSnapshot.Committed) {
            GraphMutationResult.Applied(snap)
        } else {
            GraphMutationResult.PersistenceFailed(IllegalStateException("relay access not committed"))
        }
    }

    override fun revokeRelayAccess(expectedPairing: PairingGeneration): GraphMutationResult {
        val cur = identityStore.load() ?: return GraphMutationResult.Conflict("missing identity")
        val updated = cur.copy(relayOrigin = null, deviceToken = null, expiresAt = null)
        identityStore.save(updated)
        rRev++
        val snap = currentSnapshot()
        return if (snap is PairingGraphSnapshot.Committed) {
            GraphMutationResult.Applied(snap)
        } else {
            GraphMutationResult.PersistenceFailed(IllegalStateException("revoke not committed"))
        }
    }

    override fun forget(): GraphMutationResult {
        credentialStore.clear()
        identityStore.clear()
        endpointStore?.clear()
        return GraphMutationResult.Cleared(PairingGraphSnapshot.Absent(seq.incrementAndGet()))
    }

    override fun associateDirectIfProven(expectedPairing: PairingGeneration, endpoint: DirectEndpoint, proof: () -> Boolean): Boolean {
        if (!proof()) return false
        endpointStore?.save(app.solstone.core.pl.DirectEndpoint(endpoint.host, endpoint.port))
        dRev++
        return true
    }

    override fun acquireDirectLease(): PairingLease.Direct? {
        val snap = currentSnapshot() as? PairingGraphSnapshot.Committed ?: return null
        val cred = credentialStore.load() ?: return null
        val ep = endpointStore?.load() ?: return null
        return PairingLease.Direct(snap, cred, DirectEndpoint(ep.host, ep.port))
    }

    override fun acquireRelayLease(): PairingLease.Relay? {
        val snap = currentSnapshot() as? PairingGraphSnapshot.Committed ?: return null
        val cred = credentialStore.load() ?: return null
        val origin = snap.home.relayOrigin ?: return null
        val token = snap.home.deviceToken ?: return null
        return PairingLease.Relay(snap, cred, origin, snap.home.instanceId, token)
    }

    override fun validateLease(lease: PairingLease): Boolean = true

    override fun subscribe(observer: (PairingGraphSnapshot) -> Unit): SubscriptionHandle =
        object : SubscriptionHandle {
            override fun cancel() {}
        }
}
