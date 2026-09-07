// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.work

import app.solstone.core.identity.ClientCredential
import app.solstone.core.identity.ClientCredentialStore
import app.solstone.core.identity.IdentityStore
import app.solstone.core.model.IdentityState
import app.solstone.core.model.PairedHome
import app.solstone.core.pl.DirectEndpoint
import app.solstone.core.pl.EndpointStore

sealed interface SyncTransport {
    data class Direct(val endpoint: DirectEndpoint) : SyncTransport
    data class Relay(val relayOrigin: String, val instanceId: String, val deviceToken: String) : SyncTransport
}

sealed interface SyncCredentials {
    data class Ready(
        val transport: SyncTransport,
        val credential: ClientCredential,
        val identity: PairedHome,
    ) : SyncCredentials

    data class NeedsRepair(val reason: String) : SyncCredentials
}

fun selectSyncTransport(
    identity: PairedHome,
    endpointStore: EndpointStore,
    relayLiveEligible: Boolean = true,
): SyncTransport? {
    val endpoint = endpointStore.load()
    if (endpoint != null) {
        return SyncTransport.Direct(endpoint)
    }
    val relayOrigin = identity.relayOrigin
    val deviceToken = identity.deviceToken
    if (relayLiveEligible && relayOrigin != null && deviceToken != null) {
        return SyncTransport.Relay(relayOrigin, identity.instanceId, deviceToken)
    }
    return null
}

fun recoverSyncCredentials(
    endpointStore: EndpointStore,
    credentialStore: ClientCredentialStore,
    identityStore: IdentityStore,
    relayLiveEligible: Boolean = true,
): SyncCredentials {
    val credential = credentialStore.load() ?: return SyncCredentials.NeedsRepair("missing credential")
    val identity = identityStore.load() ?: return SyncCredentials.NeedsRepair("missing identity")
    if (identity.state != IdentityState.PAIRED) {
        return SyncCredentials.NeedsRepair("identity not paired")
    }
    val transport = selectSyncTransport(identity, endpointStore, relayLiveEligible)
        ?: return SyncCredentials.NeedsRepair("missing endpoint and relay credentials")
    return SyncCredentials.Ready(transport, credential, identity)
}
