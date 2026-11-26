// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.observer

import app.solstone.core.model.BundleFile
import app.solstone.core.model.BundleManifest
import app.solstone.core.pl.PlHttpClient
import app.solstone.core.pl.StreamingBody
import app.solstone.core.pl.HttpResponse
import app.solstone.core.pl.parseJson
import app.solstone.core.pl.toJson
import app.solstone.core.sources.CALENDAR_STREAM
import app.solstone.core.sources.OBSIDIAN_STREAM
import app.solstone.core.sources.SCREENSHOTS_STREAM
import java.io.IOException
import java.io.InputStream
import java.io.ByteArrayOutputStream

class ObserverIngestClient(
    private val http: PlHttpClient,
    private val boundaryProvider: () -> String,
) {
    fun ingest(
        manifest: BundleManifest,
        openFile: (BundleFile) -> InputStream,
        host: String? = null,
        platform: String? = null,
    ): List<IngestOutcome> {
        require(manifest.files.isNotEmpty()) { "an ingest manifest requires at least one file" }
        val outcomes = mutableListOf<IngestOutcome>()
        for ((sourceId, files) in manifest.files.groupBy(BundleFile::sourceId)) {
            val batches = if (sourceId == CALENDAR_STREAM || sourceId == OBSIDIAN_STREAM || sourceId == SCREENSHOTS_STREAM) {
                files.map { listOf(it) }
            } else {
                listOf(files)
            }
            for (batch in batches) {
                val sourceManifest = manifest.copy(files = batch)
                val boundary = boundaryProvider()
                val body = buildMultipartBody(boundary, sourceManifest, sourceId, openFile, host, platform)
                val outcome = try {
                    body.use {
                        http.requestStreaming(
                            method = "POST",
                            path = INGEST_PATH,
                            headers = mapOf(
                                "Content-Type" to "multipart/form-data; boundary=$boundary",
                                PROTOCOL_VERSION_HEADER to INGEST_PROTOCOL_VERSION.toString(),
                            ),
                            body = it,
                        ).toIngestOutcome()
                    }
                } catch (error: IOException) {
                    // The mux wraps a source's argument error when it resets its stream.
                    throw body.invalidSource ?: error
                }
                outcomes += outcome
                if (outcome is IngestOutcome.Rejected && (outcome.status == 401 || outcome.status == 403)) {
                    return outcomes
                }
            }
        }
        return outcomes
    }

    private fun buildMultipartBody(
        boundary: String,
        manifest: BundleManifest,
        sourceId: String,
        openFile: (BundleFile) -> InputStream,
        host: String?,
        platform: String?,
    ): MultipartBody {
        val parts = mutableListOf<MultipartPart>()
        fun framing(write: ByteArrayOutputStream.() -> Unit) {
            val bytes = ByteArrayOutputStream().apply(write).toByteArray()
            parts += MultipartPart(bytes.size.toLong(), bytes::inputStream)
        }
        framing { writeJsonPart(boundary, "envelope", buildEnvelope(manifest, sourceId, host, platform)) }
        for (file in manifest.files) {
            require(file.byteSize >= 0) { "negative sealed file length" }
            framing { writeFileHead(boundary, "files", file.name, file.mediaType) }
            parts += MultipartPart(file.byteSize) { openFile(file) }
            framing { writeAscii("\r\n") }
        }
        framing { writeAscii("--$boundary--\r\n") }
        return MultipartBody(parts)
    }

    private fun buildEnvelope(
        manifest: BundleManifest,
        sourceId: String,
        host: String?,
        platform: String?,
    ): String {
        val root = linkedMapOf<String, Any?>(
            "day" to manifest.key.day,
            "segment" to manifest.key.segment,
            "files" to manifest.files.map { file -> linkedMapOf("submitted" to file.name) },
        )
        if (sourceId.isNotBlank()) root["source"] = sourceId
        if (sourceId == SCREENSHOTS_STREAM) {
            val captured = manifest.screenshotCapturedAt
            val taken = manifest.screenshotTakenFrom
            require(captured != null && (taken == "platform" || taken == "file")) { "screenshot meta missing" }
            root["meta"] = linkedMapOf(
                "screenshot" to linkedMapOf(
                    "captured_at" to captured,
                    "taken_from" to taken,
                    "v" to 1,
                ),
            )
            return toJson(root)
        }
        val meta = linkedMapOf<String, Any?>().apply {
            if (host != null) put("host", host)
            if (platform != null) put("platform", platform)
            val zoneId = manifest.zoneId
            if (zoneId != null && zoneId in java.time.ZoneId.getAvailableZoneIds()) {
                put("tz", zoneId)
            }
            if (manifest.utcOffsetSeconds != null) {
                put("utc_offset_seconds", manifest.utcOffsetSeconds)
            }
        }
        if (meta.isNotEmpty()) {
            root["meta"] = meta
        }
        return toJson(root)
    }

    private fun ByteArrayOutputStream.writeJsonPart(boundary: String, name: String, value: String) {
        writeAscii("--$boundary\r\n")
        writeAscii("Content-Disposition: form-data; name=\"$name\"\r\n")
        writeAscii("Content-Type: application/json\r\n")
        writeAscii("\r\n")
        writeUtf8(value)
        writeAscii("\r\n")
    }

    private fun ByteArrayOutputStream.writeFileHead(
        boundary: String,
        name: String,
        filename: String,
        mediaType: String,
    ) {
        writeAscii("--$boundary\r\n")
        writeAscii("Content-Disposition: form-data; name=\"$name\"; filename=\"")
        writeUtf8(quoteFilename(filename))
        writeAscii("\"\r\n")
        if (mediaType.isNotEmpty()) {
            writeAscii("Content-Type: $mediaType\r\n")
        }
        writeAscii("\r\n")
    }

    private fun ByteArrayOutputStream.writeAscii(text: String) {
        write(text.toByteArray(Charsets.US_ASCII))
    }

    private fun ByteArrayOutputStream.writeUtf8(text: String) {
        write(text.toByteArray(Charsets.UTF_8))
    }

    private fun quoteFilename(filename: String): String = buildString(filename.length) {
        filename.forEach { char ->
            require(!Character.isISOControl(char)) {
                "multipart filename contains a control character"
            }
            if (char == '\\' || char == '"') append('\\')
            append(char)
        }
    }
}

