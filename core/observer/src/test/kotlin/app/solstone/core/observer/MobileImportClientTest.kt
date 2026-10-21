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
import java.net.SocketTimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MobileImportClientTest {

    private data class RecordedRequest(
        val method: String,
        val path: String,
        val headers: Map<String, String>,
        val body: ByteArray?,
    )

    private class ScriptedPlHttpClient : PlHttpClient {
        val buffered = mutableListOf<RecordedRequest>()
        val streamed = mutableListOf<RecordedRequest>()
        var response: HttpResponse = HttpResponse(200, emptyMap(), ByteArray(0))
        var streamingHandler: ((StreamingBody) -> HttpResponse)? = null

        override fun request(
            method: String,
            path: String,
            headers: Map<String, String>,
            body: ByteArray?,
            maxResponseBytes: Int,
        ): HttpResponse {
            buffered += RecordedRequest(method, path, headers, body)
            return response
        }

        override fun requestStreaming(
            method: String,
            path: String,
            headers: Map<String, String>,
            body: StreamingBody,
            maxResponseBytes: Int,
        ): HttpResponse {
            streamed += RecordedRequest(method, path, headers, null)
            val handler = streamingHandler
            return if (handler != null) {
                handler(body)
            } else {
                response
            }
        }
    }

    private class CountingSource(
        private val bytes: ByteArray,
        private val onSecondCloseError: Boolean = false,
    ) : MobileImportSource {
        var readCalls = 0
        var maxRequestedLength = 0
        var closeCalls = 0
        private var offset = 0

        override fun read(target: ByteArray, offset: Int, length: Int): Int {
            readCalls++
            if (length > maxRequestedLength) maxRequestedLength = length
            if (this.offset >= bytes.size) return -1
            val toCopy = minOf(length, bytes.size - this.offset)
            System.arraycopy(bytes, this.offset, target, offset, toCopy)
            this.offset += toCopy
            return toCopy
        }

        override fun close() {
            closeCalls++
            if (onSecondCloseError && closeCalls > 1) {
                error("source closed more than once")
            }
        }
    }

    private class SimpleSource(private val bytes: ByteArray) : MobileImportSource {
        private var offset = 0
        override fun read(target: ByteArray, offset: Int, length: Int): Int {
            if (this.offset >= bytes.size) return -1
            val toCopy = minOf(length, bytes.size - this.offset)
            System.arraycopy(bytes, this.offset, target, offset, toCopy)
            this.offset += toCopy
            return toCopy
        }
        override fun close() {}
    }

    private class ThrowingOnReadSource : MobileImportSource {
        var readCalls = 0
        override fun read(target: ByteArray, offset: Int, length: Int): Int {
            readCalls++
            error("read should not be called")
        }
        override fun close() {}
    }

    private fun json(path: String): Map<*, *> = parseJson(text(path)) as Map<*, *>
    private fun text(path: String): String = bytes(path).toString(Charsets.UTF_8)
    private fun bytes(path: String): ByteArray =
        requireNotNull(MobileImportClientTest::class.java.classLoader?.getResourceAsStream(path)).use { it.readBytes() }

    private fun drainStreamingBody(body: StreamingBody, bufferSize: Int = 64 * 1024): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(bufferSize)
        while (true) {
            val read = body.readChunk(buf, 0, buf.size)
            if (read < 0) break
            if (read > 0) out.write(buf, 0, read)
        }
        return out.toByteArray()
    }

    @Test
    fun vectorKeySetMatches() {
        val vectorsDoc = json("mobile-import-v1/v1.vectors.json")
        val vectors = vectorsDoc["vectors"] as Map<*, *>
        val expectedKeys = setOf(
            "save_request",
            "save_staged_200",
            "save_replay_200",
            "start_request",
            "start_queued_200",
            "start_inprocess_200",
            "start_queued_replay_200",
            "start_inprocess_replay_200",
            "save_already_imported_400",
            "save_client_item_conflict_400",
            "start_already_imported_400",
            "start_nothing_to_import_409",
            "refusals",
        )
        assertEquals(expectedKeys, vectors.keys)
    }

    @Test
    fun saveRequestContractFixtureMatches() {
        val schema = json("mobile-import-v1/v1.schema.json")
        val routes = schema["x-solstone-routes"] as Map<*, *>
        val saveRoute = (routes["save"] as String).split(" ", limit = 2)

        val vectorsDoc = json("mobile-import-v1/v1.vectors.json")
        val vectors = vectorsDoc["vectors"] as Map<*, *>
        val saveRequest = vectors["save_request"] as Map<*, *>
        val clientItemId = saveRequest["client_item_id"] as String
        val importedVia = saveRequest["imported_via"] as String
        val fileMap = saveRequest["file"] as Map<*, *>
        val filename = fileMap["filename"] as String
        val contentType = fileMap["content_type"] as String

        val payload = "arbitrary test file content".toByteArray(Charsets.UTF_8)
        val http = ScriptedPlHttpClient()
        var drainedBytes: ByteArray? = null
        http.streamingHandler = { body ->
            drainedBytes = drainStreamingBody(body)
            HttpResponse(200, emptyMap(), "{\"status\":\"staged\",\"path\":\"/p\",\"timestamp\":\"ts\"}".toByteArray(Charsets.UTF_8))
        }

        val client = MobileImportClient(http)
        val fileItem = MobileImportFile(
            clientItemId = clientItemId,
            filename = filename,
            mediaType = contentType,
            length = payload.size.toLong(),
            source = SimpleSource(payload),
        )

        val outcome = client.save(fileItem)
        assertEquals(MobileImportOutcome.Saved("/p", "ts"), outcome)

        assertEquals(1, http.streamed.size)
        assertTrue(http.buffered.isEmpty())
        val call = http.streamed.single()
        assertEquals(saveRoute[0], call.method)
        assertEquals(saveRoute[1], call.path)
        assertEquals(1, call.headers.size)
        val contentTypeHeader = call.headers["Content-Type"] ?: error("missing Content-Type")
        assertTrue(contentTypeHeader.startsWith("multipart/form-data; boundary="))
        val boundary = contentTypeHeader.removePrefix("multipart/form-data; boundary=")

        val parts = parseMultipart(requireNotNull(drainedBytes), boundary)
        assertEquals(listOf("client_item_id", "imported_via", "file"), parts.map { it.name })
        assertEquals(clientItemId, parts[0].body.toString(Charsets.UTF_8))
        assertEquals(importedVia, parts[1].body.toString(Charsets.UTF_8))
        assertEquals(filename, parts[2].filename)
        assertEquals(contentType, parts[2].contentType)
        assertTrue(payload.contentEquals(parts[2].body))
    }

    @Test
    fun startRequestContractFixtureMatches() {
        val schema = json("mobile-import-v1/v1.schema.json")
        val routes = schema["x-solstone-routes"] as Map<*, *>
        val startRoute = (routes["start"] as String).split(" ", limit = 2)

        val vectorsDoc = json("mobile-import-v1/v1.vectors.json")
        val vectors = vectorsDoc["vectors"] as Map<*, *>
        val startRequest = vectors["start_request"] as Map<*, *>
        val path = startRequest["path"] as String
        val timestamp = startRequest["timestamp"] as String

        val http = ScriptedPlHttpClient()
        http.response = HttpResponse(200, emptyMap(), "{\"status\":\"ok\",\"task_id\":\"t\",\"import_id\":\"i\"}".toByteArray(Charsets.UTF_8))

        val client = MobileImportClient(http)
        val outcome = client.start(path, timestamp)
        assertEquals(MobileImportOutcome.Delivered, outcome)

        assertEquals(1, http.buffered.size)
        assertTrue(http.streamed.isEmpty())
        val call = http.buffered.single()
        assertEquals(startRoute[0], call.method)
        assertEquals(startRoute[1], call.path)
        assertEquals(mapOf("Content-Type" to "application/json"), call.headers)

        val expectedJson = "{\"path\":\"$path\",\"timestamp\":\"$timestamp\"}"
        val bodyText = call.body?.toString(Charsets.UTF_8)
        assertEquals(expectedJson, bodyText)

        val parsed = parseJson(requireNotNull(bodyText)) as Map<*, *>
        assertEquals(setOf("path", "timestamp"), parsed.keys)
        assertEquals(path, parsed["path"])
        assertEquals(timestamp, parsed["timestamp"])
    }

    @Test
    fun namedResponseVectorsClassified() {
        val vectorsDoc = json("mobile-import-v1/v1.vectors.json")
        val vectors = vectorsDoc["vectors"] as Map<*, *>

        val table = listOf(
            Triple("save_staged_200", MobileImportOperation.SAVE) { body: Map<*, *> ->
                MobileImportOutcome.Saved(body["path"] as String, body["timestamp"] as String)
            },
            Triple("save_replay_200", MobileImportOperation.SAVE) { body: Map<*, *> ->
                MobileImportOutcome.Saved(body["path"] as String, body["timestamp"] as String)
            },
            Triple("start_queued_200", MobileImportOperation.START) { _: Map<*, *> ->
                MobileImportOutcome.Delivered
            },
            Triple("start_inprocess_200", MobileImportOperation.START) { _: Map<*, *> ->
                MobileImportOutcome.Delivered
            },
            Triple("start_queued_replay_200", MobileImportOperation.START) { _: Map<*, *> ->
                MobileImportOutcome.Delivered
            },
            Triple("start_inprocess_replay_200", MobileImportOperation.START) { _: Map<*, *> ->
                MobileImportOutcome.Delivered
            },
            Triple("save_already_imported_400", MobileImportOperation.SAVE) { _: Map<*, *> ->
                MobileImportOutcome.Delivered
            },
            Triple("start_already_imported_400", MobileImportOperation.START) { _: Map<*, *> ->
                MobileImportOutcome.Delivered
            },
            Triple("save_client_item_conflict_400", MobileImportOperation.SAVE) { body: Map<*, *> ->
                MobileImportOutcome.NeedsAttention(body["error"] as String, body["reason_code"] as String, body["kind"] as String)
            },
            Triple("start_nothing_to_import_409", MobileImportOperation.START) { body: Map<*, *> ->
                MobileImportOutcome.NeedsAttention(body["error"] as String, body["reason_code"] as String, body["kind"] as String)
            },
        )

        for ((vectorName, op, expectedFn) in table) {
            val vector = vectors[vectorName] as Map<*, *>
            val status = (vector["status"] as BigDecimal).intValueExact()
            val bodyMap = vector["body"] as Map<*, *>
            val bodyBytes = toJson(bodyMap).toByteArray(Charsets.UTF_8)
            val expectedOutcome = expectedFn(bodyMap)
            val outcome = classifyMobileImportResponse(op, status, bodyBytes)
            assertEquals(expectedOutcome, outcome, "Failed for vector $vectorName")
        }
    }

    @Test
    fun refusalVectorsClassified() {
        val vectorsDoc = json("mobile-import-v1/v1.vectors.json")
        val vectors = vectorsDoc["vectors"] as Map<*, *>
        val refusals = vectors["refusals"] as List<*>

        for (raw in refusals) {
            val entry = raw as Map<*, *>
            val status = (entry["status"] as BigDecimal).intValueExact()
            val reasonCode = entry["reason_code"] as String
            val bodyMap = linkedMapOf<String, Any?>("error" to "refusal from the journal", "reason_code" to reasonCode)
            val bodyBytes = toJson(bodyMap).toByteArray(Charsets.UTF_8)

            for (op in listOf(MobileImportOperation.SAVE, MobileImportOperation.START)) {
                val outcome = classifyMobileImportResponse(op, status, bodyBytes)
                when (status) {
                    500, 503 -> assertEquals(MobileImportOutcome.Retry("http-$status"), outcome, "Status $status should retry for $op")
                    else -> assertEquals(
                        MobileImportOutcome.NeedsAttention("refusal from the journal", reasonCode, null),
                        outcome,
                        "Status $status reason $reasonCode for $op",
                    )
                }
            }
        }
    }

    @Test
    fun behaviour1_threeMibCountingSource() {
        val size = 3 * 1024 * 1024
        val sourceBytes = ByteArray(size) { (it and 0xFF).toByte() }
        val source = CountingSource(sourceBytes, onSecondCloseError = true)

        val http = ScriptedPlHttpClient()
        var drainedBytes: ByteArray? = null
        var declaredContentLength = -1L
        http.streamingHandler = { body ->
            assertEquals(0, source.readCalls, "Source should not be read before stream draining begins")
            declaredContentLength = body.contentLength
            drainedBytes = drainStreamingBody(body, 64 * 1024)
            body.close()
            body.close() // Second close should be safe
            HttpResponse(200, emptyMap(), "{\"status\":\"staged\",\"path\":\"/p\",\"timestamp\":\"ts\"}".toByteArray(Charsets.UTF_8))
        }

        val client = MobileImportClient(http)
        val fileItem = MobileImportFile(
            clientItemId = "id-3mb",
            filename = "large.bin",
            mediaType = "application/octet-stream",
            length = size.toLong(),
            source = source,
        )

        val outcome = client.save(fileItem)
        assertEquals(MobileImportOutcome.Saved("/p", "ts"), outcome)

        val bytes = requireNotNull(drainedBytes)
        assertEquals(bytes.size.toLong(), declaredContentLength)
        assertTrue(source.readCalls > 1, "Source should be read across multiple chunks")
        assertTrue(source.maxRequestedLength <= 64 * 1024, "Requested length should be at most 64 KiB")
        assertTrue(source.maxRequestedLength < size, "Requested length should not be the entire 3 MiB")
        assertEquals(1, source.closeCalls, "Source close should have run exactly once")

        val vectorsDoc = json("mobile-import-v1/v1.vectors.json")
        val vectors = vectorsDoc["vectors"] as Map<*, *>
        val saveRequest = vectors["save_request"] as Map<*, *>
        val importedVia = saveRequest["imported_via"] as String

        val contentType = http.streamed.single().headers["Content-Type"] ?: error("missing header")
        val boundary = contentType.removePrefix("multipart/form-data; boundary=")
        val parts = parseMultipart(bytes, boundary)
        assertEquals(3, parts.size)
        assertEquals("id-3mb", parts[0].body.toString(Charsets.UTF_8))
        assertEquals(importedVia, parts[1].body.toString(Charsets.UTF_8))
        assertEquals("large.bin", parts[2].filename)
        assertEquals("application/octet-stream", parts[2].contentType)
        assertTrue(sourceBytes.contentEquals(parts[2].body))
    }

    @Test
    fun behaviour2_textWhitespacePreserved() {
        val http = ScriptedPlHttpClient()
        var drainedBytes: ByteArray? = null
        http.streamingHandler = { body ->
            drainedBytes = drainStreamingBody(body)
            HttpResponse(200, emptyMap(), "{\"status\":\"staged\",\"path\":\"/p\",\"timestamp\":\"ts\"}".toByteArray(Charsets.UTF_8))
        }

        val client = MobileImportClient(http)
        val textValue = "  hello \r\n "
        val outcome = client.save(MobileImportText(clientItemId = "text-1", text = textValue))
        assertEquals(MobileImportOutcome.Saved("/p", "ts"), outcome)

        val contentType = http.streamed.single().headers["Content-Type"] ?: error("missing header")
        val boundary = contentType.removePrefix("multipart/form-data; boundary=")
        val parts = parseMultipart(requireNotNull(drainedBytes), boundary)

        assertEquals(listOf("client_item_id", "imported_via", "text"), parts.map { it.name })
        assertEquals(textValue, parts[2].body.toString(Charsets.UTF_8))
        assertNull(parts.firstOrNull { it.name == "file" })
    }

    @Test
    fun behaviour3_filenameSanitization() {
        val last = "a".repeat(126) + String(intArrayOf(0x1F600), 0, 1) + "b".repeat(62) + ".pdf"
        assertEquals(196, last.toByteArray(Charsets.UTF_8).size)
        val input = "\"\r\n\\" + last
        assertEquals(200, input.toByteArray(Charsets.UTF_8).size)

        val http = ScriptedPlHttpClient()
        var drainedBytes: ByteArray? = null
        http.streamingHandler = { body ->
            drainedBytes = drainStreamingBody(body)
            HttpResponse(200, emptyMap(), "{\"status\":\"staged\",\"path\":\"/p\",\"timestamp\":\"ts\"}".toByteArray(Charsets.UTF_8))
        }

        val client = MobileImportClient(http)
        val payload = "dummy".toByteArray(Charsets.UTF_8)
        client.save(MobileImportFile("id-fn", input, "application/pdf", payload.size.toLong(), SimpleSource(payload)))

        val contentType = http.streamed.single().headers["Content-Type"] ?: error("missing header")
        val boundary = contentType.removePrefix("multipart/form-data; boundary=")
        val parts = parseMultipart(requireNotNull(drainedBytes), boundary)
        val expectedSanitized = "a".repeat(124) + ".pdf"
        assertEquals(128, expectedSanitized.toByteArray(Charsets.UTF_8).size)
        assertEquals(expectedSanitized, parts[2].filename)

        // Separate save: literal "\"\\\\\r\n.pdf" parses as file.pdf
        var drainedBytes2: ByteArray? = null
        http.streamingHandler = { body ->
            drainedBytes2 = drainStreamingBody(body)
            HttpResponse(200, emptyMap(), "{\"status\":\"staged\",\"path\":\"/p\",\"timestamp\":\"ts\"}".toByteArray(Charsets.UTF_8))
        }
        client.save(MobileImportFile("id-fn2", "\"\\\\\r\n.pdf", "application/pdf", payload.size.toLong(), SimpleSource(payload)))
        val boundary2 = http.streamed.last().headers["Content-Type"]!!.removePrefix("multipart/form-data; boundary=")
        val parts2 = parseMultipart(requireNotNull(drainedBytes2), boundary2)
        assertEquals("file.pdf", parts2[2].filename)
    }

    @Test
    fun behaviour4_extensionOver128() {
        val http = ScriptedPlHttpClient()
        val payload = "dummy".toByteArray(Charsets.UTF_8)
        val client = MobileImportClient(http)

        // Filename "." + "a".repeat(200) parses as "." + "a".repeat(127)
        var drainedBytes1: ByteArray? = null
        http.streamingHandler = { body ->
            drainedBytes1 = drainStreamingBody(body)
            HttpResponse(200, emptyMap(), "{\"status\":\"staged\",\"path\":\"/p\",\"timestamp\":\"ts\"}".toByteArray(Charsets.UTF_8))
        }
        client.save(MobileImportFile("id-ext1", "." + "a".repeat(200), "", payload.size.toLong(), SimpleSource(payload)))
        val boundary1 = http.streamed.last().headers["Content-Type"]!!.removePrefix("multipart/form-data; boundary=")
        val parts1 = parseMultipart(requireNotNull(drainedBytes1), boundary1)
        assertEquals("." + "a".repeat(127), parts1[2].filename)

        // Filename "." + "a".repeat(126) + "é" parses as "." + "a".repeat(126), 127 UTF-8 bytes
        var drainedBytes2: ByteArray? = null
        http.streamingHandler = { body ->
            drainedBytes2 = drainStreamingBody(body)
            HttpResponse(200, emptyMap(), "{\"status\":\"staged\",\"path\":\"/p\",\"timestamp\":\"ts\"}".toByteArray(Charsets.UTF_8))
        }
        client.save(MobileImportFile("id-ext2", "." + "a".repeat(126) + "é", "", payload.size.toLong(), SimpleSource(payload)))
        val boundary2 = http.streamed.last().headers["Content-Type"]!!.removePrefix("multipart/form-data; boundary=")
        val parts2 = parseMultipart(requireNotNull(drainedBytes2), boundary2)
        val expected = "." + "a".repeat(126)
        assertEquals(expected, parts2[2].filename)
        assertEquals(127, expected.toByteArray(Charsets.UTF_8).size)
        assertTrue(!parts2[2].filename!!.contains("é"))
    }

    @Test
    fun behaviour5_payloadWithFakeBoundary() {
        val fakeBoundaryPayload = "prefix\r\n--not-the-boundary--\r\nsuffix".toByteArray(Charsets.UTF_8)
        val http = ScriptedPlHttpClient()
        val client = MobileImportClient(http)

        // File payload
        var fileDrained: ByteArray? = null
        http.streamingHandler = { body ->
            fileDrained = drainStreamingBody(body)
            HttpResponse(200, emptyMap(), "{\"status\":\"staged\",\"path\":\"/p\",\"timestamp\":\"ts\"}".toByteArray(Charsets.UTF_8))
        }
        client.save(MobileImportFile("id-fake", "file.bin", "application/octet-stream", fakeBoundaryPayload.size.toLong(), SimpleSource(fakeBoundaryPayload)))
        val boundaryFile = http.streamed.last().headers["Content-Type"]!!.removePrefix("multipart/form-data; boundary=")
        val fileParts = parseMultipart(requireNotNull(fileDrained), boundaryFile)
        assertTrue(fakeBoundaryPayload.contentEquals(fileParts[2].body))

        // Text payload
        var textDrained: ByteArray? = null
        http.streamingHandler = { body ->
            textDrained = drainStreamingBody(body)
            HttpResponse(200, emptyMap(), "{\"status\":\"staged\",\"path\":\"/p\",\"timestamp\":\"ts\"}".toByteArray(Charsets.UTF_8))
        }
        client.save(MobileImportText("id-fake-text", "prefix\r\n--not-the-boundary--\r\nsuffix"))
        val boundaryText = http.streamed.last().headers["Content-Type"]!!.removePrefix("multipart/form-data; boundary=")
        val textParts = parseMultipart(requireNotNull(textDrained), boundaryText)
        assertTrue(fakeBoundaryPayload.contentEquals(textParts[2].body))
    }

    @Test
    fun behaviour6_largeContentLengthWithoutAllocation() {
        val payloadLength = Int.MAX_VALUE.toLong() + 4096
        val source = ThrowingOnReadSource()
        val http = ScriptedPlHttpClient()
        var inspectedContentLength = -1L

        val vectorsDoc = json("mobile-import-v1/v1.vectors.json")
        val vectors = vectorsDoc["vectors"] as Map<*, *>
        val saveRequest = vectors["save_request"] as Map<*, *>
        val importedVia = saveRequest["imported_via"] as String

        http.streamingHandler = { body ->
            inspectedContentLength = body.contentLength
            HttpResponse(200, emptyMap(), "{\"status\":\"staged\",\"path\":\"/p\",\"timestamp\":\"ts\"}".toByteArray(Charsets.UTF_8))
        }

        val client = MobileImportClient(http)
        val item = MobileImportFile(
            clientItemId = "large-id",
            filename = "scan.pdf",
            mediaType = "application/pdf",
            length = payloadLength,
            source = source,
        )

        client.save(item)

        val boundary = http.streamed.single().headers["Content-Type"]!!.removePrefix("multipart/form-data; boundary=")
        val expectedPrefix = buildString {
            append("--").append(boundary).append("\r\n")
            append("Content-Disposition: form-data; name=\"client_item_id\"\r\n\r\n")
            append("large-id").append("\r\n")
            append("--").append(boundary).append("\r\n")
            append("Content-Disposition: form-data; name=\"imported_via\"\r\n\r\n")
            append(importedVia).append("\r\n")
            append("--").append(boundary).append("\r\n")
            append("Content-Disposition: form-data; name=\"file\"; filename=\"scan.pdf\"\r\n")
            append("Content-Type: application/pdf\r\n\r\n")
        }.toByteArray(Charsets.UTF_8)
        val expectedSuffix = "\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8)
        val expectedLength = expectedPrefix.size.toLong() + payloadLength + expectedSuffix.size.toLong()

        assertEquals(expectedLength, inspectedContentLength)
        assertEquals(0, source.readCalls, "Source read must stay 0")
    }

    @Test
    fun behaviour7_classifierRetryBranches() {
        // empty 200 body
        assertEquals(MobileImportOutcome.Retry("unreadable-answer"), classifyMobileImportResponse(MobileImportOperation.SAVE, 200, ByteArray(0)))
        assertEquals(MobileImportOutcome.Retry("unreadable-answer"), classifyMobileImportResponse(MobileImportOperation.START, 200, ByteArray(0)))

        // 200 object with no status
        assertEquals(
            MobileImportOutcome.Retry("answer-unknown"),
            classifyMobileImportResponse(MobileImportOperation.SAVE, 200, "{\"path\":\"/p\",\"timestamp\":\"ts\"}".toByteArray(Charsets.UTF_8)),
        )
        assertEquals(
            MobileImportOutcome.Retry("answer-unknown"),
            classifyMobileImportResponse(MobileImportOperation.START, 200, "{\"task_id\":\"t\",\"import_id\":\"i\"}".toByteArray(Charsets.UTF_8)),
        )

        // 200 status "held"
        assertEquals(
            MobileImportOutcome.Retry("answer-held"),
            classifyMobileImportResponse(MobileImportOperation.SAVE, 200, "{\"status\":\"held\",\"path\":\"/p\",\"timestamp\":\"ts\"}".toByteArray(Charsets.UTF_8)),
        )

        // missing, null, "", or numeric path, timestamp, task_id, or import_id
        val saveMissing = listOf(
            "{\"status\":\"staged\",\"timestamp\":\"ts\"}",
            "{\"status\":\"staged\",\"path\":null,\"timestamp\":\"ts\"}",
            "{\"status\":\"staged\",\"path\":\"\",\"timestamp\":\"ts\"}",
            "{\"status\":\"staged\",\"path\":123,\"timestamp\":\"ts\"}",
            "{\"status\":\"staged\",\"path\":\"/p\"}",
            "{\"status\":\"staged\",\"path\":\"/p\",\"timestamp\":null}",
            "{\"status\":\"staged\",\"path\":\"/p\",\"timestamp\":\"\"}",
            "{\"status\":\"staged\",\"path\":\"/p\",\"timestamp\":456}",
        )
        for (jsonStr in saveMissing) {
            assertEquals(MobileImportOutcome.Retry("answer-incomplete"), classifyMobileImportResponse(MobileImportOperation.SAVE, 200, jsonStr.toByteArray(Charsets.UTF_8)))
        }

        val startMissing = listOf(
            "{\"status\":\"ok\",\"import_id\":\"i\"}",
            "{\"status\":\"ok\",\"task_id\":null,\"import_id\":\"i\"}",
            "{\"status\":\"ok\",\"task_id\":\"\",\"import_id\":\"i\"}",
            "{\"status\":\"ok\",\"task_id\":123,\"import_id\":\"i\"}",
            "{\"status\":\"ok\",\"task_id\":\"t\"}",
            "{\"status\":\"ok\",\"task_id\":\"t\",\"import_id\":null}",
            "{\"status\":\"ok\",\"task_id\":\"t\",\"import_id\":\"\"}",
            "{\"status\":\"ok\",\"task_id\":\"t\",\"import_id\":456}",
        )
        for (jsonStr in startMissing) {
            assertEquals(MobileImportOutcome.Retry("answer-incomplete"), classifyMobileImportResponse(MobileImportOperation.START, 200, jsonStr.toByteArray(Charsets.UTF_8)))
        }

        // SAVE given start-shaped 200
        assertEquals(
            MobileImportOutcome.Retry("answer-ok"),
            classifyMobileImportResponse(MobileImportOperation.SAVE, 200, "{\"status\":\"ok\",\"task_id\":\"t\",\"import_id\":\"i\"}".toByteArray(Charsets.UTF_8)),
        )

        // START given save-shaped 200
        assertEquals(
            MobileImportOutcome.Retry("answer-staged"),
            classifyMobileImportResponse(MobileImportOperation.START, 200, "{\"status\":\"staged\",\"path\":\"/p\",\"timestamp\":\"ts\"}".toByteArray(Charsets.UTF_8)),
        )

        // HTTP 201 with valid staged body on SAVE
        assertEquals(
            MobileImportOutcome.Retry("http-201"),
            classifyMobileImportResponse(MobileImportOperation.SAVE, 201, "{\"status\":\"staged\",\"path\":\"/p\",\"timestamp\":\"ts\"}".toByteArray(Charsets.UTF_8)),
        )

        // HTTP 201 with valid ok body on START
        assertEquals(
            MobileImportOutcome.Retry("http-201"),
            classifyMobileImportResponse(MobileImportOperation.START, 201, "{\"status\":\"ok\",\"task_id\":\"t\",\"import_id\":\"i\"}".toByteArray(Charsets.UTF_8)),
        )

        // Staged 200 with path " p" and timestamp " t" is Saved(" p", " t")
        val stagedPadded = "{\"status\":\"staged\",\"path\":\" p\",\"timestamp\":\" t\"}".toByteArray(Charsets.UTF_8)
        assertEquals(MobileImportOutcome.Saved(" p", " t"), classifyMobileImportResponse(MobileImportOperation.SAVE, 200, stagedPadded))
    }

    @Test
    fun behaviour8_classifierRefusalAndTriple() {
        val tripleBody = "{\"error\":\"that action isn't available in the current state.\",\"reason_code\":\"invalid_operation_for_state\",\"detail\":\"content already imported\",\"kind\":\"already_imported\"}".toByteArray(Charsets.UTF_8)

        // 400 triple is Delivered for SAVE and START
        assertEquals(MobileImportOutcome.Delivered, classifyMobileImportResponse(MobileImportOperation.SAVE, 400, tripleBody))
        assertEquals(MobileImportOutcome.Delivered, classifyMobileImportResponse(MobileImportOperation.START, 400, tripleBody))

        // 401 and 403 with that same body are AuthStop
        assertEquals(MobileImportOutcome.AuthStop, classifyMobileImportResponse(MobileImportOperation.SAVE, 401, tripleBody))
        assertEquals(MobileImportOutcome.AuthStop, classifyMobileImportResponse(MobileImportOperation.START, 403, tripleBody))

        // 409 with that same body is NeedsAttention keeping the three strings
        assertEquals(
            MobileImportOutcome.NeedsAttention("that action isn't available in the current state.", "invalid_operation_for_state", "already_imported"),
            classifyMobileImportResponse(MobileImportOperation.SAVE, 409, tripleBody),
        )

        // 500 and 503 are Retry
        val errBody = "{\"error\":\"down\",\"reason_code\":\"import_metadata_failed\"}".toByteArray(Charsets.UTF_8)
        assertEquals(MobileImportOutcome.Retry("http-500"), classifyMobileImportResponse(MobileImportOperation.SAVE, 500, errBody))
        assertEquals(MobileImportOutcome.Retry("http-503"), classifyMobileImportResponse(MobileImportOperation.START, 503, errBody))

        // 400 with kind already_imported and reason_code other is NeedsAttention
        val otherReasonBody = "{\"error\":\"custom\",\"reason_code\":\"other\",\"kind\":\"already_imported\"}".toByteArray(Charsets.UTF_8)
        assertEquals(
            MobileImportOutcome.NeedsAttention("custom", "other", "already_imported"),
            classifyMobileImportResponse(MobileImportOperation.SAVE, 400, otherReasonBody),
        )

        // 400 {"error":"e","reason_code":"not_a_schema_code","kind":1} is NeedsAttention("e", "not_a_schema_code", null)
        val nonStringKind = "{\"error\":\"e\",\"reason_code\":\"not_a_schema_code\",\"kind\":1}".toByteArray(Charsets.UTF_8)
        assertEquals(
            MobileImportOutcome.NeedsAttention("e", "not_a_schema_code", null),
            classifyMobileImportResponse(MobileImportOperation.SAVE, 400, nonStringKind),
        )
    }

    @Test
    fun behaviour9_scriptedClientInvocations() {
        val schema = json("mobile-import-v1/v1.schema.json")
        val routes = schema["x-solstone-routes"] as Map<*, *>
        val saveRoute = (routes["save"] as String).split(" ", limit = 2)
        val startRoute = (routes["start"] as String).split(" ", limit = 2)

        val http = ScriptedPlHttpClient()
        val client = MobileImportClient(http)

        // One text save: one streaming POST to schema save route, 0 buffered requests
        http.streamingHandler = {
            HttpResponse(200, emptyMap(), "{\"status\":\"staged\",\"path\":\"/p\",\"timestamp\":\"ts\"}".toByteArray(Charsets.UTF_8))
        }
        val textItem = MobileImportText("text-id", "some text")
        val saveOutcome = client.save(textItem)
        assertEquals(MobileImportOutcome.Saved("/p", "ts"), saveOutcome)
        assertEquals(1, http.streamed.size)
        assertTrue(http.buffered.isEmpty())
        assertEquals(saveRoute[0], http.streamed[0].method)
        assertEquals(saveRoute[1], http.streamed[0].path)
        assertNull(http.streamed[0].body)
        assertEquals(1, http.streamed[0].headers.size)

        // Then one start: clear both lists first. buffered.size == 1, streamed empty, method and path from x-solstone-routes.start.
        http.buffered.clear()
        http.streamed.clear()
        http.response = HttpResponse(200, emptyMap(), "{\"status\":\"ok\",\"task_id\":\"t\",\"import_id\":\"i\"}".toByteArray(Charsets.UTF_8))
        val startOutcome = client.start("/p", "ts")
        assertEquals(MobileImportOutcome.Delivered, startOutcome)
        assertEquals(1, http.buffered.size)
        assertTrue(http.streamed.isEmpty())
        assertEquals(startRoute[0], http.buffered[0].method)
        assertEquals(startRoute[1], http.buffered[0].path)
        assertTrue(http.buffered[0].body != null)
        assertEquals(1, http.buffered[0].headers.size)

        // Then two save calls on that same MobileImportText. The streaming handler drains each body with the 64 KiB helper and keeps both byte arrays. streamed.size == 2, buffered empty. Parse both bodies. Both client_item_id part strings equal the item's clientItemId. The two boundaries match ^[0-9A-Za-z_-]{32,}$ and differ.
        http.buffered.clear()
        http.streamed.clear()
        val drainedBodies = mutableListOf<ByteArray>()
        http.streamingHandler = { body ->
            drainedBodies.add(drainStreamingBody(body))
            HttpResponse(200, emptyMap(), "{\"status\":\"staged\",\"path\":\"/p\",\"timestamp\":\"ts\"}".toByteArray(Charsets.UTF_8))
        }
        client.save(textItem)
        client.save(textItem)
        assertEquals(2, http.streamed.size)
        assertTrue(http.buffered.isEmpty())
        assertEquals(2, drainedBodies.size)

        val boundaryRegex = Regex("^[0-9A-Za-z_-]{32,}$")
        val b1 = http.streamed[0].headers["Content-Type"]!!.removePrefix("multipart/form-data; boundary=")
        val b2 = http.streamed[1].headers["Content-Type"]!!.removePrefix("multipart/form-data; boundary=")
        assertTrue(boundaryRegex.matches(b1), "Boundary 1 matches regex: $b1")
        assertTrue(boundaryRegex.matches(b2), "Boundary 2 matches regex: $b2")
        assertTrue(b1 != b2, "Boundaries should differ across saves")

        val parts1 = parseMultipart(drainedBodies[0], b1)
        val parts2 = parseMultipart(drainedBodies[1], b2)
        assertEquals(textItem.clientItemId, parts1.single { it.name == "client_item_id" }.body.toString(Charsets.UTF_8))
        assertEquals(textItem.clientItemId, parts2.single { it.name == "client_item_id" }.body.toString(Charsets.UTF_8))

        // Then a 500 from save: clear both lists, streamed.size == 1, buffered empty, outcome Retry.
        http.buffered.clear()
        http.streamed.clear()
        http.streamingHandler = {
            HttpResponse(500, emptyMap(), "server error".toByteArray(Charsets.UTF_8))
        }
        assertEquals(MobileImportOutcome.Retry("http-500"), client.save(textItem))
        assertEquals(1, http.streamed.size)
        assertTrue(http.buffered.isEmpty())

        // Then a 500 from start: clear both lists, buffered.size == 1, streamed empty, outcome Retry.
        http.buffered.clear()
        http.streamed.clear()
        http.response = HttpResponse(500, emptyMap(), "server error".toByteArray(Charsets.UTF_8))
        assertEquals(MobileImportOutcome.Retry("http-500"), client.start("/p", "ts"))
        assertEquals(1, http.buffered.size)
        assertTrue(http.streamed.isEmpty())
    }

    @Test
    fun mediaTypeStaysOneHeaderLine() {
        val http = ScriptedPlHttpClient()
        val client = MobileImportClient(http)
        val filePayload = "pdf file payload".toByteArray(Charsets.UTF_8)

        // Save 1: media type contains CRLF injection attempt
        var drained1: ByteArray? = null
        http.streamingHandler = { body ->
            drained1 = drainStreamingBody(body)
            HttpResponse(200, emptyMap(), "{\"status\":\"staged\",\"path\":\"/p\",\"timestamp\":\"ts\"}".toByteArray(Charsets.UTF_8))
        }
        val fileItem1 = MobileImportFile(
            clientItemId = "id-media-1",
            filename = "document.pdf",
            mediaType = "application/pdf\r\nX-Injected: yes",
            length = filePayload.size.toLong(),
            source = SimpleSource(filePayload),
        )
        val outcome1 = client.save(fileItem1)
        assertEquals(MobileImportOutcome.Saved("/p", "ts"), outcome1)

        val boundary1 = http.streamed.single().headers["Content-Type"]!!.removePrefix("multipart/form-data; boundary=")
        val parts1 = parseMultipart(requireNotNull(drained1), boundary1)
        assertEquals(3, parts1.size)
        assertEquals(listOf("client_item_id", "imported_via", "file"), parts1.map { it.name })

        val filePart1 = parts1[2]
        assertEquals("application/pdfX-Injected: yes", filePart1.contentType)
        assertTrue('\r' !in requireNotNull(filePart1.contentType))
        assertTrue('\n' !in requireNotNull(filePart1.contentType))

        val text1 = requireNotNull(drained1).toString(Charsets.UTF_8)
        assertTrue(text1.lines().none { it.startsWith("X-Injected:") }, "Must not contain injected header line")
        assertTrue(filePayload.contentEquals(filePart1.body))

        // Save 2: media type is "\r\n" which sanitizes to empty string
        http.buffered.clear()
        http.streamed.clear()
        var drained2: ByteArray? = null
        http.streamingHandler = { body ->
            drained2 = drainStreamingBody(body)
            HttpResponse(200, emptyMap(), "{\"status\":\"staged\",\"path\":\"/p\",\"timestamp\":\"ts\"}".toByteArray(Charsets.UTF_8))
        }
        val fileItem2 = MobileImportFile(
            clientItemId = "id-media-2",
            filename = "document.pdf",
            mediaType = "\r\n",
            length = filePayload.size.toLong(),
            source = SimpleSource(filePayload),
        )
        val outcome2 = client.save(fileItem2)
        assertEquals(MobileImportOutcome.Saved("/p", "ts"), outcome2)

        val boundary2 = http.streamed.single().headers["Content-Type"]!!.removePrefix("multipart/form-data; boundary=")
        val parts2 = parseMultipart(requireNotNull(drained2), boundary2)
        val filePart2 = parts2[2]
        assertNull(filePart2.contentType)

        val text2 = requireNotNull(drained2).toString(Charsets.UTF_8)
        assertTrue("Content-Type:" !in text2)
    }

    @Test
    fun behaviour10_refusal413AndNonJson() {
        val body413 = "{\"error\":\"too large\",\"reason_code\":\"multipart_part_too_large\"}".toByteArray(Charsets.UTF_8)
        assertEquals(
            MobileImportOutcome.NeedsAttention("too large", "multipart_part_too_large", null),
            classifyMobileImportResponse(MobileImportOperation.SAVE, 413, body413),
        )
        assertEquals(
            MobileImportOutcome.NeedsAttention("too large", "multipart_part_too_large", null),
            classifyMobileImportResponse(MobileImportOperation.START, 413, body413),
        )

        // HTTP 400 body not-json
        val nonJsonOutcome = classifyMobileImportResponse(MobileImportOperation.SAVE, 400, "not-json".toByteArray(Charsets.UTF_8))
        assertTrue(nonJsonOutcome is MobileImportOutcome.NeedsAttention)
        assertTrue(nonJsonOutcome.error.isNotEmpty())
        assertNull(nonJsonOutcome.reasonCode)
        assertNull(nonJsonOutcome.kind)
    }

    @Test
    fun behaviour11_exceptions() {
        val http = ScriptedPlHttpClient()
        val client = MobileImportClient(http)

        // requestStreaming throws IOException -> save returns Retry
        http.streamingHandler = { throw IOException("connection failed") }
        assertEquals(MobileImportOutcome.Retry("io-IOException"), client.save(MobileImportText("id", "txt")))

        // request throws SocketTimeoutException -> start returns Retry
        http.response = HttpResponse(200, emptyMap(), ByteArray(0)) // dummy, will throw instead
        val throwingHttpClient = object : PlHttpClient {
            override fun request(
                method: String,
                path: String,
                headers: Map<String, String>,
                body: ByteArray?,
                maxResponseBytes: Int,
            ): HttpResponse = throw SocketTimeoutException("timed out")
        }
        val timeoutClient = MobileImportClient(throwingHttpClient)
        assertEquals(MobileImportOutcome.Retry("io-SocketTimeoutException"), timeoutClient.start("/p", "ts"))

        // request throws IllegalStateException -> start throws IllegalStateException
        val iseHttpClient = object : PlHttpClient {
            override fun request(
                method: String,
                path: String,
                headers: Map<String, String>,
                body: ByteArray?,
                maxResponseBytes: Int,
            ): HttpResponse = throw IllegalStateException("unexpected state")
        }
        assertFailsWith<IllegalStateException> {
            MobileImportClient(iseHttpClient).start("/p", "ts")
        }

        // requestStreaming throws IllegalStateException -> save throws IllegalStateException
        http.streamingHandler = { throw IllegalStateException("unexpected state") }
        assertFailsWith<IllegalStateException> {
            client.save(MobileImportText("id", "txt"))
        }
    }
}
