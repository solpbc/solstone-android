// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.pl

import kotlin.test.assertNotNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClientsSelfApiTest {

    private class FakePlHttpClient(
        private val handler: (method: String, path: String, headers: Map<String, String>, body: ByteArray?, maxResponseBytes: Int) -> HttpResponse,
    ) : PlHttpClient {
        override fun request(
            method: String,
            path: String,
            headers: Map<String, String>,
            body: ByteArray?,
            maxResponseBytes: Int,
        ): HttpResponse = handler(method, path, headers, body, maxResponseBytes)
    }

    @Test
    fun sanitizeReportedDescriptionNullsOverBoundAndC0AndBlankFields() {
        val raw = ClientReportedDescription(
            name = "a".repeat(81), // > 80 UTF-8 bytes -> null
            platform = "android\u0000bad", // C0 control -> null
            deviceType = "   ", // blank -> null
            appId = "app.solstone.phone", // valid
            appVersion = "1.0.0", // valid
        )
        val sanitized = sanitizeReportedDescription(raw)
        assertNull(sanitized.name)
        assertNull(sanitized.platform)
        assertNull(sanitized.deviceType)
        assertEquals("app.solstone.phone", sanitized.appId)
        assertEquals("1.0.0", sanitized.appVersion)
    }

    @Test
    fun sanitizeReportedDescriptionHandlesMultibyteUtf8Bounds() {
        val validMultibyte = "é".repeat(40)
        assertEquals(80, validMultibyte.toByteArray(Charsets.UTF_8).size)
        val overMultibyte = "é".repeat(41)
        assertEquals(82, overMultibyte.toByteArray(Charsets.UTF_8).size)

        val desc = ClientReportedDescription(
            name = validMultibyte,
            platform = overMultibyte, // platform limit is 64 bytes -> 82 bytes -> null
            deviceType = "phone",
            appId = "pkg",
            appVersion = "1.0",
        )
        val sanitized = sanitizeReportedDescription(desc)
        assertEquals(validMultibyte, sanitized.name)
        assertNull(sanitized.platform)
    }

    @Test
    fun parseClientsSelfResponseExtractsJournalAndReportedFields() {
        val json = """{"protocol_version":1,"revision":3,"journal":{"name":"My Daily Journal","version":"1.4.2"},"reported":{"name":"Pixel 8","platform":"android","device_type":"phone","app_id":"app.solstone.phone","app_version":"1.0.0"},"owner_label":null,"display_label":"Phone","updated_at":null}""".trimIndent()
        val response = parseClientsSelfResponse(json)
        assertEquals(1, response?.protocolVersion)
        assertEquals(3L, response?.revision)
        assertEquals("My Daily Journal", response?.journalName)
        assertEquals("1.4.2", response?.journalVersion)
        assertEquals("Pixel 8", response?.reported?.name)
        assertEquals("android", response?.reported?.platform)
        assertEquals("phone", response?.reported?.deviceType)
        assertEquals("app.solstone.phone", response?.reported?.appId)
        assertEquals("1.0.0", response?.reported?.appVersion)
    }

    @Test
    fun parseClientsSelfResponseRejectsInvalidProtocolsRevisionsAndTypes() {
        // Missing protocol_version
        assertNull(parseClientsSelfResponse("""{"revision":1,"journal":{"name":"J","version":"1"}}"""))
        // protocol 1.5 (float)
        assertNull(parseClientsSelfResponse("""{"protocol_version":1.5,"revision":1}"""))
        // protocol string
        assertNull(parseClientsSelfResponse("""{"protocol_version":"1","revision":1}"""))
        // protocol 2
        assertNull(parseClientsSelfResponse("""{"protocol_version":2,"revision":1}"""))
        // missing revision
        assertNull(parseClientsSelfResponse("""{"protocol_version":1}"""))
        // non-integral revision (float)
        assertNull(parseClientsSelfResponse("""{"protocol_version":1,"revision":1.5}"""))
        // negative revision
        assertNull(parseClientsSelfResponse("""{"protocol_version":1,"revision":-1}"""))
        // wrong type for journal (string instead of map)
        assertNull(parseClientsSelfResponse("""{"protocol_version":1,"revision":1,"journal":"bad"}"""))
        // wrong type for reported (array instead of map)
        assertNull(parseClientsSelfResponse("""{"protocol_version":1,"revision":1,"reported":[]}"""))
        // empty json
        assertNull(parseClientsSelfResponse("{}"))
    }

    @Test
    fun fetchClientsSelfReturnsSuccessOn200() {
        val json = """{"protocol_version":1,"revision":1,"journal":{"name":"J","version":"2.0"},"reported":null,"owner_label":null,"display_label":"Phone","updated_at":null}"""
        val client = FakePlHttpClient { method, path, _, _, _ ->
            assertEquals("GET", method)
            assertEquals("/app/network/api/clients/self", path)
            HttpResponse(200, emptyMap(), json.toByteArray())
        }
        val result = fetchClientsSelf(client)
        assertTrue(result is ClientsSelfGetResult.Success)
        assertEquals("J", result.response.journalName)
        assertEquals("2.0", result.response.journalVersion)
    }

    @Test
    fun fetchClientsSelfReturnsNotFoundOn404() {
        val client = FakePlHttpClient { _, _, _, _, _ -> HttpResponse(404, emptyMap(), ByteArray(0)) }
        val result = fetchClientsSelf(client)
        assertTrue(result is ClientsSelfGetResult.NotFound)
    }

    @Test
    fun putClientsSelfReturnsConflictOn409() {
        val client = FakePlHttpClient { method, path, _, _, _ ->
            assertEquals("PUT", method)
            assertEquals("/app/network/api/clients/self", path)
            HttpResponse(409, emptyMap(), ByteArray(0))
        }
        val result = putClientsSelf(client, ClientsSelfPutRequest(expectedRevision = 1L, reported = ClientReportedDescription(name = "phone")))
        assertTrue(result is ClientsSelfPutResult.Conflict)
    }

    @Test
    fun putClientsSelfReturnsSuccessOn200() {
        val json = """{"protocol_version":1,"revision":2,"reported":{"name":"Phone","platform":null,"device_type":null,"app_id":null,"app_version":null},"owner_label":null,"display_label":"Phone","updated_at":null,"journal":{"name":"J","version":"1.0.0"}}"""
        val client = FakePlHttpClient { _, _, _, _, _ ->
            HttpResponse(200, emptyMap(), json.toByteArray())
        }
        val result = putClientsSelf(client, ClientsSelfPutRequest(expectedRevision = 1L, reported = ClientReportedDescription(name = "Phone")))
        assertTrue(result is ClientsSelfPutResult.Success)
        assertEquals("Phone", result.response.reported?.name)
    }
    @Test
    fun requiredNullableFieldsMustBePresentAndRevisionMustBeBounded() {
        val complete = """{"protocol_version":1,"revision":1,"reported":{"name":null,"platform":null,"device_type":null,"app_id":null,"app_version":null},"owner_label":null,"display_label":"Phone","updated_at":null,"journal":{"name":"J","version":"1.0.0"}}"""
        assertNotNull(parseClientsSelfResponse(complete))
        assertNull(parseClientsSelfResponse(complete.replace("\"protocol_version\":1", "\"protocol_version\":1.000000000000000001")))
        assertNull(parseClientsSelfResponse(complete.replace("\"revision\":1", "\"revision\":1.000000000000000001")))
        val root = parseJson(complete) as Map<String, Any?>
        assertNotNull(parseClientsSelfResponse(toJson(root + ("journal" to mapOf("name" to null, "version" to "1.0.0")))))
        assertNull(parseClientsSelfResponse(toJson(root + ("display_label" to null))))
        assertNull(parseClientsSelfResponse(toJson(root + ("journal" to mapOf("name" to "J", "version" to null)))))
        for (key in root.keys) {
            assertNull(parseClientsSelfResponse(toJson(root - key)), key)
        }
        val reported = root["reported"] as Map<String, Any?>
        for (key in reported.keys) {
            assertNull(parseClientsSelfResponse(toJson(root + ("reported" to (reported - key)))), key)
        }
        assertNotNull(parseClientsSelfResponse(toJson(root + ("reported" to null))))
        for (revision in listOf(1.5, -1, 9007199254740992.0)) {
            assertNull(parseClientsSelfResponse(toJson(root + ("revision" to revision))))
        }
    }

}
