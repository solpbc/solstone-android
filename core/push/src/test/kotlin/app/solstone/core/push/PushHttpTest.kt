// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.push

import app.solstone.core.pl.HttpResponse
import app.solstone.core.pl.PlHttpClient
import app.solstone.core.pl.parseJson
import java.io.IOException
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PushHttpTest {

    private class FakePlHttpClient(
        var handler: (method: String, path: String, headers: Map<String, String>, body: ByteArray?) -> HttpResponse = { _, _, _, _ ->
            HttpResponse(404, emptyMap(), ByteArray(0))
        },
    ) : PlHttpClient {
        val requests = mutableListOf<RequestRecord>()

        data class RequestRecord(
            val method: String,
            val path: String,
            val headers: Map<String, String>,
            val body: ByteArray?,
        )

        override fun request(
            method: String,
            path: String,
            headers: Map<String, String>,
            body: ByteArray?,
            maxResponseBytes: Int,
        ): HttpResponse {
            requests.add(RequestRecord(method, path, headers, body))
            return handler(method, path, headers, body)
        }
    }

    @Test
    fun registerPushBodyFormat() {
        val client = FakePlHttpClient { _, _, _, _ ->
            HttpResponse(200, emptyMap(), ByteArray(0))
        }

        val key = ByteArray(32) { 0 }
        key[0] = 0xFB.toByte() // 0xFB is -5, base64 encodes with - / _ chars
        key[3] = 0xFF.toByte()

        val endpoint = "https://ntfy.sh/upAbC123?up=1"
        val p256dh = "test-p256dh-string"
        val auth = "test-auth-string"

        val result = registerPush(client, endpoint, p256dh, auth, key)
        assertIs<RegisterPushResult.Registered>(result)

        val req = client.requests.single()
        assertEquals("POST", req.method)
        assertEquals("/api/push/register", req.path)
        assertEquals("application/json", req.headers["Content-Type"])
        assertEquals("application/json", req.headers["Accept"])

        val bodyText = req.body!!.toString(Charsets.UTF_8)
        val parsed = parseJson(bodyText) as Map<*, *>
        assertEquals("android", parsed["platform"])
        assertEquals(endpoint, parsed["endpoint"])
        assertEquals(p256dh, parsed["p256dh"])
        assertEquals(auth, parsed["auth"])

        val pushKeyStr = parsed["push_key"] as String
        assertTrue(Regex("^[A-Za-z0-9_-]{43}$").matches(pushKeyStr), "push_key must be 43 base64url chars")
        assertTrue(pushKeyStr.contains('-') && pushKeyStr.contains('_'), "push_key with 0xFB and 0xFF must contain '-' and '_'")

        val pad = (4 - (pushKeyStr.length % 4)) % 4
        val decoded = Base64.getUrlDecoder().decode(pushKeyStr + "=".repeat(pad))
        assertContentEquals(key, decoded)
    }

    @Test
    fun deletePushRegistrationBodyFormat() {
        val client = FakePlHttpClient { _, _, _, _ ->
            HttpResponse(204, emptyMap(), ByteArray(0))
        }

        val endpoint = "https://ntfy.sh/upAbC123?up=1"
        val result = deletePushRegistration(client, endpoint)
        assertIs<DeletePushResult.Deleted>(result)

        val req = client.requests.single()
        assertEquals("DELETE", req.method)
        assertEquals("/api/push/register", req.path)
        assertEquals("application/json", req.headers["Content-Type"])

        val bodyText = req.body!!.toString(Charsets.UTF_8)
        assertEquals("""{"platform":"android","endpoint":"https://ntfy.sh/upAbC123?up=1"}""", bodyText)
    }

    @Test
    fun fetchVapidKeyStatusContracts() {
        val client = FakePlHttpClient()

        // 1. Valid 200 with 65-byte uncompressed P-256 key starting 0x04 (87 unpadded chars)
        val rawKey = ByteArray(65) { 0x01 }
        rawKey[0] = 0x04
        val b64Unpadded = Base64.getUrlEncoder().withoutPadding().encodeToString(rawKey)
        assertEquals(87, b64Unpadded.length)

        client.handler = { _, _, _, _ ->
            HttpResponse(200, emptyMap(), """{"public_key":"$b64Unpadded"}""".toByteArray(Charsets.UTF_8))
        }
        val resValid = fetchVapidKey(client)
        val keyRes = assertIs<VapidKeyResult.Key>(resValid)
        assertContentEquals(rawKey, keyRes.publicKey)

        // 2. 200 with wrong length or wrong prefix
        client.handler = { _, _, _, _ ->
            HttpResponse(200, emptyMap(), """{"public_key":"short"}""".toByteArray(Charsets.UTF_8))
        }
        assertIs<VapidKeyResult.Invalid>(fetchVapidKey(client))

        val badPrefixKey = ByteArray(65) { 0x01 }
        badPrefixKey[0] = 0x02
        val b64BadPrefix = Base64.getUrlEncoder().withoutPadding().encodeToString(badPrefixKey)
        client.handler = { _, _, _, _ ->
            HttpResponse(200, emptyMap(), """{"public_key":"$b64BadPrefix"}""".toByteArray(Charsets.UTF_8))
        }
        assertIs<VapidKeyResult.Invalid>(fetchVapidKey(client))

        // 3. 404 -> NoPush
        client.handler = { _, _, _, _ -> HttpResponse(404, emptyMap(), ByteArray(0)) }
        assertIs<VapidKeyResult.NoPush>(fetchVapidKey(client))

        // 4. 500 -> Failed
        client.handler = { _, _, _, _ -> HttpResponse(500, emptyMap(), "server error".toByteArray(Charsets.UTF_8)) }
        val resFailed = assertIs<VapidKeyResult.Failed>(fetchVapidKey(client))
        assertEquals(500, resFailed.status)
        assertEquals("server error", resFailed.message)

        // 5. IOException -> Failed with status null
        client.handler = { _, _, _, _ -> throw IOException("network down") }
        val resIo = assertIs<VapidKeyResult.Failed>(fetchVapidKey(client))
        assertNull(resIo.status)
        assertEquals("network down", resIo.message)
    }

    @Test
    fun registerPushStatusContracts() {
        val client = FakePlHttpClient()
        val key = ByteArray(32)

        client.handler = { _, _, _, _ -> HttpResponse(200, emptyMap(), ByteArray(0)) }
        assertIs<RegisterPushResult.Registered>(registerPush(client, "https://x.com", "p", "a", key))

        client.handler = { _, _, _, _ -> HttpResponse(201, emptyMap(), ByteArray(0)) }
        assertIs<RegisterPushResult.Registered>(registerPush(client, "https://x.com", "p", "a", key))

        client.handler = { _, _, _, _ -> HttpResponse(400, emptyMap(), ByteArray(0)) }
        assertIs<RegisterPushResult.Refused>(registerPush(client, "https://x.com", "p", "a", key))

        client.handler = { _, _, _, _ -> HttpResponse(403, emptyMap(), ByteArray(0)) }
        assertIs<RegisterPushResult.NotLinked>(registerPush(client, "https://x.com", "p", "a", key))

        client.handler = { _, _, _, _ -> HttpResponse(404, emptyMap(), ByteArray(0)) }
        assertIs<RegisterPushResult.NoPush>(registerPush(client, "https://x.com", "p", "a", key))

        client.handler = { _, _, _, _ -> HttpResponse(503, emptyMap(), "service unavailable".toByteArray(Charsets.UTF_8)) }
        val res503 = assertIs<RegisterPushResult.Failed>(registerPush(client, "https://x.com", "p", "a", key))
        assertEquals(503, res503.status)
        assertEquals("service unavailable", res503.message)

        client.handler = { _, _, _, _ -> throw IOException("timeout") }
        val resIo = assertIs<RegisterPushResult.Failed>(registerPush(client, "https://x.com", "p", "a", key))
        assertNull(resIo.status)
        assertEquals("timeout", resIo.message)
    }

    @Test
    fun deletePushRegistrationStatusContracts() {
        val client = FakePlHttpClient()

        client.handler = { _, _, _, _ -> HttpResponse(204, emptyMap(), ByteArray(0)) }
        assertIs<DeletePushResult.Deleted>(deletePushRegistration(client, "https://x.com"))

        client.handler = { _, _, _, _ -> HttpResponse(403, emptyMap(), ByteArray(0)) }
        assertIs<DeletePushResult.NotLinked>(deletePushRegistration(client, "https://x.com"))

        client.handler = { _, _, _, _ -> HttpResponse(404, emptyMap(), ByteArray(0)) }
        assertIs<DeletePushResult.NoPush>(deletePushRegistration(client, "https://x.com"))

        client.handler = { _, _, _, _ -> HttpResponse(500, emptyMap(), "error".toByteArray(Charsets.UTF_8)) }
        val res500 = assertIs<DeletePushResult.Failed>(deletePushRegistration(client, "https://x.com"))
        assertEquals(500, res500.status)

        client.handler = { _, _, _, _ -> throw IOException("refused") }
        val resIo = assertIs<DeletePushResult.Failed>(deletePushRegistration(client, "https://x.com"))
        assertNull(resIo.status)
        assertEquals("refused", resIo.message)
    }

    @Test
    fun isAcceptableEndpointCases() {
        assertTrue(isAcceptableEndpoint("https://ntfy.sh/upAbC123?up=1"))
        assertTrue(isAcceptableEndpoint("https://fcm.googleapis.com/fcm/send/x"))
        assertTrue(isAcceptableEndpoint("https://example.com"))
        assertTrue(isAcceptableEndpoint("https://example.com/"))
        assertTrue(isAcceptableEndpoint("https://example.com:8443/push"))

        assertFalse(isAcceptableEndpoint("http://example.com"))
        assertFalse(isAcceptableEndpoint("HTTPS://example.com"))
        assertFalse(isAcceptableEndpoint("ftp://example.com"))
        assertFalse(isAcceptableEndpoint("https://"))
        assertFalse(isAcceptableEndpoint("https:// "))
        assertFalse(isAcceptableEndpoint(" https://example.com"))
        assertFalse(isAcceptableEndpoint("https://example.com "))
        assertFalse(isAcceptableEndpoint(""))
    }
}
