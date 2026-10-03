// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.pl

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class JournalAboutApiTest {
    @Test
    fun resourceFixturesAcceptOneAndRejectThree() {
        val resources = resourceJson("about/bundle/resources.json")
        val valid = resources.requiredList("valid").single().requiredMap()
        assertEquals(
            JournalAbout("1.2.3", null, "ubuntu", "24.04", "x86_64"),
            decodeAbout(toJson(valid)),
        )
        resources.requiredList("invalid").forEach { invalid ->
            assertNull(decodeAbout(toJson(invalid)))
        }
    }

    @Test
    fun extraKeysAreIgnoredAndNotKept() {
        val resources = resourceJson("about/bundle/resources.json")
        val valid = resources.requiredList("valid").single().requiredMap() + ("hostname" to "PRIVATE HOST")
        val accepted = decodeAbout(toJson(valid))
        assertEquals(JournalAbout("1.2.3", null, "ubuntu", "24.04", "x86_64"), accepted)
    }

    @Test
    fun refusesMalformedAndUnsafeStoredFacts() {
        val valid = """{"protocol_version":1,"version":"1.2.3","os":"ubuntu","os_version":"24.04","arch":"x86_64","about":"journal 1.2.3"}"""
        assertNull(decodeAbout(valid.replace("\"version\":\"1.2.3\"", "\"version\":\"1\\n2.3\"")))
        assertNull(decodeAbout(valid.replace("\"arch\":\"x86_64\"", "\"arch\":64")))
        assertNull(decodeAbout(valid.replace("\"about\":\"journal 1.2.3\"", "\"about\":\"\"")))
        assertNull(decodeAbout(valid.replace("\"arch\":\"x86_64\"", "\"arch\":\"x86_64\\tprivate\"")))
        assertNull(decodeAbout(valid.replace("\"about\":\"journal 1.2.3\"", "\"about\":123")))
        assertNull(decodeAbout(valid.replace("\"arch\":\"x86_64\"", "\"arch\":\"x86_64\" , \"build\":\"\"")))
    }

    @Test
    fun fetchDistinguishesNotFoundAndRefusal() {
        val responseClient = object : PlHttpClient {
            var response = HttpResponse(404, emptyMap(), ByteArray(0))
            var requestPath: String? = null
            var maxBytes: Int = -1
            override fun request(method: String, path: String, headers: Map<String, String>, body: ByteArray?, maxResponseBytes: Int): HttpResponse {
                requestPath = path
                maxBytes = maxResponseBytes
                return response
            }
        }
        assertIs<JournalAboutResult.NotFound>(fetchJournalAbout(responseClient))
        assertEquals("/api/system/about", responseClient.requestPath)
        assertEquals(64 * 1024, responseClient.maxBytes)
        responseClient.response = HttpResponse(200, emptyMap(), "{}".toByteArray())
        assertIs<JournalAboutResult.Failed>(fetchJournalAbout(responseClient))
    }

    private fun resourceJson(path: String): Map<String, Any?> =
        parseJson(requiredResource(path).toString(Charsets.UTF_8)).requiredMap()

    private fun requiredResource(path: String): ByteArray =
        requireNotNull(javaClass.classLoader.getResourceAsStream(path)) { "missing test resource $path" }.use { it.readBytes() }

    private fun Any?.requiredMap(): Map<String, Any?> {
        @Suppress("UNCHECKED_CAST")
        return this as? Map<String, Any?> ?: error("expected JSON object")
    }

    private fun Map<String, Any?>.requiredList(key: String): List<Any?> = get(key) as? List<Any?> ?: error("expected JSON array $key")
}
