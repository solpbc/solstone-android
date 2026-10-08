// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.identity.file

import app.solstone.core.identity.*
import app.solstone.core.model.DirectEndpoint
import app.solstone.core.model.IdentityState
import app.solstone.core.model.PairedHome
import app.solstone.core.pl.HttpResponse
import app.solstone.core.pl.PlHttpClient
import app.solstone.core.pl.openPairingClient
import app.solstone.core.pl.refreshDirectEndpoints
import java.io.Closeable
import java.io.File
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.*

class FileAddressRefreshTest {
    @get:Rule val temp = TemporaryFolder()
    private val protector = object : SecretProtector {
        override fun protect(plaintext: ByteArray) = plaintext
        override fun unprotect(wrapped: ByteArray) = wrapped
    }
    private val a = DirectEndpoint("10.0.0.2", 7657)
    private val b = DirectEndpoint("10.0.0.3", 7657)
    private val c = DirectEndpoint("journal.example.test", 8765)
    private val home = PairedHome("home-1", "Home", "https://link.solstone.app", "sha256:ca-1", "sha256:cert-1", "obs", "token-1", "2030-01-01T00:00:00Z", IdentityState.PAIRED)
    private val credential = ClientCredential("-----BEGIN PRIVATE KEY-----\nkey-1\n-----END PRIVATE KEY-----\n", "-----BEGIN CERTIFICATE-----\ncert-1\n-----END CERTIFICATE-----\n", listOf("-----BEGIN CERTIFICATE-----\nca-1\n-----END CERTIFICATE-----\n"))
    private fun graph(hook: DurableTxnHook? = null) = FilePairingGraph(File(temp.root, "identity.tsv"), File(temp.root, "credential.pem"), File(temp.root, "endpoint.txt"), File(temp.root, "pairing.commit"), protector, stepHook = hook)
    private fun snapshot(graph: FilePairingGraph) = graph.currentSnapshot() as PairingGraphSnapshot.Committed
    private fun install(graph: FilePairingGraph, endpoints: List<DirectEndpoint> = listOf(a, b), associated: Boolean = true) {
        assertIs<GraphMutationResult.Applied>(graph.installOrReplace(home, credential, endpoints.firstOrNull(), associated, PairingProvenance.FRESH_LINK, endpoints))
    }
    // Envelope and field names are the journal's local-endpoints API contract.
    private fun body(endpoints: List<DirectEndpoint>) = """{"v":2,"endpoints":[${endpoints.joinToString(",") { """{"ip":"${it.host}","port":${it.port},"scope":"lan"}""" }}],"ttl_s":3600,"generated_at":"2026-10-08T23:00:00Z"}"""
    private class Client(private val response: () -> HttpResponse) : PlHttpClient, Closeable {
        val paths = mutableListOf<String>()
        var closed = false
        override fun request(method: String, path: String, headers: Map<String, String>, body: ByteArray?, maxResponseBytes: Int): HttpResponse { paths += path; return response() }
        override fun close() { closed = true }
    }
    private fun client(endpoints: List<DirectEndpoint>) = Client { HttpResponse(200, emptyMap(), body(endpoints).toByteArray()) }

    @Test fun refreshAddsRetiresAndSurvivesRestartAndRelayAccessUpdate() {
        val graph = graph(); install(graph)
        val before = snapshot(graph)
        assertTrue(refreshDirectEndpoints(client(listOf(b, c)), graph, before, b))
        assertEquals(listOf(b, c), snapshot(graph).directEndpoints)
        assertEquals(before.revisions.pairingRevision, snapshot(graph).revisions.pairingRevision)
        assertIs<GraphMutationResult.Applied>(graph.updateRelayAccess(before.pairing, "https://link.solstone.app", "token-2", null))
        assertEquals(listOf(b, c), snapshot(graph()).directEndpoints)
        assertEquals("token-2", snapshot(graph()).home.deviceToken)
    }

    @Test fun olderOrMissingVersionNeverReplacesTheSet() {
        val graph = graph(); install(graph)
        val rejectedBodies = listOf("1", "0", "null", "\"2\"", "2.5").map { body(listOf(c)).replace("\"v\":2", "\"v\":$it") } + body(listOf(c)).replace("\"v\":2,", "")
        for (responseBody in rejectedBodies) {
            val client = Client { HttpResponse(200, emptyMap(), responseBody.toByteArray()) }
            assertFalse(refreshDirectEndpoints(client, graph, snapshot(graph), b, log = {}))
            assertEquals(listOf(a, b), snapshot(graph()).directEndpoints)
        }
        assertTrue(refreshDirectEndpoints(client(listOf(c)), graph, snapshot(graph), b))
        assertEquals(listOf(c, b), snapshot(graph()).directEndpoints)
    }

