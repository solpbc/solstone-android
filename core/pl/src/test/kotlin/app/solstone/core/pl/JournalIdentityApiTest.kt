// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.pl

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JournalIdentityApiTest {

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
    fun parseIdentityResponseVectorOnePrimary() {
        val json = """
            {
              "committed": true,
              "instance_id": "f30ed159-ef46-8e9c-913f-e49f0fe7d201",
              "mark": {
                "icon1": {
                  "name": "piano",
                  "svg": "<path d=\"M18.5 3H5.5C4.12 3 3 4.12 3 5.5v13C3 19.88 4.12 21 5.5 21h13c1.38 0 2.5-1.12 2.5-2.5v-13C21 4.12 19.88 3 18.5 3z\"/><path d=\"M7 3v9\"/><path d=\"M12 3v9\"/><path d=\"M17 3v9\"/><path d=\"M3 12h18\"/>",
                  "color": { "name": "blue", "hex": "#3b82f6" },
                  "rot": 45
                },
                "icon2": {
                  "name": "key",
                  "svg": "<path d=\"m15.5 7.5 2.3 2.3a1 1 0 0 0 1.4 0l2.1-2.1a1 1 0 0 0 0-1.4L19 4\"/><path d=\"m21 2-9.6 9.6\"/><circle cx=\"7.5\" cy=\"15.5\" r=\"5.5\"/>",
                  "color": { "name": "purple", "hex": "#a855f7" },
                  "rot": 0
                },
                "words": ["liquefy", "smock"]
              }
            }
        """.trimIndent()

        val resp = parseIdentityResponse(json)
        assertNotNull(resp)
        assertTrue(resp.committed)
        assertEquals("f30ed159-ef46-8e9c-913f-e49f0fe7d201", resp.instanceId)
        val mark = assertNotNull(resp.mark)
        assertEquals("piano", mark.icon1.name)
        assertEquals("blue", mark.icon1.colorName)
        assertEquals("#3b82f6", mark.icon1.colorHex)
        assertEquals(45, mark.icon1.rot)

        assertEquals("key", mark.icon2.name)
        assertEquals("purple", mark.icon2.colorName)
        assertEquals("#a855f7", mark.icon2.colorHex)
        assertEquals(0, mark.icon2.rot)

        assertEquals(listOf("liquefy", "smock"), mark.words)
    }

    @Test
    fun parseIdentityResponseVectorTwoRotZeroZero() {
        val json = """
            {
              "committed": true,
              "instance_id": "62bde3af-1ef4-8292-84db-1e5ac2c07e8b",
              "mark": {
                "icon1": {
                  "name": "turtle",
                  "svg": "<path d=\"m12 10 2 4v3a1 1 0 0 0 1 1h2a1 1 0 0 0 1-1v-3a8 8 0 1 0-16 0v3a1 1 0 0 0 1 1h2a1 1 0 0 0 1-1v-3l2-4\"/><path d=\"M4.82 7.9 8 10\"/><path d=\"M19.18 7.9 16 10\"/><path d=\"M10.5 5.5 8 10\"/><path d=\"M13.5 5.5 16 10\"/>",
                  "color": { "name": "pink", "hex": "#ec4899" },
                  "rot": 0
                },
                "icon2": {
                  "name": "pizza",
                  "svg": "<path d=\"m10 14 2 2\"/><path d=\"m15 9-6 6\"/><path d=\"M15 19v-4a3 3 0 0 0-3-3l-7.79-.31A12 12 0 0 1 20 5.48v.02A12.04 12.04 0 0 1 15 19Z\"/><path d=\"M3.14 11.23A12.03 12.03 0 0 1 5.48 4v.02A12 12 0 0 1 11.23 3.14\"/>",
                  "color": { "name": "cyan", "hex": "#06b6d4" },
                  "rot": 0
                },
                "words": ["distrust", "chokehold"]
              }
            }
        """.trimIndent()

        val resp = parseIdentityResponse(json)
        assertNotNull(resp)
        assertTrue(resp.committed)
        assertEquals("62bde3af-1ef4-8292-84db-1e5ac2c07e8b", resp.instanceId)
        val mark = assertNotNull(resp.mark)
        assertEquals("turtle", mark.icon1.name)
        assertEquals("pink", mark.icon1.colorName)
        assertEquals("#ec4899", mark.icon1.colorHex)
        assertEquals(0, mark.icon1.rot)

        assertEquals("pizza", mark.icon2.name)
        assertEquals("cyan", mark.icon2.colorName)
        assertEquals("#06b6d4", mark.icon2.colorHex)
        assertEquals(0, mark.icon2.rot)

        assertEquals(listOf("distrust", "chokehold"), mark.words)
    }

    @Test
    fun parseIdentityResponseVectorThreeMismatchColorNameAccepted() {
        val json = """
            {
              "committed": true,
              "instance_id": "f30ed159-ef46-8e9c-913f-e49f0fe7d201",
              "mark": {
                "icon1": {
                  "name": "piano",
                  "svg": "<path d=\"M18.5 3H5.5\"/>",
                  "color": { "name": "chartreuse", "hex": "#3b82f6" },
                  "rot": 45
                },
                "icon2": {
                  "name": "key",
                  "svg": "<circle cx=\"7.5\" cy=\"15.5\" r=\"5.5\"/>",
                  "color": { "name": "purple", "hex": "#a855f7" },
                  "rot": 0
                },
                "words": ["liquefy", "smock"]
              }
            }
        """.trimIndent()

        val resp = parseIdentityResponse(json)
        assertNotNull(resp)
        val mark = assertNotNull(resp.mark)
        assertEquals("chartreuse", mark.icon1.colorName)
        assertEquals("#3b82f6", mark.icon1.colorHex)
    }

    @Test
    fun parseIdentityResponseUncommittedNeutralReturnsNone() {
        val json = """{"committed":false,"instance_id":null,"mark":null}"""
        val resp = parseIdentityResponse(json)
        assertNotNull(resp)
        assertFalse(resp.committed)
        assertNull(resp.instanceId)
        assertNull(resp.mark)
    }

    @Test
    fun parseIdentityResponseRejectsInvalidInputs() {
        // Missing committed
        assertNull(parseIdentityResponse("""{"instance_id":"abc","mark":null}"""))
        // committed: false with mark
        assertNull(parseIdentityResponse("""{"committed":false,"instance_id":"abc","mark":{"icon1":null}}"""))
        // committed: true with null mark
        assertNull(parseIdentityResponse("""{"committed":true,"instance_id":"abc","mark":null}"""))
        // rot other than 0 or 45
        assertNull(parseIdentityResponse("""
            {"committed":true,"instance_id":"abc","mark":{"icon1":{"name":"a","svg":"<path d=\"M0 0\"/>","color":{"name":"c","hex":"#112233"},"rot":90},"icon2":{"name":"b","svg":"<path d=\"M0 0\"/>","color":{"name":"c","hex":"#112233"},"rot":0},"words":["w1","w2"]}}
        """.trimIndent()))
        // uppercase hex
        assertNull(parseIdentityResponse("""
            {"committed":true,"instance_id":"abc","mark":{"icon1":{"name":"a","svg":"<path d=\"M0 0\"/>","color":{"name":"c","hex":"#AABBCC"},"rot":0},"icon2":{"name":"b","svg":"<path d=\"M0 0\"/>","color":{"name":"c","hex":"#112233"},"rot":0},"words":["w1","w2"]}}
        """.trimIndent()))
        // duplicate words
        assertNull(parseIdentityResponse("""
            {"committed":true,"instance_id":"abc","mark":{"icon1":{"name":"a","svg":"<path d=\"M0 0\"/>","color":{"name":"c","hex":"#112233"},"rot":0},"icon2":{"name":"b","svg":"<path d=\"M0 0\"/>","color":{"name":"c","hex":"#112233"},"rot":0},"words":["word","word"]}}
        """.trimIndent()))
        // uppercase word
        assertNull(parseIdentityResponse("""
            {"committed":true,"instance_id":"abc","mark":{"icon1":{"name":"a","svg":"<path d=\"M0 0\"/>","color":{"name":"c","hex":"#112233"},"rot":0},"icon2":{"name":"b","svg":"<path d=\"M0 0\"/>","color":{"name":"c","hex":"#112233"},"rot":0},"words":["Word","other"]}}
        """.trimIndent()))
        // missing icon2
        assertNull(parseIdentityResponse("""
            {"committed":true,"instance_id":"abc","mark":{"icon1":{"name":"a","svg":"<path d=\"M0 0\"/>","color":{"name":"c","hex":"#112233"},"rot":0},"words":["w1","w2"]}}
        """.trimIndent()))
        // missing required field (color)
        assertNull(parseIdentityResponse("""
            {"committed":true,"instance_id":"abc","mark":{"icon1":{"name":"a","svg":"<path d=\"M0 0\"/>","rot":0},"icon2":{"name":"b","svg":"<path d=\"M0 0\"/>","color":{"name":"c","hex":"#112233"},"rot":0},"words":["w1","w2"]}}
        """.trimIndent()))
        // 1 word
        assertNull(parseIdentityResponse("""
            {"committed":true,"instance_id":"abc","mark":{"icon1":{"name":"a","svg":"<path d=\"M0 0\"/>","color":{"name":"c","hex":"#112233"},"rot":0},"icon2":{"name":"b","svg":"<path d=\"M0 0\"/>","color":{"name":"c","hex":"#112233"},"rot":0},"words":["onlyone"]}}
        """.trimIndent()))
        // 3 words
        assertNull(parseIdentityResponse("""
            {"committed":true,"instance_id":"abc","mark":{"icon1":{"name":"a","svg":"<path d=\"M0 0\"/>","color":{"name":"c","hex":"#112233"},"rot":0},"icon2":{"name":"b","svg":"<path d=\"M0 0\"/>","color":{"name":"c","hex":"#112233"},"rot":0},"words":["w1","w2","w3"]}}
        """.trimIndent()))
        // unparseable path d
        assertNull(parseIdentityResponse("""
            {"committed":true,"instance_id":"abc","mark":{"icon1":{"name":"a","svg":"<path d=\"not-a-path\"/>","color":{"name":"c","hex":"#112233"},"rot":0},"icon2":{"name":"b","svg":"<path d=\"M0 0\"/>","color":{"name":"c","hex":"#112233"},"rot":0},"words":["w1","w2"]}}
        """.trimIndent()))
        // disallowed SVG tag (e.g. script)
        assertNull(parseIdentityResponse("""
            {"committed":true,"instance_id":"abc","mark":{"icon1":{"name":"a","svg":"<script>alert(1)</script>","color":{"name":"c","hex":"#112233"},"rot":0},"icon2":{"name":"b","svg":"<path d=\"M0 0\"/>","color":{"name":"c","hex":"#112233"},"rot":0},"words":["first","second"]}}
        """.trimIndent()))
    }

    @Test
    fun fetchJournalIdentityReturnsSuccessOn200() {
        val json = """{"committed":false,"instance_id":null,"mark":null}"""
        val client = FakePlHttpClient { method, path, _, _, _ ->
            assertEquals("GET", method)
            assertEquals("/app/network/api/identity", path)
            HttpResponse(200, emptyMap(), json.toByteArray())
        }
        val result = fetchJournalIdentity(client)
        assertTrue(result is IdentityGetResult.Success)
        assertFalse(result.response.committed)
    }

    @Test
    fun fetchJournalIdentityReturnsNotFoundOn404() {
        val client = FakePlHttpClient { _, _, _, _, _ -> HttpResponse(404, emptyMap(), ByteArray(0)) }
        val result = fetchJournalIdentity(client)
        assertTrue(result is IdentityGetResult.NotFound)
    }
}
