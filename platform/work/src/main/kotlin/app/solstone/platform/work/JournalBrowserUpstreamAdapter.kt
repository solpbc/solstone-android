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

import app.solstone.core.identity.PairingPublisher
import app.solstone.core.identity.PairingLease

class JournalBrowserUpstreamAdapter(
    private val publisher: PairingPublisher,
    private val directClientOpener: (DirectEndpoint, ClientCredential) -> JournalBrowserUpstream = { ep, cred ->
        ConscryptJournalBrowserUpstream(openAuthenticatedClient(ep, cred))
    },
    private val relayClientOpener: (String, String, String, ClientCredential) -> JournalBrowserUpstream = { origin, instId, tok, cred ->
        ConscryptJournalBrowserUpstream(openRelaySyncClient(origin, instId, tok, cred))
    },
) : JournalBrowserUpstreamFactory {

    constructor(
        endpointStore: EndpointStore,
        credentialStore: ClientCredentialStore,
        identityStore: IdentityStore,
        mutator: IdentityMutator,
        directClientOpener: (DirectEndpoint, ClientCredential) -> JournalBrowserUpstream = { ep, cred ->
            ConscryptJournalBrowserUpstream(openAuthenticatedClient(ep, cred))
        },
        relayClientOpener: (String, String, String, ClientCredential) -> JournalBrowserUpstream = { origin, instId, tok, cred ->
            ConscryptJournalBrowserUpstream(openRelaySyncClient(origin, instId, tok, cred))
        },
    ) : this(
        publisher = object : PairingPublisher {
            override fun currentSnapshot(): app.solstone.core.identity.PairingGraphSnapshot {
                val ident = mutator.current() ?: return app.solstone.core.identity.PairingGraphSnapshot.Absent(0)
                val hasEp = endpointStore.load() != null
                return app.solstone.core.identity.PairingGraphSnapshot.Committed(
                    sequenceNumber = 1,
                    revisions = app.solstone.core.identity.GraphRevisions(1, 1, 1),
                    home = ident,
                    hasDirectEndpoint = hasEp,
                    directAssociated = hasEp,
                    relayLiveEligible = mutator.isRelayLiveEligible(),
                )
            }
            override fun subscribe(observer: (app.solstone.core.identity.PairingGraphSnapshot) -> Unit) = app.solstone.core.identity.SubscriptionHandle {}
            override fun <T> withMutationBoundary(block: () -> T) = block()

            override fun acquireDirectLease(): PairingLease.Direct? {
                val cred = credentialStore.load() ?: return null
                val ident = mutator.current() ?: return null
                val ep = endpointStore.load() ?: return null
                val snap = app.solstone.core.identity.PairingGraphSnapshot.Committed(
                    sequenceNumber = 1,
                    revisions = app.solstone.core.identity.GraphRevisions(1, 1, 1),
                    home = ident,
                    hasDirectEndpoint = true,
                    directAssociated = true,
                    relayLiveEligible = mutator.isRelayLiveEligible(),
                )
                return PairingLease.Direct(snap, cred, ep)
            }
            override fun acquireRelayLease(): PairingLease.Relay? {
                val cred = credentialStore.load() ?: return null
                val ident = mutator.current() ?: return null
                val origin = ident.relayOrigin ?: return null
                val token = ident.deviceToken ?: return null
                if (!mutator.isRelayLiveEligible()) return null
                val snap = app.solstone.core.identity.PairingGraphSnapshot.Committed(
                    sequenceNumber = 1,
                    revisions = app.solstone.core.identity.GraphRevisions(1, 1, 1),
                    home = ident,
                    hasDirectEndpoint = false,
                    directAssociated = false,
                    relayLiveEligible = true,
                )
                return PairingLease.Relay(snap, cred, origin, ident.instanceId, token)
            }
            override fun validateLease(lease: PairingLease): Boolean = true
            override fun installOrReplace(home: app.solstone.core.model.PairedHome, credential: ClientCredential, directEndpoint: app.solstone.core.model.DirectEndpoint?, isDirectAssociated: Boolean) = app.solstone.core.identity.GraphMutationResult.Conflict("compat")
            override fun updateRelayAccess(expectedPairing: app.solstone.core.identity.PairingGeneration, relayOrigin: String, deviceToken: String, expiresAt: String?) = app.solstone.core.identity.GraphMutationResult.Conflict("compat")
            override fun revokeRelayAccess(expectedPairing: app.solstone.core.identity.PairingGeneration) = app.solstone.core.identity.GraphMutationResult.Conflict("compat")
            override fun forget() = app.solstone.core.identity.GraphMutationResult.Conflict("compat")
            override fun associateDirectIfProven(expectedPairing: app.solstone.core.identity.PairingGeneration, endpoint: app.solstone.core.model.DirectEndpoint, proof: () -> Boolean) = false
        },
        directClientOpener = directClientOpener,
        relayClientOpener = relayClientOpener,
    )

    override fun open(): JournalBrowserUpstream {
        val directLease = publisher.acquireDirectLease()
        if (directLease != null) {
            if (!publisher.validateLease(directLease)) throw JournalBrowserIdentityException()
            return try {
                val opened = directClientOpener(app.solstone.core.pl.DirectEndpoint(directLease.endpoint.host, directLease.endpoint.port), directLease.credential)
                if (!publisher.validateLease(directLease)) {
                    runCatching { opened.close() }
                    throw JournalBrowserIdentityException()
                }
                opened
            } catch (t: Throwable) {
                if (isTrustRefusal(t) || classifyOpenerFailure(t) == OpenerFailureKind.TRUST_REFUSAL) {
                    throw JournalBrowserIdentityException()
                }
                throw t
            }
        }
        val relayLease = publisher.acquireRelayLease()
        if (relayLease != null) {
            if (!publisher.validateLease(relayLease)) throw JournalBrowserIdentityException()
            return try {
                val opened = relayClientOpener(relayLease.relayOrigin, relayLease.instanceId, relayLease.deviceToken, relayLease.credential)
                if (!publisher.validateLease(relayLease)) {
                    runCatching { opened.close() }
                    throw JournalBrowserIdentityException()
                }
                opened
            } catch (t: Throwable) {
                if (isTrustRefusal(t) || classifyOpenerFailure(t) == OpenerFailureKind.TRUST_REFUSAL) {
                    throw JournalBrowserIdentityException()
                }
                throw t
            }
        }
        throw JournalBrowserIdentityException()
    }

    fun isAccessStillCurrent(): Boolean {
        val snap = publisher.currentSnapshot() as? app.solstone.core.identity.PairingGraphSnapshot.Committed ?: return false
        return snap.isDirectEligible || snap.isRelayEligible
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
