// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.observer

import app.solstone.core.pl.HttpResponse
import app.solstone.core.pl.PlHttpClient
import app.solstone.core.pl.parseJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ObserverRegistrationTest {
    @Test
    fun registerPostsExpectedBodyAndMapsResponse() {
        val http = RecordingPlHttpClient(
            response = HttpResponse(
                200,
                emptyMap(),
                """{"key":"obs-1","prefix":"watch","name":"audio","ingest_url":"https://journal.example/ingest","protocol_version":2}"""
                    .toByteArray(),
            ),
        )
        val result = ObserverRegistration(http).register(
            platform = "rogbid",
            hostname = "watch-one",
            streamType = "audio",
            version = "1.0.0",
        )

        assertEquals("POST", http.lastRequest.method)
        assertEquals(REGISTER_PATH, http.lastRequest.path)
        assertEquals("application/json", http.lastRequest.headers["Content-Type"])
        val body = parseJson(http.lastRequest.body!!.toString(Charsets.UTF_8)) as Map<*, *>
        assertEquals("rogbid", body["platform"])
        assertEquals("watch-one", body["hostname"])
        assertEquals("audio", body["stream_type"])
        assertEquals("1.0.0", body["version"])
        assertEquals("obs-1", result.handle)
        assertEquals("watch", result.prefix)
        assertEquals("audio", result.stream)
        assertEquals("https://journal.example/ingest", result.ingestUrl)
        assertEquals(2, result.protocolVersion)
    }

    @Test
    fun registerFailsWhenKeyMissing() {
        val http = RecordingPlHttpClient(
            response = HttpResponse(
                200,
                emptyMap(),
                """{"name":"audio","ingest_url":"https://journal.example/ingest","protocol_version":2}""".toByteArray(),
            ),
        )

        assertFailsWith<IllegalArgumentException> {
            ObserverRegistration(http).register("rogbid", "watch-one", "audio", "1.0.0")
        }
    }

    @Test
    fun registerFailsWhenBodyIsNotJson() {
        val http = RecordingPlHttpClient(response = HttpResponse(200, emptyMap(), "not json".toByteArray()))

        assertFailsWith<IllegalArgumentException> {
            ObserverRegistration(http).register("rogbid", "watch-one", "audio", "1.0.0")
        }
    }

    @Test
    fun registerFailsOnNon200() {
        val http = RecordingPlHttpClient(response = HttpResponse(401, emptyMap(), "nope".toByteArray()))

        assertFailsWith<IllegalStateException> {
            ObserverRegistration(http).register("rogbid", "watch-one", "audio", "1.0.0")
        }
    }
}

data class RecordedRequest(
    val method: String,
    val path: String,
    val headers: Map<String, String>,
    val body: ByteArray?,
)

class RecordingPlHttpClient(var response: HttpResponse) : PlHttpClient {
    val requests = mutableListOf<RecordedRequest>()
    val lastRequest: RecordedRequest
        get() = requests.last()

    override fun request(method: String, path: String, headers: Map<String, String>, body: ByteArray?): HttpResponse {
        requests += RecordedRequest(method, path, headers, body)
        return response
    }
}