private data class MultipartPart(val length: Long, val open: () -> InputStream)

/** Only the current part is open. Each sealed file must still match its own recorded length. */
private class MultipartBody(private val parts: List<MultipartPart>) : StreamingBody {
    override val contentLength = parts.fold(0L) { sum, part -> Math.addExact(sum, part.length) }
    private var index = 0
    private var current: InputStream? = null
    var invalidSource: IllegalArgumentException? = null
        private set
    private var consumed = 0L

    override fun readChunk(target: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        while (index < parts.size) {
            val part = parts[index]
            val stream = current ?: try {
                part.open().also { current = it }
            } catch (error: IllegalArgumentException) {
                invalidSource = error
                throw error
            }
            val count = stream.read(target, offset, length)
            if (count >= 0) {
                consumed += count
                if (consumed > part.length) throw IOException("sealed file grew during upload")
                return count
            }
            if (consumed != part.length) throw IOException("sealed file ended before its recorded length")
            current = null
            stream.close()
            consumed = 0
            index++
        }
        return -1
    }

    override fun close() {
        index = parts.size
        val stream = current
        current = null
        stream?.close()
    }
}

data class IngestFileDescriptor(
    val submitted: String?,
    val written: String?,
    val size: Long?,
    val sha256: String?,
    val disposition: String?,
)

sealed interface IngestDescriptors {
    data object Absent : IngestDescriptors
    data object NotAList : IngestDescriptors
    data class Listed(val items: List<IngestFileDescriptor>) : IngestDescriptors
}

