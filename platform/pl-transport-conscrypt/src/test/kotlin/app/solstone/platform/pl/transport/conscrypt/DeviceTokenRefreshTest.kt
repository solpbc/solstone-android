// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.pl.transport.conscrypt

import app.solstone.core.pl.HttpResponse
import app.solstone.core.pl.parseJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class DeviceTokenRefreshTest {
    @Test
    fun postsJsonToNormalizedRefreshEndpoint() {
        val poster = FakePoster(HttpResponse(200, emptyMap(), """{"device_token":"new","expires_at":"2026-01-01T00:00:00Z"}""".toByteArray()))

        val result = refreshDeviceToken("old", "https://link.solstone.app/", poster)

        assertEquals("https://link.solstone.app/token/refresh", poster.requests.single().url)
        assertEquals("application/json", poster.requests.single().headers["content-type"])
        assertEquals(mapOf("device_token" to "old"), parseJson(poster.requests.single().body.toString(Charsets.UTF_8)))
        val refreshed = assertIs<DeviceTokenRefresh.Refreshed>(result)
        assertEquals("new", refreshed.deviceToken)
        assertEquals("2026-01-01T00:00:00Z", refreshed.expiresAt)
    }

    @Test
    fun mapsMalformedOkToTransient() {
        assertEquals(
            DeviceTokenRefresh.TransientError,
            refreshDeviceToken("old", ORIGIN, FakePoster(HttpResponse(200, emptyMap(), "{}".toByteArray()))),
        )
        assertEquals(
            DeviceTokenRefresh.TransientError,
            refreshDeviceToken("old", ORIGIN, FakePoster(HttpResponse(200, emptyMap(), "[]".toByteArray()))),
        )
    }

    @Test
    fun mapsReconnectStatuses() {
        assertEquals(
            DeviceTokenRefresh.ReconnectNeeded,
            refreshDeviceToken("old", ORIGIN, FakePoster(HttpResponse(401, emptyMap(), """{"reason":"expired"}""".toByteArray()))),
        )
        assertEquals(
            DeviceTokenRefresh.ReconnectNeeded,
            refreshDeviceToken("old", ORIGIN, FakePoster(HttpResponse(403, emptyMap(), ByteArray(0)))),
        )
        assertEquals(
            DeviceTokenRefresh.ReconnectNeeded,
            refreshDeviceToken("old", ORIGIN, FakePoster(HttpResponse(404, emptyMap(), ByteArray(0)))),
        )
    }

    @Test
    fun mapsOtherFailuresToTransient() {
        assertEquals(
            DeviceTokenRefresh.TransientError,
            refreshDeviceToken("old", ORIGIN, FakePoster(HttpResponse(401, emptyMap(), """{"reason":"other"}""".toByteArray()))),
        )
        assertEquals(
            DeviceTokenRefresh.TransientError,
            refreshDeviceToken("old", ORIGIN, FakePoster(HttpResponse(500, emptyMap(), ByteArray(0)))),
        )
        assertEquals(DeviceTokenRefresh.TransientError, refreshDeviceToken("old", ORIGIN, FakePoster(error = RuntimeException("down"))))
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
    }
}
