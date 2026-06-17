// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.work

import app.solstone.core.identity.ClientCredential
import app.solstone.core.identity.ClientCredentialStore
import app.solstone.core.identity.IdentityStore
import app.solstone.core.model.IdentityState
import app.solstone.core.pl.DirectEndpoint
import app.solstone.core.pl.EndpointStore

sealed interface SyncCredentials {
    data class Ready(
        val endpoint: DirectEndpoint,
        val credential: ClientCredential,
        val handle: String,
    ) : SyncCredentials

    data class NeedsRepair(val reason: String) : SyncCredentials
}

fun recoverSyncCredentials(
    endpointStore: EndpointStore,
    credentialStore: ClientCredentialStore,
    identityStore: IdentityStore,
): SyncCredentials {
    val endpoint = endpointStore.load() ?: return SyncCredentials.NeedsRepair("missing endpoint")
    val credential = credentialStore.load() ?: return SyncCredentials.NeedsRepair("missing credential")
    val identity = identityStore.load() ?: return SyncCredentials.NeedsRepair("missing identity")
    val handle = identity.observerHandle ?: return SyncCredentials.NeedsRepair("missing observer handle")
    if (identity.state != IdentityState.PAIRED) {
        return SyncCredentials.NeedsRepair("identity not paired")
    }
    return SyncCredentials.Ready(endpoint, credential, handle)
}
