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
import app.solstone.core.identity.PairingGraphSnapshot
import app.solstone.core.pl.HandshakeGuard
import app.solstone.core.pl.JournalAttempt
import app.solstone.core.pl.JournalVerdictListener

/**
 * Opens the journal browser's upstream connections, one attempt per connection.
 *
 * Every open is an attempt under the refusal rule shared with sync and the status probe: each saved
 * address, then the relay, with a peer that is not the paired journal never ending the walk. The
 * journal's verdict arrives on the connection's first read, so each upstream carries its attempt.
 * Only a stop routes the browser to pairing repair ([JournalBrowserIdentityException]); a 46, a
 * refusal below the limit, an outer TLS failure or an unknown journal is an ordinary failure.
 */
class JournalBrowserUpstreamAdapter(
    private val publisher: PairingPublisher,
    private val guard: HandshakeGuard? = null,
    private val directClientOpener: (DirectEndpoint, ClientCredential, JournalVerdictListener) -> JournalBrowserUpstream = { ep, cred, listener ->
        ConscryptJournalBrowserUpstream(openAuthenticatedClient(ep, cred, verdictListener = listener))
    },
    private val relayClientOpener: (String, String, String, ClientCredential, JournalVerdictListener) -> JournalBrowserUpstream = { origin, instId, tok, cred, listener ->
        ConscryptJournalBrowserUpstream(openRelaySyncClient(origin, instId, tok, cred, verdictListener = listener))
    },
    private val dialEvents: app.solstone.core.pl.DialEventLog? = DialDiagnostics.events,
) : JournalBrowserUpstreamFactory {

    constructor(
        endpointStore: EndpointStore,
        credentialStore: ClientCredentialStore,
        identityStore: IdentityStore,
        mutator: IdentityMutator,
        guard: HandshakeGuard? = null,
        directClientOpener: (DirectEndpoint, ClientCredential, JournalVerdictListener) -> JournalBrowserUpstream = { ep, cred, listener ->
            ConscryptJournalBrowserUpstream(openAuthenticatedClient(ep, cred, verdictListener = listener))
        },
        relayClientOpener: (String, String, String, ClientCredential, JournalVerdictListener) -> JournalBrowserUpstream = { origin, instId, tok, cred, listener ->
            ConscryptJournalBrowserUpstream(openRelaySyncClient(origin, instId, tok, cred, verdictListener = listener))
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
                    directEndpoints = endpointStore.loadAll(),
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
                    directEndpoints = endpointStore.loadAll(),
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
            override fun installOrReplace(home: app.solstone.core.model.PairedHome, credential: ClientCredential, directEndpoint: app.solstone.core.model.DirectEndpoint?, isDirectAssociated: Boolean, provenance: app.solstone.core.identity.PairingProvenance, directEndpoints: List<app.solstone.core.model.DirectEndpoint>) = app.solstone.core.identity.GraphMutationResult.Conflict("compat")
            override fun replaceDirectEndpoints(expected: app.solstone.core.identity.PairingGraphSnapshot.Committed, endpoints: List<app.solstone.core.model.DirectEndpoint>) = app.solstone.core.identity.GraphMutationResult.Conflict("unsupported")
            override fun updateRelayAccess(expectedPairing: app.solstone.core.identity.PairingGeneration, relayOrigin: String, deviceToken: String, expiresAt: String?) = app.solstone.core.identity.GraphMutationResult.Conflict("compat")
            override fun revokeRelayAccess(expectedPairing: app.solstone.core.identity.PairingGeneration) = app.solstone.core.identity.GraphMutationResult.Conflict("compat")
            override fun forget() = app.solstone.core.identity.GraphMutationResult.Conflict("compat")
            override fun associateDirectIfProven(expectedPairing: app.solstone.core.identity.PairingGeneration, endpoint: app.solstone.core.model.DirectEndpoint, proof: () -> Boolean) = false
        },
        guard = guard,
        directClientOpener = directClientOpener,
        relayClientOpener = relayClientOpener,
    )

    override fun open(): JournalBrowserUpstream {
        val pairing = (publisher.currentSnapshot() as? PairingGraphSnapshot.Committed)?.pairing
        if (pairing == null || (publisher.acquireDirectLeases().isEmpty() && publisher.acquireRelayLease() == null)) {
            throw JournalBrowserIdentityException()
        }
        if (guard?.stopped(pairing) == true) throw JournalBrowserIdentityException()
        val attempt = guard?.begin(pairing) ?: JournalAttempt.untracked(pairing)
        val opened = try {
            app.solstone.core.pl.openPairingClient(publisher, nextLegAfter = { true }) { lease ->
                val upstream = try {
                    when (lease) {
                        is PairingLease.Direct -> directClientOpener(DirectEndpoint(lease.endpoint.host, lease.endpoint.port), lease.credential, attempt)
                        is PairingLease.Relay -> relayClientOpener(lease.relayOrigin, lease.instanceId, lease.deviceToken, lease.credential, attempt)
                    }
                } catch (e: Exception) {
                    attempt.legFailed(e)
                    noteSighting(lease, e)
                    throw e
                }
                RefreshableUpstream(upstream)
            }.upstream
        } catch (t: Throwable) {
            attempt.finish()
            if (attempt.stop != null) throw JournalBrowserIdentityException()
            throw t
        }
        // The address refresh above may have been the connection's first read, and so its verdict.
        if (attempt.decided && attempt.refusal != null) {
            runCatching { opened.close() }
            if (attempt.stop != null) throw JournalBrowserIdentityException()
            throw java.io.IOException("journal refused this device")
        }
        return GuardedUpstream(opened, attempt)
    }

    private fun noteSighting(lease: PairingLease, failure: Exception) {
        val unknown = app.solstone.core.pl.unknownJournalIn(failure) ?: return
        val log = dialEvents ?: return
        val (host, port) = when (lease) {
            is PairingLease.Direct -> lease.endpoint.host to lease.endpoint.port
            is PairingLease.Relay -> {
                val origin = app.solstone.core.pl.parseProductionRelayOrigin(lease.relayOrigin) ?: return
                origin.host to origin.effectivePort
            }
        }
        log.recordUnknownJournal(host, port, unknown.claimedJournalId)
    }

    /**
     * An upstream whose first answer or refusal settles its attempt. A refusal that stopped the
     * credential becomes [JournalBrowserIdentityException]; any other failure stays ordinary.
     */
    private class GuardedUpstream(
        private val upstream: JournalBrowserUpstream,
        private val attempt: JournalAttempt,
    ) : JournalBrowserUpstream {
        override val isPoisoned: Boolean
            get() = upstream.isPoisoned

        override fun request(method: String, path: String, headers: Map<String, String>, body: ByteArray?): BrowserHttpResponse =
            guarded { upstream.request(method, path, headers, body) }.also { attempt.answered() }

        override fun requestStreaming(
            method: String,
            path: String,
            headers: List<Pair<String, String>>,
            bodySource: app.solstone.core.pl.browser.BrowserRequestBodySource?,
            responseSink: app.solstone.core.pl.browser.BrowserResponseSink,
        ) {
            guarded {
                upstream.requestStreaming(method, path, headers, bodySource, object : app.solstone.core.pl.browser.BrowserResponseSink by responseSink {
                    override fun onStatusAndHeaders(status: Int, reason: String, resHeaders: List<Pair<String, String>>) {
                        attempt.answered()
                        responseSink.onStatusAndHeaders(status, reason, resHeaders)
                    }
                })
            }
        }

        private fun <T> guarded(block: () -> T): T = try {
            block()
        } catch (e: JournalBrowserIdentityException) {
            throw e
        } catch (e: Exception) {
            if (app.solstone.core.pl.journalRefusalIn(e) != null) attempt.legFailed(e)
            if (attempt.stop != null) throw JournalBrowserIdentityException()
            throw e
        }

        override fun close() = upstream.close()
    }

    private class RefreshableUpstream(val upstream: JournalBrowserUpstream) : app.solstone.core.pl.PlHttpClient, java.io.Closeable {
        override fun request(method: String, path: String, headers: Map<String, String>, body: ByteArray?, maxResponseBytes: Int): app.solstone.core.pl.HttpResponse {
            val response = upstream.request(method, path, headers, body)
            require(response.body.size <= maxResponseBytes) { "address response too large" }
            return app.solstone.core.pl.HttpResponse(response.status, response.headers.toMap(), response.body)
        }
        override fun close() = upstream.close()
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