    @Test fun deliveringAddressIsKeptAfterTheAdvertisedList() {
        val graph = graph(); install(graph)
        assertTrue(refreshDirectEndpoints(client(listOf(c)), graph, snapshot(graph), b))
        assertEquals(listOf(c, b), snapshot(graph()).directEndpoints)
    }

    @Test fun emptyTransportErrorNon200AndPartialMalformedLeaveLastGoodSet() {
        val graph = graph(); install(graph)
        val clients = listOf(client(emptyList()), Client { throw java.io.IOException("offline") }, Client { HttpResponse(503, emptyMap(), body(listOf(c)).toByteArray()) }, Client { HttpResponse(200, emptyMap(), """{"v":2,"endpoints":[{"ip":"10.0.0.3","port":7657,"scope":"lan"},{"ip":"bad","scope":"vpn"}],"ttl_s":3600,"generated_at":"2026-10-08T23:00:00Z"}""".toByteArray()) })
        for (client in clients) {
            assertFalse(refreshDirectEndpoints(client, graph, snapshot(graph), b, log = {}))
            assertEquals(listOf(a, b), snapshot(graph()).directEndpoints)
        }
    }

    @Test fun legacySingleEndpointWithAndWithoutCommitMarkerNeedsNoRepair() {
        for (marker in listOf(true, false)) {
            val graph = graph(); install(graph, listOf(a), associated = marker)
            assertEquals("10.0.0.2\n7657\n", File(temp.root, "endpoint.txt").readText())
            if (!marker) File(temp.root, "pairing.commit").delete()
            val upgraded = graph()
            assertEquals(listOf(a), snapshot(upgraded).directEndpoints)
            openPairingClient(upgraded) { client(listOf(a, c)) }.use { assertFalse(it.closed) }
            assertEquals(listOf(a, c), snapshot(graph()).directEndpoints)
            assertTrue(snapshot(graph()).directAssociated)
        }
    }

    @Test fun oldPairingAndOlderSamePairingResponseCannotResurrectAnAddress() {
        val graph = graph(); install(graph)
        val old = snapshot(graph)
        assertTrue(refreshDirectEndpoints(client(listOf(b, c)), graph, old, b))
        assertFalse(refreshDirectEndpoints(client(listOf(a, b)), graph, old, b))
        assertEquals(listOf(b, c), snapshot(graph).directEndpoints)
        val priorPairing = snapshot(graph)
        assertIs<GraphMutationResult.Cleared>(graph.forget())
        install(graph, listOf(c)) // Same certificate again still has a new pairing revision.
        assertFalse(refreshDirectEndpoints(client(listOf(a, b)), graph, priorPairing, b))
        assertEquals(listOf(c), snapshot(graph).directEndpoints)
    }

    @Test fun secondAddressIsDialedAndItsConnectionSurvivesItsOwnRefresh() {
        val graph = graph(); install(graph)
        val attempted = mutableListOf<DirectEndpoint>()
        val connected = openPairingClient(graph) { lease ->
            val direct = assertIs<PairingLease.Direct>(lease)
            attempted += direct.endpoint
            if (direct.endpoint == a) throw java.net.ConnectException("retired")
            client(listOf(b, c))
        }
        assertEquals(listOf(a, b), attempted)
        assertFalse(connected.closed)
        assertEquals(200, connected.request("GET", "/app/network/api/status", emptyMap(), null).status)
        assertEquals(listOf(b, c), snapshot(graph()).directEndpoints)
        connected.close()
    }

    @Test fun loopbackDoesNotRequestAddresses() {
        val graph = graph(); val loopback = DirectEndpoint("127.0.0.1", 7657); install(graph, listOf(loopback))
        val connected = client(listOf(c))
        assertFalse(refreshDirectEndpoints(connected, graph, snapshot(graph), loopback))
        assertTrue(connected.paths.isEmpty())
        assertEquals(listOf(loopback), snapshot(graph()).directEndpoints)
    }

    private class Crash : Error()
    @Test fun deathBeforeCommitRestoresOldSetAndAfterCommitKeepsNewSet() {
        for (step in listOf(DurableTxnStep.STAGING_WRITE, DurableTxnStep.RENAME_REPLACE, DurableTxnStep.READ_BACK, DurableTxnStep.DURABLE_COMMIT_DECISION)) {
            var armed = false
            val graph = graph(DurableTxnHook { observed, op -> if (armed && observed == step && op == "ADDRESS_UPDATE") throw Crash() })
            install(graph)
            val before = snapshot(graph)
            armed = true
            assertFailsWith<Crash> { graph.replaceDirectEndpoints(before, listOf(b, c)) }
            val restored = snapshot(graph())
            assertEquals(if (step == DurableTxnStep.DURABLE_COMMIT_DECISION) listOf(b, c) else listOf(a, b), restored.directEndpoints)
            assertEquals(home, restored.home)
            assertEquals(credential, graph().acquireDirectLeases().first().credential)
        }
    }
}
