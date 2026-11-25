// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.observer

import app.solstone.core.pl.HttpResponse
import app.solstone.core.pl.PlHttpClient
import app.solstone.core.pl.StreamingBody
import app.solstone.core.pl.parseJson
import app.solstone.core.pl.toJson
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ResumableImportClientTest {
    private data class Call(
        val method: String,
        val path: String,
        val headers: Map<String, String>,
        val body: ByteArray?,
        val contentLength: Long? = null,
    )

    private class FakeHttp : PlHttpClient {
        val calls = mutableListOf<Call>()
        val responses = ArrayDeque<() -> HttpResponse>()

        override fun request(
            method: String,
            path: String,
            headers: Map<String, String>,
            body: ByteArray?,
            maxResponseBytes: Int,
        ): HttpResponse {
            calls += Call(method, path, headers, body, body?.size?.toLong())
            return responses.removeFirst().invoke()
        }

        override fun requestStreaming(
            method: String,
            path: String,
            headers: Map<String, String>,
            body: StreamingBody,
            maxResponseBytes: Int,
        ): HttpResponse {
            val out = ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            try {
                while (true) {
                    val n = body.readChunk(buf, 0, buf.size)
                    if (n < 0) break
                    if (n > 0) out.write(buf, 0, n)
                }
            } finally {
                body.close()
            }
            calls += Call(method, path, headers, out.toByteArray(), body.contentLength)
            return responses.removeFirst().invoke()
        }
    }

    private class ArraySource(private val bytes: ByteArray) : MobileImportSource {
        private var pos = 0
        override fun read(target: ByteArray, offset: Int, length: Int): Int {
            if (pos >= bytes.size) return -1
            val n = minOf(length, bytes.size - pos)
            System.arraycopy(bytes, pos, target, offset, n)
            pos += n
            return n
        }
        override fun close() {}
    }

    private fun vectors(): Map<*, *> {
        val raw = requireNotNull(javaClass.classLoader?.getResourceAsStream("mobile-import-v1/v1.vectors.json"))
            .use { it.readBytes().toString(Charsets.UTF_8) }
        return (parseJson(raw) as Map<*, *>)["vectors"] as Map<*, *>
    }

    @Test
    fun resumableConfigReadsCapabilityAndAdvertisedLimitsWithoutHardcoding() {
        val http = FakeHttp()
        http.responses.add {
            HttpResponse(200, emptyMap(), """{"capabilities":["segments","resumable_import_uploads"]}""".toByteArray())
        }
        http.responses.add {
            HttpResponse(
                200,
                emptyMap(),
                """{"sources":[],"resumable":{"threshold_bytes":8388608,"chunk_bytes":2097152,"max_file_bytes":2147483648}}""".toByteArray(),
            )
        }
        val client = MobileImportClient(http)
        val result = client.resumableConfig()
        assertEquals(
            ResumableConfigOutcome.Supported(
                ResumableUploadConfig(
                    thresholdBytes = 8_388_608L,
                    chunkBytes = 2_097_152L,
                    maxFileBytes = 2_147_483_648L,
                ),
            ),
            result,
        )
        assertEquals(listOf("/api/system/status", "/app/import/api/sources"), http.calls.map { it.path })
    }

    @Test
    fun resumableConfigReturnsUnsupportedWhenCapabilityAbsent() {
        val http = FakeHttp()
        http.responses.add {
            HttpResponse(200, emptyMap(), """{"capabilities":["segments"]}""".toByteArray())
        }
        val client = MobileImportClient(http)
        assertEquals(ResumableConfigOutcome.Unsupported, client.resumableConfig())
        assertEquals(1, http.calls.size)
    }

    @Test
    fun resumableConfigReturnsAuthStopAndRetryOnErrors() {
        val authHttp = FakeHttp()
        authHttp.responses.add { HttpResponse(403, emptyMap(), ByteArray(0)) }
        assertEquals(ResumableConfigOutcome.AuthStop, MobileImportClient(authHttp).resumableConfig())

        val ioHttp = FakeHttp()
        ioHttp.responses.add { throw IOException("offline") }
        assertEquals(ResumableConfigOutcome.Retry("io-IOException"), MobileImportClient(ioHttp).resumableConfig())
    }

    @Test
    fun contractVectorsClassifyAllResumableResponses() {
        val v = vectors()

        fun statusAndBytes(name: String): Pair<Int, ByteArray> {
            val entry = v[name] as Map<*, *>
            val status = (entry["status"] as BigDecimal).intValueExact()
            val body = toJson(entry["body"]).toByteArray(Charsets.UTF_8)
            return status to body
        }

        val (c201, c201Body) = statusAndBytes("upload_created_201")
        assertEquals(
            ResumableCreateOutcome.Open(
                uploadId = "550e8400-e29b-41d4-a716-446655440000",
                nextOffset = 0L,
                totalBytes = 33_554_432L,
                chunkBytes = 4_194_304L,
                expiresAt = "2026-12-01T22:00:00Z",
            ),
            classifyResumableCreateResponse(c201, c201Body),
        )

        val (c200, c200Body) = statusAndBytes("upload_resumed_200")
        assertEquals(
            ResumableCreateOutcome.Open(
                uploadId = "550e8400-e29b-41d4-a716-446655440000",
                nextOffset = 4_194_304L,
                totalBytes = 33_554_432L,
                chunkBytes = 4_194_304L,
                expiresAt = "2026-12-01T22:05:00Z",
            ),
            classifyResumableCreateResponse(c200, c200Body),
        )

        val (stagedStatus, stagedBody) = statusAndBytes("upload_already_staged_200")
        assertEquals(
            ResumableCreateOutcome.AlreadyStaged(
                path = "/journal/imports/20261019_101500/scan.pdf",
                timestamp = "20261019_101500",
                replay = true,
            ),
            classifyResumableCreateResponse(stagedStatus, stagedBody),
        )

        val (impStatus, impBody) = statusAndBytes("upload_already_imported_200")
        assertEquals(
            ResumableCreateOutcome.AlreadyImported("20261019_101500"),
            classifyResumableCreateResponse(impStatus, impBody),
        )

        val (chunk200, chunk200Body) = statusAndBytes("upload_chunk_200")
        assertEquals(
            ResumableChunkOutcome.Accepted(
                nextOffset = 4_194_304L,
                totalBytes = 33_554_432L,
                complete = false,
                expiresAt = "2026-12-01T22:00:02Z",
            ),
            classifyResumableChunkResponse(chunk200, chunk200Body),
        )

        val (off409, off409Body) = statusAndBytes("upload_offset_mismatch_409")
        assertEquals(
            ResumableChunkOutcome.OffsetMismatch(4_194_304L),
            classifyResumableChunkResponse(off409, off409Body),
        )
        // Bodyless 409 from intermediary triggers GET resync.
        assertEquals(
            ResumableChunkOutcome.OffsetMismatch(null),
            classifyResumableChunkResponse(409, ByteArray(0)),
        )

        val (chk422, chk422Body) = statusAndBytes("upload_chunk_checksum_mismatch_422")
        assertEquals(
            ResumableChunkOutcome.ChunkChecksumMismatch(0L),
            classifyResumableChunkResponse(chk422, chk422Body),
        )

        val (space507, space507Body) = statusAndBytes("upload_insufficient_space_507")
        val createSpace = classifyResumableCreateResponse(space507, space507Body) as ResumableCreateOutcome.InsufficientSpace
        assertEquals("insufficient_journal_space", createSpace.reasonCode)
        assertEquals(301_989_888L, createSpace.requiredBytes)
        assertEquals(104_857_600L, createSpace.availableBytes)
        val chunkSpace = classifyResumableChunkResponse(space507, space507Body) as ResumableChunkOutcome.InsufficientSpace
        assertEquals("insufficient_journal_space", chunkSpace.reasonCode)

        val (st200, st200Body) = statusAndBytes("upload_status_200")
        assertEquals(
            ResumableStatusOutcome.Open(
                uploadId = "550e8400-e29b-41d4-a716-446655440000",
                nextOffset = 4_194_304L,
                totalBytes = 33_554_432L,
                chunkBytes = 4_194_304L,
                phase = "sending",
                expiresAt = "2026-12-01T22:00:02Z",
            ),
            classifyResumableStatusResponse(st200, st200Body),
        )

        val (commit200, commit200Body) = statusAndBytes("upload_committed_200")
        assertEquals(
            ResumableCommitOutcome.Saved("/journal/imports/20261019_101500/scan.pdf", "20261019_101500"),
            classifyResumableCommitResponse(commit200, commit200Body),
        )

        val (whole422, whole422Body) = statusAndBytes("upload_whole_checksum_mismatch_422")
        val mismatch = classifyResumableCommitResponse(whole422, whole422Body)
        assertTrue(mismatch is ResumableCommitOutcome.WholeFileChecksumMismatch)

        val (abort200, _) = statusAndBytes("upload_abort_200")
        assertEquals(ResumableAbortOutcome.Deleted, classifyResumableAbortResponse(abort200))
        assertEquals(ResumableAbortOutcome.Deleted, classifyResumableAbortResponse(404))
    }

    @Test
    fun createTreats404And405AsRouteUnavailableForOlderJournalFallback() {
        assertEquals(ResumableCreateOutcome.RouteUnavailable, classifyResumableCreateResponse(404, ByteArray(0)))
        assertEquals(ResumableCreateOutcome.RouteUnavailable, classifyResumableCreateResponse(405, ByteArray(0)))
    }

    @Test
    fun createChunkCommitAndAbortSendExpectedWireFormat() {
        val http = FakeHttp()
        val sha = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"
        val chunkPayload = "hello-resumable-chunk".toByteArray(Charsets.UTF_8)
        http.responses.add {
            HttpResponse(
                201,
                emptyMap(),
                """{"upload_id":"u-123","client_item_id":"item-1","status":"open","next_offset":0,"total_bytes":21,"chunk_bytes":4194304,"expires_at":"2026-12-02T00:00:00Z"}""".toByteArray(),
            )
        }
        http.responses.add {
            HttpResponse(
                200,
                emptyMap(),
                """{"upload_id":"u-123","next_offset":21,"total_bytes":21,"complete":true,"expires_at":"2026-12-02T00:00:01Z"}""".toByteArray(),
            )
        }
        http.responses.add {
            HttpResponse(
                200,
                emptyMap(),
                """{"status":"staged","path":"/imports/20261125_120000/export.zip","timestamp":"20261125_120000"}""".toByteArray(),
            )
        }
        http.responses.add {
            HttpResponse(200, emptyMap(), """{"deleted":true,"upload_id":"u-123"}""".toByteArray())
        }

        val client = MobileImportClient(http)
        val created = client.createUpload(
            ResumableUploadCreateRequest(
                clientItemId = "item-1",
                filename = "export.zip",
                mediaType = "application/zip",
                totalBytes = chunkPayload.size.toLong(),
                sha256 = sha.uppercase(),
                area = "apple_health",
                context = """{"keep_originals":false}""",
            ),
        )
        assertTrue(created is ResumableCreateOutcome.Open)

        val put = client.uploadChunk(
            uploadId = "u-123",
            offset = 0L,
            length = chunkPayload.size.toLong(),
            chunkSha256 = sha.uppercase(),
            source = ArraySource(chunkPayload),
        )
        assertTrue(put is ResumableChunkOutcome.Accepted)

        val commit = client.commitUpload("u-123", sha.uppercase())
        assertEquals(
            ResumableCommitOutcome.Saved("/imports/20261125_120000/export.zip", "20261125_120000"),
            commit,
        )

        val abort = client.abortUpload("u-123")
        assertEquals(ResumableAbortOutcome.Deleted, abort)

        val createCall = http.calls[0]
        assertEquals("POST", createCall.method)
        assertEquals("/app/import/api/uploads", createCall.path)
        val createBody = parseJson(createCall.body!!.toString(Charsets.UTF_8)) as Map<*, *>
        assertEquals("item-1", createBody["client_item_id"])
        assertEquals("android", createBody["imported_via"])
        assertEquals("phone", createBody["device_noun"])
        assertEquals(sha, createBody["sha256"])
        assertEquals("apple_health", createBody["area"])
        assertEquals("apple_health", createBody["source_hint"])
        assertEquals(mapOf("keep_originals" to false), createBody["context"])

        val putCall = http.calls[1]
        assertEquals("PUT", putCall.method)
        assertEquals("/app/import/api/uploads/u-123?offset=0", putCall.path)
        assertEquals("application/octet-stream", putCall.headers["Content-Type"])
        assertEquals(sha, putCall.headers["X-Chunk-Sha256"])
        assertNull(putCall.headers["Transfer-Encoding"])
        assertNull(putCall.headers["Expect"])
        assertEquals(chunkPayload.size.toLong(), putCall.contentLength)
        assertContentEquals(chunkPayload, putCall.body)

        val commitCall = http.calls[2]
        assertEquals("POST", commitCall.method)
        assertEquals("/app/import/api/uploads/u-123/commit", commitCall.path)
        assertEquals("""{"sha256":"$sha"}""", commitCall.body!!.toString(Charsets.UTF_8))

        val abortCall = http.calls[3]
        assertEquals("DELETE", abortCall.method)
        assertEquals("/app/import/api/uploads/u-123", abortCall.path)
    }
}
