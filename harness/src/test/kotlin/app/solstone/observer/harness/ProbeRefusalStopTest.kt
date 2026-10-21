// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.harness

import app.solstone.core.identity.ClientCredential
import app.solstone.core.identity.PairingGraphSnapshot
import app.solstone.core.model.DirectEndpoint
import app.solstone.core.model.IdentityState
import app.solstone.core.model.PairedHome
import app.solstone.core.pl.HandshakeFailure
import app.solstone.core.pl.HandshakeGuard
import app.solstone.core.pl.HandshakeRefusalTracker
import app.solstone.core.pl.HandshakeStop
import app.solstone.core.pl.HttpResponse
import app.solstone.core.pl.JournalRefusalException
import app.solstone.core.pl.PlHttpClient
import app.solstone.core.pl.RefusalRecord
import app.solstone.platform.identity.file.FileClientCredentialStore
import app.solstone.platform.identity.file.FileEndpointStore
import app.solstone.platform.identity.file.FileIdentityStore
import app.solstone.platform.identity.file.FilePairingGraph
import app.solstone.platform.identity.file.FileRefusalRecordStore
import app.solstone.platform.identity.file.SecretProtector
import app.solstone.platform.work.PublisherIdentityMutatorAdapter
import app.solstone.platform.work.SyncTransport
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertIs
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The status probe honours a stop, in this process and the next, until a re-pair. */
class ProbeRefusalStopTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val protector = object : SecretProtector {
        override fun protect(plaintext: ByteArray) = plaintext
        override fun unprotect(wrapped: ByteArray) = wrapped
    }

    // Falsified by a probe that keeps dialling a credential the journal refused with 49, or by a
    // stop that a relaunch forgets.
    @Test
    fun aStoppedCredentialIsNotDialledByTheProbeInAFreshProcessUntilARepair() {
        var dials = 0
        val first = probe { dials++; refusing(49) }
        assertIs<HarnessPlStatus.PairedButUnreachable>(first.probe.probe())
        assertEquals(1, dials)
        assertEquals(IdentityState.REVOKED, first.state())

        val relaunched = probe { dials++; answering(200) }
        assertEquals(HarnessPlStatus.PairedButUnreachable("identity not paired"), relaunched.probe.probe())
        assertEquals(1, dials)

        relaunched.graph.installOrReplace(home("sha256:cert-2"), credential("cert-2"), DirectEndpoint("10.0.0.1", 7657), true)
        assertEquals(HarnessPlStatus.Reachable(200), relaunched.probe.probe())
        assertEquals(2, dials)
    }

    // The record says stopped but the revoked identity was never written (a failed write, say). The
    // probe still dials nothing, and writes the revoked state. Falsified by honouring only the
    // identity state.
    @Test
    fun aRecordedStopBlocksTheProbeEvenBeforeTheRevokedStateIsWritten() {
        FileRefusalRecordStore(File(temp.root, "handshake_refusal.json"))
            .write(RefusalRecord("sha256:cert-1", 7, 1L, HandshakeStop.REFUSALS_EXHAUSTED))
        var dials = 0
        val setup = probe { dials++; answering(200) }
        assertEquals(HarnessPlStatus.PairedButUnreachable("identity not paired"), setup.probe.probe())
        assertEquals(0, dials)
        assertEquals(IdentityState.REVOKED, setup.state())
    }

    private class Setup(val probe: RealPlStatusProbe, val graph: FilePairingGraph) {
        fun state() = (graph.currentSnapshot() as PairingGraphSnapshot.Committed).home.state
    }

    private fun probe(open: (SyncTransport) -> PlHttpClient): Setup {
        val graph = FilePairingGraph(
            identityFile = File(temp.root, "identity.tsv"),
            credentialFile = File(temp.root, "credential.pem"),
            endpointFile = File(temp.root, "endpoint.txt"),
            commitMarkerFile = File(temp.root, "pairing.commit"),
            protector = protector,
        )
        if (graph.currentSnapshot() !is PairingGraphSnapshot.Committed) {
            graph.installOrReplace(home("sha256:cert-1"), credential("cert-1"), DirectEndpoint("10.0.0.1", 7657), true)
        }
        val guard = HandshakeGuard(
            HandshakeRefusalTracker(FileRefusalRecordStore(File(temp.root, "handshake_refusal.json"))),
            graph,
        )
        val probe = RealPlStatusProbe(
            endpointStore = FileEndpointStore(File(temp.root, "endpoint.txt")),
            credentialStore = FileClientCredentialStore(File(temp.root, "credential.pem"), protector),
            identityStore = FileIdentityStore(File(temp.root, "identity.tsv"), protector),
            mutator = PublisherIdentityMutatorAdapter(graph),
            openTransport = { transport, _ -> open(transport) },
            publisher = graph,
            handshakeGuard = guard,
        )
        return Setup(probe, graph)
    }

    private fun home(cert: String) =
        PairedHome("home-1", "Home", null, "sha256:ca", cert, null, null, null, IdentityState.PAIRED)

    private fun credential(body: String) = ClientCredential(
        "-----BEGIN PRIVATE KEY-----\nkey\n-----END PRIVATE KEY-----\n",
        "-----BEGIN CERTIFICATE-----\n$body\n-----END CERTIFICATE-----\n",
        listOf("-----BEGIN CERTIFICATE-----\nca\n-----END CERTIFICATE-----\n"),
    )

    private fun answering(status: Int) = object : PlHttpClient {
        override fun request(method: String, path: String, headers: Map<String, String>, body: ByteArray?, maxResponseBytes: Int) =
            HttpResponse(status, emptyMap(), ByteArray(0))
    }

    private fun refusing(code: Int) = object : PlHttpClient {
        override fun request(method: String, path: String, headers: Map<String, String>, body: ByteArray?, maxResponseBytes: Int): HttpResponse =
            throw JournalRefusalException(HandshakeFailure.TLS_ACCESS_DENIED, code)
    }
}
