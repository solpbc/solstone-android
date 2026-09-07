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

fun relayFallbackTransport(
    identity: PairedHome,
    relayLiveEligible: Boolean,
): SyncTransport.Relay? {
    if (!relayLiveEligible) return null
    val origin = identity.relayOrigin ?: return null
    val token = identity.deviceToken ?: return null
    return SyncTransport.Relay(origin, identity.instanceId, token)
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

enum class OpenerFailureKind {
    TRUST_REFUSAL,
    AVAILABILITY,
    NONE,
}

fun isTrustRefusal(t: Throwable): Boolean {
    var curr: Throwable? = t
    while (curr != null) {
        if (curr is javax.net.ssl.SSLException ||
            curr is java.security.cert.CertificateException ||
            curr is app.solstone.core.crypto.CaPinException
        ) {
            return true
        }
        curr = curr.cause
    }
    return false
}

fun classifyOpenerFailure(t: Throwable): OpenerFailureKind = when {
    isTrustRefusal(t) -> OpenerFailureKind.TRUST_REFUSAL
    t is java.io.IOException -> OpenerFailureKind.AVAILABILITY
    else -> OpenerFailureKind.NONE
}