sealed interface IngestOutcome {
    data class Accepted(val serverSegment: String, val descriptors: IngestDescriptors) : IngestOutcome
    data class Collision(val serverSegment: String, val descriptors: IngestDescriptors) : IngestOutcome
    data class Duplicate(val existingSegment: String?, val descriptors: IngestDescriptors) : IngestOutcome
    data class Failed(val detail: String?) : IngestOutcome
    data class UnknownStatus(val status: String) : IngestOutcome
    data class MalformedResponse(val reason: String) : IngestOutcome
    data class Rejected(val status: Int, val body: String) : IngestOutcome
}

private fun HttpResponse.toIngestOutcome(): IngestOutcome {
    if (status != 200) {
        return IngestOutcome.Rejected(status, bodyText())
    }
    val root = try {
        parseJson(bodyText()) as? Map<*, *>
    } catch (_: Exception) {
        null
    } ?: return IngestOutcome.MalformedResponse("invalid_json")
    val responseStatus = root["status"] as? String
        ?: return IngestOutcome.MalformedResponse("missing_status")
    if (responseStatus.isBlank()) return IngestOutcome.MalformedResponse("invalid_status")
    return when (responseStatus) {
        "ok" -> {
            val serverSegment = requiredNonBlankString(root, "segment")
                ?: return IngestOutcome.MalformedResponse("missing_segment")
            val descriptors = parseDescriptors(root)
            IngestOutcome.Accepted(serverSegment, descriptors)
        }
        "collision" -> {
            val serverSegment = requiredNonBlankString(root, "segment")
                ?: return IngestOutcome.MalformedResponse("missing_segment")
            val descriptors = parseDescriptors(root)
            IngestOutcome.Collision(serverSegment, descriptors)
        }
        "duplicate" -> {
            val descriptors = parseDescriptors(root)
            when (val existingSegment = root["existing_segment"]) {
                null -> IngestOutcome.Duplicate(null, descriptors)
                is String -> IngestOutcome.Duplicate(existingSegment, descriptors)
                else -> IngestOutcome.MalformedResponse("invalid_existing_segment")
            }
        }
        "failed" -> IngestOutcome.Failed(root["detail"] as? String)
        else -> IngestOutcome.UnknownStatus(responseStatus)
    }
}

private fun parseDescriptors(root: Map<*, *>): IngestDescriptors {
    if (!root.containsKey("file_descriptors")) return IngestDescriptors.Absent
    val raw = root["file_descriptors"] ?: return IngestDescriptors.Absent
    val list = raw as? List<*> ?: return IngestDescriptors.NotAList
    val items = list.map { item ->
        val map = item as? Map<*, *>
        if (map == null) {
            IngestFileDescriptor(null, null, null, null, null)
        } else {
            val sizeVal = map["size"]
            val sizeLong = when (sizeVal) {
                is java.math.BigDecimal -> try {
                    if (sizeVal.stripTrailingZeros().scale() <= 0) {
                        sizeVal.longValueExact()
                    } else {
                        null
                    }
                } catch (_: ArithmeticException) {
                    null
                }
                is Number -> try {
                    val bd = java.math.BigDecimal(sizeVal.toString())
                    if (bd.stripTrailingZeros().scale() <= 0) {
                        bd.longValueExact()
                    } else {
                        null
                    }
                } catch (_: Exception) {
                    null
                }
                else -> null
            }
            IngestFileDescriptor(
                submitted = map["submitted"] as? String,
                written = map["written"] as? String,
                size = sizeLong,
                sha256 = map["sha256"] as? String,
                disposition = map["disposition"] as? String,
            )
        }
    }
    return IngestDescriptors.Listed(items)
}

private fun requiredNonBlankString(root: Map<*, *>, key: String): String? =
    (root[key] as? String)?.takeIf { it.isNotBlank() }
