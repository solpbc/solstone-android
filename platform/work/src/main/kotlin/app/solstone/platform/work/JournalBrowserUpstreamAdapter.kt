// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.work

import app.solstone.core.identity.ClientCredential
import app.solstone.core.identity.ClientCredentialStore
import app.solstone.core.identity.IdentityMutator
import app.solstone.core.identity.IdentityStore
import app.solstone.core.pl.DirectEndpoint
import app.solstone.core.pl.EndpointStore
import app.solstone.core.pl.browser.BrowserHttpResponse
import app.solstone.core.pl.browser.JournalBrowserIdentityException
import app.solstone.core.pl.browser.JournalBrowserUpstream
import app.solstone.core.pl.browser.JournalBrowserUpstreamFactory
import app.solstone.platform.pl.transport.conscrypt.ConscryptPlHttpClient
import app.solstone.platform.pl.transport.conscrypt.openAuthenticatedClient
import app.solstone.platform.pl.transport.conscrypt.openRelaySyncClient

class JournalBrowserUpstreamAdapter(
    private val endpointStore: EndpointStore,
    private val credentialStore: ClientCredentialStore,
    private val identityStore: IdentityStore,
    private val mutator: IdentityMutator,
    private val directClientOpener: (DirectEndpoint, ClientCredential) -> JournalBrowserUpstream = { ep, cred ->
        ConscryptJournalBrowserUpstream(openAuthenticatedClient(ep, cred))
    },
    private val relayClientOpener: (String, String, String, ClientCredential) -> JournalBrowserUpstream = { origin, instId, tok, cred ->
        ConscryptJournalBrowserUpstream(openRelaySyncClient(origin, instId, tok, cred))
    },
) : JournalBrowserUpstreamFactory {

    override fun open(): JournalBrowserUpstream {
        val ready = when (val res = recoverSyncCredentials(endpointStore, credentialStore, identityStore, relayLiveEligible = true, mutator = mutator)) {
            is SyncCredentials.Ready -> res
            is SyncCredentials.NeedsRepair -> throw JournalBrowserIdentityException()
        }

        return try {
            when (val transport = ready.transport) {
                is SyncTransport.Direct -> directClientOpener(transport.endpoint, ready.credential)
                is SyncTransport.Relay -> relayClientOpener(transport.relayOrigin, transport.instanceId, transport.deviceToken, ready.credential)
            }
        } catch (t: Throwable) {
            if (isTrustRefusal(t) || classifyOpenerFailure(t) == OpenerFailureKind.TRUST_REFUSAL) {
                throw JournalBrowserIdentityException()
            }
            throw t
        }
    }

    fun isAccessStillCurrent(): Boolean {
        val ready = when (val res = recoverSyncCredentials(endpointStore, credentialStore, identityStore, relayLiveEligible = true, mutator = mutator)) {
            is SyncCredentials.Ready -> res
            is SyncCredentials.NeedsRepair -> return false
        }
        val access = mutator.accessSnapshot() ?: return false
        return transportAccessStillCurrent(ready.transport, ready.identity, access, mutator)
    }

    private class ConscryptJournalBrowserUpstream(
        private val client: ConscryptPlHttpClient,
    ) : JournalBrowserUpstream {
        override val isPoisoned: Boolean
            get() = client.isPoisoned

        override fun request(
            method: String,
            path: String,
            headers: Map<String, String>,
            body: ByteArray?,
        ): BrowserHttpResponse = client.requestBrowser(method, path, headers, body)

        override fun requestStreaming(
            method: String,
            path: String,
            headers: List<Pair<String, String>>,
            bodySource: app.solstone.core.pl.browser.BrowserRequestBodySource?,
            responseSink: app.solstone.core.pl.browser.BrowserResponseSink,
        ) {
            client.requestStreaming(method, path, headers, bodySource, responseSink)
        }

        override fun close() = client.close()
    }
}
