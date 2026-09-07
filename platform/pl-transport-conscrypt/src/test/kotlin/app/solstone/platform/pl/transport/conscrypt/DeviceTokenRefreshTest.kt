// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.pl.transport.conscrypt

import app.solstone.core.pl.HttpResponse
import app.solstone.core.pl.parseJson
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DeviceTokenRefreshTest {
    @Test
    fun realLegacyHomeBindingAndExpiredGraceArePreserved() {
        val now = 1_000_000_000L
        val next = v2Jwt("home-123", now, now + 86400)
        val body = """{"protocol_version":2,"device_token":"$next","expires_at":"2001-09-10T01:46:40Z"}"""
        for (legacy in listOf(true, false)) {
            fun current(exp: Long) = if (legacy) legacyJwt(exp - 3600, exp) else v2Jwt("home-123", exp - 3600, exp)
            val within = FakePoster(HttpResponse(200, emptyMap(), body.toByteArray()))
            assertIs<DeviceTokenRefresh.Refreshed>(refreshDeviceToken(current(now - 30 * 86400 + 1), ORIGIN, within, now * 1000))
            assertEquals(1, within.requests.size)
            val boundary = FakePoster(HttpResponse(200, emptyMap(), body.toByteArray()))
            assertEquals(DeviceTokenRefresh.ReconnectNeeded, refreshDeviceToken(current(now - 30 * 86400), ORIGIN, boundary, now * 1000))
            assertEquals(0, boundary.requests.size)
        }
        val other = v2Jwt("other-home", now, now + 86400)
        val poster = FakePoster(HttpResponse(200, emptyMap(), body.replace(next, other).toByteArray()))
        assertEquals(DeviceTokenRefresh.TransientError, refreshDeviceToken(legacyJwt(now - 1, now + 1), ORIGIN, poster, now * 1000))
        assertEquals(1, poster.requests.size)
    }

    @Test
    fun replacementRequiresExplicitExactVersionAndMatchingWholeSecondExpiry() {
        val now = 1_000_000_000_000L
        val old = v2Jwt("home-123", 1_000_000_000, 1_000_000_500)
        val next = v2Jwt("home-123", 1_000_000_000, 1_000_086_400)
        val body = """{"protocol_version":2,"device_token":"$next","expires_at":"2001-09-10T01:46:40Z"}"""
        assertIs<DeviceTokenRefresh.Refreshed>(refreshDeviceToken(old, ORIGIN, FakePoster(HttpResponse(200, emptyMap(), body.toByteArray())), now))
        val badBodies = listOf(
            body.replace("\"protocol_version\":2,", ""),
            body.replace("\"protocol_version\":2", "\"protocol_version\":null"),
            body.replace("\"protocol_version\":2", "\"protocol_version\":\"2\""),
            body.replace("\"protocol_version\":2", "\"protocol_version\":2.5"),
            body.replace("\"protocol_version\":2", "\"protocol_version\":2.000000000000000001"),
            body.replace("\"protocol_version\":2", "\"protocol_version\":3"),
            body.replace(",\"expires_at\":\"2001-09-10T01:46:40Z\"", ""),
            body.replace("01:46:40Z", "01:46:40.5Z"),
            body.replace("01:46:40Z", "01:46:41Z"),
        )
        for (bad in badBodies) {
            val poster = FakePoster(HttpResponse(200, emptyMap(), bad.toByteArray()))
            assertEquals(DeviceTokenRefresh.TransientError, refreshDeviceToken(old, ORIGIN, poster, now))
            assertEquals(1, poster.requests.size)
        }
        for (broken in listOf(old.substringAfter('.'), "." + old.substringAfter('.'), old.substringBeforeLast('.') + ".")) {
            val poster = FakePoster()
            assertEquals(DeviceTokenRefresh.TransientError, refreshDeviceToken(broken, ORIGIN, poster, now))
            assertEquals(0, poster.requests.size)
        }
    }

    @Test
    fun postsJsonToNormalizedRefreshEndpointWithProtocolVersion2() {
        val now = 1_000_000_000_000L
        val oldToken = v2Jwt(instanceId = "home-123", iat = 1_000_000_000, exp = 1_000_000_500)
        val newToken = v2Jwt(instanceId = "home-123", iat = 1_000_000_000, exp = 1_000_086_400)
        val poster = FakePoster(HttpResponse(200, emptyMap(), """{"device_token":"$newToken","protocol_version":2,"expires_at":"2001-09-10T01:46:40Z"}""".toByteArray()))

        val result = refreshDeviceToken(oldToken, "https://link.solstone.app/", poster, nowEpochMs = now)

        assertEquals("https://link.solstone.app/token/refresh", poster.requests.single().url)
        assertEquals("application/json", poster.requests.single().headers["content-type"])
        val bodyMap = parseJson(poster.requests.single().body.toString(Charsets.UTF_8)) as Map<*, *>
        assertEquals(oldToken, bodyMap["device_token"])
        assertEquals(2, (bodyMap["protocol_version"] as Number).toInt())
        val refreshed = assertIs<DeviceTokenRefresh.Refreshed>(result)
        assertEquals(newToken, refreshed.deviceToken)
        assertEquals("2001-09-10T01:46:40Z", refreshed.expiresAt)
    }

    @Test
    fun legacyTokenCanBeUpgradedToV2() {
        val now = 1_000_000_000_000L
        val oldToken = legacyJwt(iat = 1_000_000_000, exp = 1_000_000_500)
        val newToken = v2Jwt(instanceId = "home-123", iat = 1_000_000_000, exp = 1_000_086_400)
        val poster = FakePoster(HttpResponse(200, emptyMap(), """{"device_token":"$newToken","protocol_version":2,"expires_at":"2001-09-10T01:46:40Z"}""".toByteArray()))

        val result = refreshDeviceToken(oldToken, ORIGIN, poster, nowEpochMs = now)

        val refreshed = assertIs<DeviceTokenRefresh.Refreshed>(result)
        assertEquals(newToken, refreshed.deviceToken)
    }

    @Test
    fun v2TokenCannotBeDowngradedToLegacy() {
        val now = 1_000_000_000_000L
        val oldToken = v2Jwt(instanceId = "home-123", iat = 1_000_000_000, exp = 1_000_000_500)
        val legacyReplacement = legacyJwt(iat = 1_000_000_000, exp = 1_000_086_400)
        val poster = FakePoster(HttpResponse(200, emptyMap(), """{"device_token":"$legacyReplacement"}""".toByteArray()))

        val result = refreshDeviceToken(oldToken, ORIGIN, poster, nowEpochMs = now)

        assertEquals(DeviceTokenRefresh.TransientError, result)
    }

    @Test
    fun v2ReplacementWithMismatchedInstanceIdFailsTransient() {
        val now = 1_000_000_000_000L
        val oldToken = v2Jwt(instanceId = "home-123", iat = 1_000_000_000, exp = 1_000_000_500)
        val mismatchedToken = v2Jwt(instanceId = "home-456", iat = 1_000_000_000, exp = 1_000_086_400)
        val poster = FakePoster(HttpResponse(200, emptyMap(), """{"device_token":"$mismatchedToken","protocol_version":2,"expires_at":"2001-09-10T01:46:40Z"}""".toByteArray()))

        val result = refreshDeviceToken(oldToken, ORIGIN, poster, nowEpochMs = now)

        assertEquals(DeviceTokenRefresh.TransientError, result)
    }

    @Test
    fun expiredTokenBeyondGraceReturnsReconnectNeededWithoutNetworkPost() {
        val now = 1_000_000_000_000L // 1,000,000,000 s
        // Expired 31 days ago: exp = 1,000,000,000 - 31 * 86400
        val exp = 1_000_000_000L - 31L * 86400L
        val token = v2Jwt(instanceId = "home-123", iat = exp - 3600, exp = exp)
        val poster = FakePoster()

        val result = refreshDeviceToken(token, ORIGIN, poster, nowEpochMs = now)

        assertEquals(DeviceTokenRefresh.ReconnectNeeded, result)
        assertEquals(0, poster.requests.size)
    }

    @Test
    fun invalidOriginReturnsTransientWithoutNetworkPost() {
        val now = 1_000_000_000_000L
        val token = v2Jwt(instanceId = "home-123", iat = 1_000_000_000, exp = 1_000_000_500)
        val poster = FakePoster()

        val result = refreshDeviceToken(token, "http://insecure.example.com", poster, nowEpochMs = now)

        assertEquals(DeviceTokenRefresh.TransientError, result)
        assertEquals(0, poster.requests.size)
    }

    @Test
    fun mapsMalformedOkToTransient() {
        val token = v2Jwt(instanceId = "home-123", iat = 1_000_000_000, exp = 1_000_000_500)
        assertEquals(
            DeviceTokenRefresh.TransientError,
            refreshDeviceToken(token, ORIGIN, FakePoster(HttpResponse(200, emptyMap(), "{}".toByteArray())), nowEpochMs = 1_000_000_000_000L),
        )
        assertEquals(
            DeviceTokenRefresh.TransientError,
            refreshDeviceToken(token, ORIGIN, FakePoster(HttpResponse(200, emptyMap(), "[]".toByteArray())), nowEpochMs = 1_000_000_000_000L),
        )
    }

    @Test
    fun mapsReconnectStatuses() {
        val token = v2Jwt(instanceId = "home-123", iat = 1_000_000_000, exp = 1_000_000_500)
        val now = 1_000_000_000_000L
        assertEquals(
            DeviceTokenRefresh.ReconnectNeeded,
            refreshDeviceToken(token, ORIGIN, FakePoster(HttpResponse(401, emptyMap(), """{"reason":"expired"}""".toByteArray())), nowEpochMs = now),
        )
        assertEquals(
            DeviceTokenRefresh.ReconnectNeeded,
            refreshDeviceToken(token, ORIGIN, FakePoster(HttpResponse(403, emptyMap(), ByteArray(0))), nowEpochMs = now),
        )
        assertEquals(
            DeviceTokenRefresh.ReconnectNeeded,
            refreshDeviceToken(token, ORIGIN, FakePoster(HttpResponse(404, emptyMap(), ByteArray(0))), nowEpochMs = now),
        )
    }

    @Test
    fun mapsOtherFailuresToTransient() {
        val token = v2Jwt(instanceId = "home-123", iat = 1_000_000_000, exp = 1_000_000_500)
        val now = 1_000_000_000_000L
        assertEquals(
            DeviceTokenRefresh.TransientError,
            refreshDeviceToken(token, ORIGIN, FakePoster(HttpResponse(401, emptyMap(), """{"reason":"other"}""".toByteArray())), nowEpochMs = now),
        )
        assertEquals(
            DeviceTokenRefresh.TransientError,
            refreshDeviceToken(token, ORIGIN, FakePoster(HttpResponse(500, emptyMap(), ByteArray(0))), nowEpochMs = now),
        )
        assertEquals(DeviceTokenRefresh.TransientError, refreshDeviceToken(token, ORIGIN, FakePoster(error = RuntimeException("down")), nowEpochMs = now))
    }

    @Test
    fun refreshedToStringRedactsToken() {
        val refreshed = DeviceTokenRefresh.Refreshed(deviceToken = "secret-jwt-token", expiresAt = "2026-01-01T00:00:00Z")
        val str = refreshed.toString()
        assertFalse(str.contains("secret-jwt-token"))
        assertTrue(str.contains("<redacted>"))
        assertTrue(str.contains("2026-01-01T00:00:00Z"))
    }

    private class FakePoster(
        private val response: HttpResponse = HttpResponse(200, emptyMap(), """{"device_token":"new"}""".toByteArray()),
        private val error: RuntimeException? = null,
    ) : HttpsPoster {
        val requests = mutableListOf<RequestRecord>()

        override fun post(url: String, body: ByteArray, headers: Map<String, String>): HttpResponse {
            error?.let { throw it }
            requests += RequestRecord(url, body, headers)
            return response
        }
    }

    private data class RequestRecord(val url: String, val body: ByteArray, val headers: Map<String, String>)

    private companion object {
        const val ORIGIN = "https://link.solstone.app"

        fun v2Jwt(instanceId: String, iat: Long, exp: Long): String {
            val payload = """{"iss":"https://link.solstone.app","sub":"instance:$instanceId","aud":"spl-relay","scope":"session.dial","ver":2,"instance_id":"$instanceId","iat":$iat,"exp":$exp,"jti":"test-jti"}"""
            val enc = Base64.getUrlEncoder().withoutPadding()
            return "${enc.encodeToString("{}".toByteArray())}.${enc.encodeToString(payload.toByteArray())}.sig"
        }

        fun legacyJwt(iat: Long, exp: Long): String {
            val payload = """{"iss":"https://link.solstone.app","sub":"device:dev-123","instance_id":"home-123","aud":"spl-relay","scope":"session.dial","device_fp":"sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef","iat":$iat,"exp":$exp,"jti":"test-jti"}"""
            val enc = Base64.getUrlEncoder().withoutPadding()
            return "${enc.encodeToString("{}".toByteArray())}.${enc.encodeToString(payload.toByteArray())}.sig"
        }
    }
}
