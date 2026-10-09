// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.pl

import app.solstone.core.identity.PairingGraphSnapshot
import app.solstone.core.identity.PairingLease
import app.solstone.core.identity.PairingPublisher
import java.io.Closeable
import java.io.IOException
import javax.net.ssl.SSLException

/** Open each saved address in order, then relay, authenticating each attempt. */
fun <T> openPairingClient(
    publisher: PairingPublisher,
    opener: (PairingLease) -> T,
): T where T : PlHttpClient, T : Closeable {
    val candidates = publisher.acquireDirectLeases() + listOfNotNull(publisher.acquireRelayLease())
    var last: IOException? = null
    for (lease in candidates) {
        if (!leaseStillAuthorized(publisher, lease)) throw IOException("pairing changed")
        val client = try {
            opener(lease)
        } catch (e: IOException) {
            if (isPairingTrustRefusal(e)) throw e
            last = e
            continue
        }
        try {
            if (!leaseStillAuthorized(publisher, lease)) throw IOException("pairing changed")
            if (lease is PairingLease.Direct && !lease.snapshot.directAssociated) {
                if (!publisher.associateDirectIfProven(lease.snapshot.pairing, lease.endpoint) { true }) {
                    throw IOException("direct route association failed")
                }
            }
            val current = publisher.currentSnapshot() as? PairingGraphSnapshot.Committed
                ?: throw IOException("pairing changed")
            if (current.pairing != lease.snapshot.pairing || current.revisions.pairingRevision != lease.snapshot.revisions.pairingRevision) {
                throw IOException("pairing changed")
            }
            refreshDirectEndpoints(client, publisher, current, (lease as? PairingLease.Direct)?.endpoint)
            val after = publisher.currentSnapshot() as? PairingGraphSnapshot.Committed
            if (after == null || after.pairing != current.pairing ||
                after.revisions.pairingRevision != current.revisions.pairingRevision ||
                (lease is PairingLease.Relay && !leaseStillAuthorized(publisher, lease))
            ) {
                throw IOException("pairing changed")
            }
            return client
        } catch (e: Exception) {
            runCatching { client.close() }
            throw e
        }
    }
    throw last ?: IOException("missing endpoint and relay credentials")
}

/** Relay expiry metadata can renew during dialing without changing authorization. */
private fun leaseStillAuthorized(publisher: PairingPublisher, lease: PairingLease): Boolean {
    if (lease !is PairingLease.Relay) return publisher.validateLease(lease)
    val current = publisher.currentSnapshot() as? PairingGraphSnapshot.Committed ?: return false
    return current.pairing == lease.snapshot.pairing &&
        current.revisions.pairingRevision == lease.snapshot.revisions.pairingRevision &&
        current.isRelayEligible &&
        current.home.relayOrigin == lease.relayOrigin &&
        current.home.deviceToken == lease.deviceToken
}

fun isPairingTrustRefusal(t: Throwable): Boolean {
    var cause: Throwable? = t
    while (cause != null) {
        if (cause is SSLException || cause is java.security.cert.CertificateException || cause is app.solstone.core.crypto.CaPinException) return true
        cause = cause.cause
    }
    return false
}
