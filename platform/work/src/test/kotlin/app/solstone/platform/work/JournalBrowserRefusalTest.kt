// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.work

import app.solstone.core.identity.ClientCredential
import app.solstone.core.identity.PairingGeneration
import app.solstone.core.identity.PairingGraphSnapshot
import app.solstone.core.model.DirectEndpoint
import app.solstone.core.model.IdentityState
import app.solstone.core.model.PairedHome
import app.solstone.core.pl.HandshakeFailure
import app.solstone.core.pl.HandshakeGuard
import app.solstone.core.pl.HandshakeRefusalTracker
import app.solstone.core.pl.JournalRefusalException
import app.solstone.core.pl.JournalVerdictListener
import app.solstone.core.pl.browser.BrowserHttpResponse
import app.solstone.core.pl.browser.BrowserRequestBodySource
import app.solstone.core.pl.browser.BrowserResponseSink
import app.solstone.core.pl.browser.BrowserTerminalClass
import app.solstone.core.pl.browser.JournalBrowserIdentityException
import app.solstone.core.pl.browser.JournalBrowserLifecycle
import app.solstone.core.pl.browser.JournalBrowserSession
import app.solstone.core.pl.browser.JournalBrowserUpstream
import app.solstone.platform.identity.file.FilePairingGraph
import app.solstone.platform.identity.file.FilePairingRefusalStore
import app.solstone.platform.identity.file.FileRefusalRecordStore
import app.solstone.platform.identity.file.SecretProtector
import java.io.File
import java.net.Socket
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The journal browser under the refusal rule it shares with sync and the status probe. */
class JournalBrowserRefusalTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val protector = object : SecretProtector {
        override fun protect(plaintext: ByteArray) = plaintext
        override fun unprotect(wrapped: ByteArray) = wrapped
    }
    private var now = 1_700_000_000_000L

    // Falsified by routing a 46 to pairing repair: an owner whose journal briefly could not read
    // its pairing records would be walked through a re-pair nothing required.
    @Test
    fun theBrowserDoesNotRouteACertificateUnknownToPairingRepair() {
        val (graph, guard) = paired()
        val session = JournalBrowserSession(
            publisher = graph,
            upstreamFactory = JournalBrowserUpstreamAdapter(graph, guard, refusingWith(46), refusingRelay()),
            diag = {},
        )
        val origin = URI(session.start().url)
        val response = get(origin)
        assertTrue(response.startsWith("HTTP/1.1 502"), response)
        val lifecycle = session.lifecycle
        assertIs<JournalBrowserLifecycle.Live>(lifecycle, "$lifecycle")
        assertEquals(IdentityState.PAIRED, state(graph))
        session.stop()
    }

    // A 49 stops the credential: the browser goes to pairing repair, the identity reads revoked, and
    // the next open dials nothing. Falsified by a stop the browser does not honour, or by a
    // browser-only refusal state the other callers never see.
    @Test
    fun anAccessDeniedStopsAndRoutesToPairingRepairAndNothingIsDialledAfter() {
        val (graph, guard) = paired()
        var dials = 0
        val adapter = JournalBrowserUpstreamAdapter(
            graph,
            guard,
            { _, _, listener ->
                dials++
                RefusingUpstream(listener, JournalRefusalException(HandshakeFailure.TLS_ACCESS_DENIED, 49))
            },
            refusingRelay(),
        )
        assertFailsWith<JournalBrowserIdentityException> { adapter.open() }
        assertEquals(IdentityState.REVOKED, state(graph))
        assertTrue(guard.stopped(PairingGeneration(HOME, CERT)))

        assertFailsWith<JournalBrowserIdentityException> { adapter.open() }
        assertEquals(1, dials)

        val session = JournalBrowserSession(publisher = graph, upstreamFactory = adapter, diag = {})
        runCatching { session.start() }
        val terminal = assertIs<JournalBrowserLifecycle.Terminal>(session.lifecycle)
        assertEquals(BrowserTerminalClass.IDENTITY_AUTH_REFUSAL, terminal.reason.classification)
        assertEquals(1, dials)

        // A fresh process reads the same stop from disk and dials nothing either.
        val (reopened, freshGuard) = reopen()
        val freshAdapter = JournalBrowserUpstreamAdapter(
            reopened,
            freshGuard,
            { _, _, _ ->
                dials++
                error("a stopped credential must not be dialled")
            },
            refusingRelay(),
        )
        assertFailsWith<JournalBrowserIdentityException> { freshAdapter.open() }
        assertEquals(1, dials)
    }

    // A refusal below the limit is retried, not repaired, and counted in the record sync and the
    // probe share. Falsified by a browser-local count, or by routing it to repair.
    @Test
    fun aCountedRefusalBelowTheLimitIsAnOrdinaryFailureCountedInTheSharedRecord() {
        val (graph, guard) = paired()
        val adapter = JournalBrowserUpstreamAdapter(graph, guard, refusingWith(80), refusingRelay())
        val failure = assertFailsWith<java.io.IOException> { adapter.open() }
        assertFalse(failure is JournalBrowserIdentityException)
        assertEquals(1, tracker().current(CERT)?.refusals)
        assertEquals(IdentityState.PAIRED, state(graph))
    }

    private fun paired(): Pair<FilePairingGraph, HandshakeGuard> {
        val graph = FilePairingGraph(
            identityFile = File(temp.root, "identity.tsv"),
            credentialFile = File(temp.root, "credential.pem"),
            endpointFile = File(temp.root, "endpoint.txt"),
            commitMarkerFile = File(temp.root, "pairing.commit"),
            protector = protector,
        )
        graph.installOrReplace(
            PairedHome(HOME, "Home", "https://link.solstone.app", "sha256:ca", CERT, null, "token-1", null, IdentityState.PAIRED),
            ClientCredential(
                "-----BEGIN PRIVATE KEY-----\nkey\n-----END PRIVATE KEY-----\n",
                "-----BEGIN CERTIFICATE-----\n${CERT.removePrefix("sha256:")}\n-----END CERTIFICATE-----\n",
                listOf("-----BEGIN CERTIFICATE-----\nca\n-----END CERTIFICATE-----\n"),
            ),
            DirectEndpoint("10.0.0.1", 7657),
            true,
        )
        return graph to HandshakeGuard(tracker(), graph, FilePairingRefusalStore(File(temp.root, "pairing_refusal.json")))
    }

    private fun reopen(): Pair<FilePairingGraph, HandshakeGuard> {
        val graph = FilePairingGraph(
            identityFile = File(temp.root, "identity.tsv"),
            credentialFile = File(temp.root, "credential.pem"),
            endpointFile = File(temp.root, "endpoint.txt"),
            commitMarkerFile = File(temp.root, "pairing.commit"),
            protector = protector,
        )
        return graph to HandshakeGuard(tracker(), graph)
    }

    private fun tracker() = HandshakeRefusalTracker(FileRefusalRecordStore(File(temp.root, "handshake_refusal.json")), { now }, { now })

    private fun state(graph: FilePairingGraph) = (graph.currentSnapshot() as PairingGraphSnapshot.Committed).home.state

    private fun refusingWith(code: Int): (app.solstone.core.pl.DirectEndpoint, ClientCredential, JournalVerdictListener) -> JournalBrowserUpstream {
        val failure = when (code) {
            46 -> HandshakeFailure.TLS_CERTIFICATE_UNKNOWN
            49 -> HandshakeFailure.TLS_ACCESS_DENIED
            else -> HandshakeFailure.TLS_REFUSED
        }
        return { _, _, listener -> RefusingUpstream(listener, JournalRefusalException(failure, code)) }
    }

    private fun refusingRelay(): (String, String, String, ClientCredential, JournalVerdictListener) -> JournalBrowserUpstream =
        { _, _, _, _, _ -> error("the relay must not be dialled after a verdict") }

    /** A connection whose journal refuses this device on the first read, as the transport reports it. */
    private class RefusingUpstream(
        private val listener: JournalVerdictListener,
        private val refusal: JournalRefusalException,
    ) : JournalBrowserUpstream {
        override val isPoisoned: Boolean = false

        private fun refuse(): Nothing {
            listener.onRefused(refusal)
            throw refusal
        }

        override fun request(method: String, path: String, headers: Map<String, String>, body: ByteArray?): BrowserHttpResponse = refuse()

        override fun requestStreaming(
            method: String,
            path: String,
            headers: List<Pair<String, String>>,
            bodySource: BrowserRequestBodySource?,
            responseSink: BrowserResponseSink,
        ) = refuse()

        override fun close() = Unit
    }

    private fun get(origin: URI): String =
        Socket("127.0.0.1", origin.port).use { socket ->
            socket.soTimeout = 10_000
            socket.getOutputStream().write("GET / HTTP/1.1\r\nHost: ${origin.host}:${origin.port}\r\nConnection: close\r\n\r\n".toByteArray())
            socket.getInputStream().readBytes().toString(Charsets.US_ASCII)
        }

    private companion object {
        const val HOME = "home-1"
        const val CERT = "sha256:cert-1"
    }
}
