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

    @Test
    fun parseProductionRelayOriginTests() {
        val std = parseProductionRelayOrigin("https://link.solstone.app")
        assertEquals("https", std?.scheme)
        assertEquals("link.solstone.app", std?.host)
        assertEquals(443, std?.effectivePort)
        assertEquals("https://link.solstone.app", std?.httpsBase)
        assertEquals("wss://link.solstone.app", std?.wssBase)

        val trailingSlash = parseProductionRelayOrigin("https://link.solstone.app/")
        assertEquals(443, trailingSlash?.effectivePort)
        assertEquals("https://link.solstone.app", trailingSlash?.httpsBase)

        val customPort = parseProductionRelayOrigin("https://link.solstone.app:8443")
        assertEquals(8443, customPort?.effectivePort)
        assertEquals("https://link.solstone.app:8443", customPort?.httpsBase)
        assertEquals("wss://link.solstone.app:8443", customPort?.wssBase)

        val ipv6 = parseProductionRelayOrigin("https://[2001:db8::1]")
        assertEquals("2001:db8::1", ipv6?.host)
        assertEquals(443, ipv6?.effectivePort)
        assertEquals("https://[2001:db8::1]", ipv6?.httpsBase)
        assertEquals("wss://[2001:db8::1]", ipv6?.wssBase)

        val ipv6Port = parseProductionRelayOrigin("https://[2001:db8::1]:9443/")
        assertEquals("2001:db8::1", ipv6Port?.host)
        assertEquals(9443, ipv6Port?.effectivePort)
        assertEquals("https://[2001:db8::1]:9443", ipv6Port?.httpsBase)
        assertEquals("wss://[2001:db8::1]:9443", ipv6Port?.wssBase)

        // Invalid origins
        assertEquals(null, parseProductionRelayOrigin("http://link.solstone.app"))
        assertEquals(null, parseProductionRelayOrigin("https://user:pass@link.solstone.app"))
        assertEquals(null, parseProductionRelayOrigin("https://link.solstone.app/some/path"))
        assertEquals(null, parseProductionRelayOrigin("https://link.solstone.app?query=1"))
        assertEquals(null, parseProductionRelayOrigin("https://link.solstone.app#fragment"))
        assertEquals(null, parseProductionRelayOrigin("https://link.solstone.app:0"))
        assertEquals(null, parseProductionRelayOrigin("https://link.solstone.app:65536"))
        assertEquals(null, parseProductionRelayOrigin("https://link solstone app"))
    }

    @Test
    fun extraJwtClaimsRejected() {
        val extraClaims = listOf("device_fp", "ca_fp", "predecessor", "arbitrary_key")
        val iat = 1700000000L
        val exp = 1800000000L

        for (claim in extraClaims) {
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
                "jti": "jwt-123",
                "$claim": "extra-val"
            }
            """.trimIndent())
            assertEquals(null, parseRelayAccessJwtV2(jwt, "jid-1"))
        }
    }

    @Test
    fun iatSkewBoundaryTests() {
        val nowSec = 1700000000L
        val expSec = 1800000000L
        val expRfc = "2027-01-15T08:00:00Z" // 1800000000

        // iat exactly at now + 60s -> valid
        val jwt60 = createJwt("""{"iss":"s","ver":2,"aud":"spl-relay","scope":"session.dial","instance_id":"j1","sub":"instance:j1","iat":${nowSec + 60},"exp":$expSec,"jti":"1"}""")
        val res60 = validateRelayAccessReady(2, "ready", "https://relay.solstone.app", "j1", jwt60, expRfc, "j1", nowEpochMs = nowSec * 1000L)
        assertTrue(res60 is RelayAccessResponse.Ready)

        // iat at now + 61s -> invalid (future)
        val jwt61 = createJwt("""{"iss":"s","ver":2,"aud":"spl-relay","scope":"session.dial","instance_id":"j1","sub":"instance:j1","iat":${nowSec + 61},"exp":$expSec,"jti":"1"}""")
        val res61 = validateRelayAccessReady(2, "ready", "https://relay.solstone.app", "j1", jwt61, expRfc, "j1", nowEpochMs = nowSec * 1000L)
        assertTrue(res61 is RelayAccessResponse.Malformed)
    }

    @Test
    fun tokenGraceBoundaryExactSeconds() {
        val expSec = 1700000000L
        val thirtyDaysSec = 30L * 86400L
        val lastGraceSec = expSec + thirtyDaysSec

        // Usable now: now < exp
        assertTrue(isRelayTokenUsableNow(expSec, (expSec - 1) * 1000L))
        assertTrue(!isRelayTokenUsableNow(expSec, expSec * 1000L))
        assertTrue(!isRelayTokenUsableNow(expSec, (expSec + 1) * 1000L))

        // Within refresh grace: now <= exp + 30d
        assertTrue(isRelayTokenWithinRefreshGrace(expSec, lastGraceSec * 1000L))
        assertTrue(!isRelayTokenWithinRefreshGrace(expSec, (lastGraceSec + 1) * 1000L))
    }

    @Test
    fun parseLegacyDeviceTokenJwtValidatesExactClaims() {
        val validFp = "sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        val jwt = createJwt("""
        {
            "iss": "solstone",
            "aud": "spl-relay",
            "scope": "session.dial",
            "sub": "device:dev-1",
            "device_fp": "$validFp",
            "iat": 1700000000,
            "exp": 1800000000,
            "jti": "j1"
        }
        """.trimIndent())

        val parsed = parseLegacyDeviceTokenJwt(jwt, "j1")
        assertTrue(parsed != null)
        assertEquals("device:dev-1", parsed?.sub)
        assertEquals(validFp, parsed?.deviceFp)

        // Invalid fp
        val badFpJwt = createJwt("""{"iss":"s","aud":"spl-relay","scope":"session.dial","sub":"device:d1","device_fp":"sha256:invalid","iat":1700000000,"exp":1800000000,"jti":"1"}""")
        assertEquals(null, parseLegacyDeviceTokenJwt(badFpJwt, "j1"))

        // Extra claim
        val extraClaimJwt = createJwt("""{"iss":"s","aud":"spl-relay","scope":"session.dial","sub":"device:d1","device_fp":"$validFp","ver":1,"iat":1700000000,"exp":1800000000,"jti":"1"}""")
        assertEquals(null, parseLegacyDeviceTokenJwt(extraClaimJwt, "j1"))
    }

    @Test
    fun decodePairRelayAccessTests() {
        val expectedOrigin = parseProductionRelayOrigin("https://relay.solstone.app")!!
        val iat = 1700000000L
        val exp = 1800000000L
        val expRfc = "2027-01-15T08:00:00Z"
        val jwt = createJwt("""{"iss":"s","ver":2,"aud":"spl-relay","scope":"session.dial","instance_id":"j1","sub":"instance:j1","iat":$iat,"exp":$exp,"jti":"1"}""")

        val validObj = PairRelayAccess.Object(mapOf(
            "protocol_version" to 2,
            "status" to "ready",
            "relay_origin" to "https://relay.solstone.app",
            "instance_id" to "j1",
            "device_token" to jwt,
            "expires_at" to expRfc,
        ))

        val decoded = decodePairRelayAccess(validObj, "j1", expectedOrigin, nowEpochMs = 1750000000000L)
        assertTrue(decoded is RelayAccessResponse.Ready)

        // Origin mismatch
        val mismatchObj = PairRelayAccess.Object(mapOf(
            "protocol_version" to 2,
            "status" to "ready",
            "relay_origin" to "https://other.solstone.app",
            "instance_id" to "j1",
            "device_token" to jwt,
            "expires_at" to expRfc,
        ))
        val mismatchDecoded = decodePairRelayAccess(mismatchObj, "j1", expectedOrigin, nowEpochMs = 1750000000000L)
        assertTrue(mismatchDecoded is RelayAccessResponse.Malformed)

        // Null and non-object
        assertTrue(decodePairRelayAccess(PairRelayAccess.PresentNull, "j1", expectedOrigin) is RelayAccessResponse.Malformed)
        assertTrue(decodePairRelayAccess(PairRelayAccess.NonObject("foo"), "j1", expectedOrigin) is RelayAccessResponse.Malformed)
    }

    @Test
    fun readyResponseToStringRedactsDeviceToken() {
        val ready = RelayAccessResponse.Ready(
            protocolVersion = 2,
            relayOrigin = "https://relay.solstone.app",
            instanceId = "inst",
            deviceToken = "secret-token-value",
            expiresAt = "2027-01-15T08:00:00Z",
        )
        val s = ready.toString()
        assertTrue(!s.contains("secret-token-value"))
        assertTrue(s.contains("<redacted>"))
    }
}
