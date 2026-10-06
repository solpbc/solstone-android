// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.observer

import app.solstone.core.pl.parseJson
import app.solstone.core.pl.HttpResponse
import app.solstone.core.pl.PlHttpClient
import app.solstone.core.pl.toJson
import java.security.MessageDigest
import kotlin.test.assertFailsWith
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ClientIngestContractAdoptionTest {
    @Test
    fun pinnedRevisionResourcesAndOfflineCollisionFixturesAreBound() {
        val adoption = json("client-ingest-contract/adoption.json")
        assertEquals("1e432dba3ecdfa43789c25f97077fdc3e71fab59", adoption["authority_commit"])
        assertEquals("app.solstone.core.observer.SegmentReconciler", adoption["owner"])
        val manifest = json("client-ingest-contract/manifest.json")
        assertEquals("3", manifest["client_protocol_version"].toString())

        val files = adoption["files"] as List<*>
        files.forEach { raw ->
            val entry = raw as Map<*, *>
            val path = entry["path"] as String
            val local = path.removePrefix("docs/openapi/client-ingest-contract/")
            assertEquals(entry["sha256"], sha256(bytes("client-ingest-contract/$local")), path)
        }
        val fixture = text("client-ingest-contract/fixtures/wire-behavior.json")
        assertTrue("client.ingestSegments.collision.same_basename_distinct_streams" in fixture)
        assertTrue("client.ingestSegments.collision.duplicate_wire_key_refused" in fixture)
        assertTrue("duplicate_listing_key" in fixture)
        assertTrue("browser_a" in fixture && "browser_b" in fixture)
    }

    @Test
    fun pinnedSegmentsCollisionVectorsAreConsumedByTheOfflineParser() {
        val vectors = json("client-ingest-contract/vectors.json")["vectors"] as List<*>
        val collision = vectors.map { it as Map<*, *> }
            .single { it["id"] == "client.ingestSegments.collision.same_basename_distinct_streams" }
        val duplicateKey = vectors.map { it as Map<*, *> }
            .single { it["id"] == "client.ingestSegments.collision.duplicate_wire_key_refused" }

        val acceptedInput = collision["input"] as Map<*, *>
        val accepted = SegmentReconciler(clientFor(acceptedInput)).fetch("20260616")
        assertEquals(listOf("120000_10~browser_a", "120000_10~browser_b"), accepted.map { it.key })
        assertEquals(listOf("browser_a", "browser_b"), accepted.map { it.stream })

        val duplicateInput = duplicateKey["input"] as Map<*, *>
        assertFailsWith<ReconcileUnavailableException> {
            SegmentReconciler(clientFor(duplicateInput)).fetch("20260616")
        }
    }

    private fun json(path: String): Map<*, *> = parseJson(text(path)) as Map<*, *>
    private fun clientFor(input: Map<*, *>): PlHttpClient = object : PlHttpClient {
        override fun request(
            method: String,
            path: String,
            headers: Map<String, String>,
            body: ByteArray?,
            maxResponseBytes: Int,
        ): HttpResponse = HttpResponse(200, emptyMap(), toJson(input).toByteArray(Charsets.UTF_8))
    }
    private fun text(path: String): String = bytes(path).toString(Charsets.UTF_8)
    private fun bytes(path: String): ByteArray = requireNotNull(javaClass.classLoader?.getResourceAsStream(path)).use { it.readBytes() }
    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}
