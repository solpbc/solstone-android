// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.pl

import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RelayAccessApiTest {

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

    private fun createJwt(claimsJson: String): String {
        val encoder = Base64.getUrlEncoder().withoutPadding()
        val header = encoder.encodeToString("""{"alg":"none","typ":"JWT"}""".toByteArray())
        val payload = encoder.encodeToString(claimsJson.toByteArray())
        return "$header.$payload.signature"
    }

    @Test
    fun fetchRelayAccessReturnsReadyWhenValid() {
        val iat = 1700000000L
        val exp = 1800000000L
        val jwt = createJwt("""
        {
            "iss": "solstone",
            "ver": 2,
            "aud": "spl-relay",
            "scope": "session.dial",
            "instance_id": "jid-1",
            "sub": "instance:jid-1",
            "iat": $iat,
            "exp": $exp,
            "jti": "jwt-id-123"
        }
        """.trimIndent())

        val json = """
        {
            "protocol_version": 2,
            "status": "ready",
            "relay_origin": "https://relay.solstone.app",
            "instance_id": "jid-1",
            "device_token": "$jwt",
            "expires_at": "2027-01-15T08:00:00Z"
        }
        """.trimIndent()

        val client = FakePlHttpClient { method, path, _, _, _ ->
            assertEquals("GET", method)
            assertEquals("/app/network/api/relay/access", path)
            HttpResponse(200, emptyMap(), json.toByteArray())
        }

        val result = fetchRelayAccess(client, "jid-1", nowEpochMs = 1750000000000L)
        assertTrue(result is RelayAccessResponse.Ready)
        assertEquals("https://relay.solstone.app", result.relayOrigin)
        assertEquals("jid-1", result.instanceId)
        assertEquals(jwt, result.deviceToken)
        assertEquals(jwt, result.token)
        assertEquals("2027-01-15T08:00:00Z", result.expiresAt)
    }

    @Test
    fun fetchRelayAccessReturnsNotConfiguredWhenExactKeys() {
        val json = """{"protocol_version": 2, "status": "not_configured"}"""
        val client = FakePlHttpClient { _, _, _, _, _ ->
            HttpResponse(200, emptyMap(), json.toByteArray())
        }
        val result = fetchRelayAccess(client, "jid-1")
        assertTrue(result is RelayAccessResponse.NotConfigured)
    }

    @Test
    fun fetchRelayAccessReturnsMalformedWhenNotConfiguredHasExtraKeys() {
        val json = """{"protocol_version": 2, "status": "not_configured", "extra": "forbidden"}"""
        val client = FakePlHttpClient { _, _, _, _, _ ->
            HttpResponse(200, emptyMap(), json.toByteArray())
        }
        val result = fetchRelayAccess(client, "jid-1")
        assertTrue(result is RelayAccessResponse.Malformed)
    }

    @Test
    fun fetchRelayAccessReturnsMalformedWhenExtraJwtClaimsPresent() {
        val iat = 1700000000L
        val exp = 1800000000L
        val jwt = createJwt("""
        {
            "iss": "solstone",
            "ver": 2,
            "aud": "spl-relay",
            "scope": "session.dial",
            "instance_id": "jid-1",
            "sub": "instance:jid-1",
            "iat": $iat,
            "exp": $exp,
            "jti": "jwt-id-123",
            "device_fp": "extra-claim"
        }
        """.trimIndent())

        val json = """
        {
            "protocol_version": 2,
            "status": "ready",
            "relay_origin": "https://relay.solstone.app",
            "instance_id": "jid-1",
            "device_token": "$jwt",
            "expires_at": "2027-01-15T08:00:00Z"
        }
        """.trimIndent()

        val client = FakePlHttpClient { _, _, _, _, _ ->
            HttpResponse(200, emptyMap(), json.toByteArray())
        }

        val result = fetchRelayAccess(client, "jid-1")
        assertTrue(result is RelayAccessResponse.Malformed)
    }

    @Test
    fun fetchRelayAccessReturnsMalformedWhenJtiMissingOrBlank() {
        val iat = 1700000000L
        val exp = 1800000000L
        val jwtMissingJti = createJwt("""
        {
            "iss": "solstone",
            "ver": 2,
            "aud": "spl-relay",
            "scope": "session.dial",
            "instance_id": "jid-1",
            "sub": "instance:jid-1",
            "iat": $iat,
            "exp": $exp
        }
        """.trimIndent())

        val json = """
        {
            "protocol_version": 2,
            "status": "ready",
            "relay_origin": "https://relay.solstone.app",
            "instance_id": "jid-1",
            "device_token": "$jwtMissingJti",
            "expires_at": "2027-01-15T08:00:00Z"
        }
        """.trimIndent()

        val client = FakePlHttpClient { _, _, _, _, _ ->
            HttpResponse(200, emptyMap(), json.toByteArray())
        }

        val result = fetchRelayAccess(client, "jid-1")
        assertTrue(result is RelayAccessResponse.Malformed)
    }

    @Test
    fun fetchRelayAccessReturnsMalformedWhenIatOrExpFractional() {
        val jwt = createJwt("""
        {
            "iss": "solstone",
            "ver": 2,
            "aud": "spl-relay",
            "scope": "session.dial",
            "instance_id": "jid-1",
            "sub": "instance:jid-1",
            "iat": 1700000000.5,
            "exp": 1800000000,
            "jti": "jwt-1"
        }
        """.trimIndent())

        val json = """
        {
            "protocol_version": 2,
            "status": "ready",
            "relay_origin": "https://relay.solstone.app",
            "instance_id": "jid-1",
            "device_token": "$jwt",
            "expires_at": "2027-01-15T08:00:00Z"
        }
        """.trimIndent()

        val client = FakePlHttpClient { _, _, _, _, _ ->
            HttpResponse(200, emptyMap(), json.toByteArray())
        }

        val result = fetchRelayAccess(client, "jid-1")
        assertTrue(result is RelayAccessResponse.Malformed)
    }

    @Test
    fun fetchRelayAccessReturnsMalformedWhenSubMismatch() {
        val iat = 1700000000L
        val exp = 1800000000L
        val jwt = createJwt("""
        {
            "iss": "solstone",
            "ver": 2,
            "aud": "spl-relay",
            "scope": "session.dial",
            "instance_id": "wrong-jid",
            "sub": "instance:wrong-jid",
            "iat": $iat,
            "exp": $exp,
            "jti": "jwt-1"
        }
        """.trimIndent())

        val json = """
        {
            "protocol_version": 2,
            "status": "ready",
            "relay_origin": "https://relay.solstone.app",
            "instance_id": "jid-1",
            "device_token": "$jwt",
            "expires_at": "2027-01-15T08:00:00Z"
        }
        """.trimIndent()

        val client = FakePlHttpClient { _, _, _, _, _ ->
            HttpResponse(200, emptyMap(), json.toByteArray())
        }

        val result = fetchRelayAccess(client, "jid-1")
        assertTrue(result is RelayAccessResponse.Malformed)
    }

    @Test
    fun fetchRelayAccessReturnsMalformedWhenVersionNot2() {
        val iat = 1700000000L
        val exp = 1800000000L
        val jwt = createJwt("""
        {
            "iss": "solstone",
            "ver": 1,
            "aud": "spl-relay",
            "scope": "session.dial",
            "instance_id": "jid-1",
            "sub": "instance:jid-1",
            "iat": $iat,
            "exp": $exp,
            "jti": "jwt-1"
        }
        """.trimIndent())

        val json = """
        {
            "protocol_version": 2,
            "status": "ready",
            "relay_origin": "https://relay.solstone.app",
            "instance_id": "jid-1",
            "device_token": "$jwt",
            "expires_at": "2027-01-15T08:00:00Z"
        }
        """.trimIndent()

        val client = FakePlHttpClient { _, _, _, _, _ ->
            HttpResponse(200, emptyMap(), json.toByteArray())
        }

        val result = fetchRelayAccess(client, "jid-1")
        assertTrue(result is RelayAccessResponse.Malformed)
    }

    @Test
    fun fetchRelayAccessReturnsNotFoundOn404() {
        val client = FakePlHttpClient { _, _, _, _, _ -> HttpResponse(404, emptyMap(), ByteArray(0)) }
        val result = fetchRelayAccess(client, "jid-1")
        assertTrue(result is RelayAccessResponse.NotFound)
    }

    @Test
    fun fetchRelayAccessReturnsUnavailableOn503() {
        val client = FakePlHttpClient { _, _, _, _, _ -> HttpResponse(503, emptyMap(), ByteArray(0)) }
        val result = fetchRelayAccess(client, "jid-1")
        assertTrue(result is RelayAccessResponse.Unavailable)
    }

    @Test
    fun fetchRelayAccessFailureDoesNotLeakResponseBodyInMessage() {
        val secretBody = "sensitive-device-token-data"
        val client = FakePlHttpClient { _, _, _, _, _ -> HttpResponse(500, emptyMap(), secretBody.toByteArray()) }
        val result = fetchRelayAccess(client, "jid-1")
        assertTrue(result is RelayAccessResponse.Failure)
        assertEquals(500, result.status)
        assertEquals("HTTP 500", result.message)
    }
}
