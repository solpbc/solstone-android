// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.work

import app.solstone.core.identity.PairingLease
import app.solstone.core.identity.PairingPublisher
import app.solstone.core.pl.ClientRevokeResult
import app.solstone.core.pl.DirectEndpoint
import app.solstone.core.pl.revokeClient
import app.solstone.platform.pl.transport.conscrypt.ConscryptPlHttpClient
import app.solstone.platform.pl.transport.conscrypt.openAuthenticatedClient
import app.solstone.platform.pl.transport.conscrypt.openRelaySyncClient

/** What the journal side of an unpair did, so the owner can be told which half happened. */
enum class JournalRevokeOutcome {
    /** The journal no longer has a record of this device. */
    REMOVED,

    /** The journal could not be reached, or refused. Its record is still there. */
    UNREACHED,
}

/**
 * Remove this device's own record from the journal, before the local pairing is dropped.
 *
 * ⚠ Order matters and is not incidental: the credential this call authenticates with is the one
 * `forget()` is about to delete, so the journal half has to happen first or it cannot happen at
 * all. ⛔ And it must never block the local half — an owner who has lost their journal for good
 * still gets to unpair; they are told the journal kept its record rather than refused.
 *
 * The identifier is this device's own client-certificate digest, which is what the journal keys
 * its record by: the pairing handshake already refuses unless both sides agree on it, so asking
 * to remove that identifier is asking to remove itself.
 */
fun revokeThisDeviceOnJournal(
    publisher: PairingPublisher,
    openDirect: (DirectEndpoint, app.solstone.core.identity.ClientCredential) -> ConscryptPlHttpClient =
        { endpoint, credential -> openAuthenticatedClient(endpoint, credential) },
    openRelay: (PairingLease.Relay) -> ConscryptPlHttpClient = { lease ->
        openRelaySyncClient(lease.relayOrigin, lease.instanceId, lease.deviceToken, lease.credential)
    },
): JournalRevokeOutcome {
    val direct = publisher.acquireDirectLease()
    val relay = if (direct == null) publisher.acquireRelayLease() else null
    val cid = direct?.snapshot?.home?.clientCertFingerprint
        ?: relay?.snapshot?.home?.clientCertFingerprint
        ?: return JournalRevokeOutcome.UNREACHED
    var client: ConscryptPlHttpClient? = null
    return try {
        val opened = when {
            direct != null -> openDirect(
                DirectEndpoint(direct.endpoint.host, direct.endpoint.port),
                direct.credential,
            )
            else -> openRelay(relay as PairingLease.Relay)
        }
        client = opened
        when (revokeClient(opened, cid)) {
            // Already gone is the state the owner asked for, so it is not a failure to report.
            ClientRevokeResult.Removed, ClientRevokeResult.NotFound -> JournalRevokeOutcome.REMOVED
            is ClientRevokeResult.Failure -> JournalRevokeOutcome.UNREACHED
        }
    } catch (_: Throwable) {
        JournalRevokeOutcome.UNREACHED
    } finally {
        runCatching { client?.close() }
    }
}
