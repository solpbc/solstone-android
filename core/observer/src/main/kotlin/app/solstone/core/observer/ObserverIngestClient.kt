// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.observer

import app.solstone.core.model.BundleFile
import app.solstone.core.model.BundleManifest
import app.solstone.core.pl.HttpResponse
import app.solstone.core.pl.PlHttpClient
import app.solstone.core.pl.parseJson
import app.solstone.core.pl.toJson
import java.io.ByteArrayOutputStream

class ObserverIngestClient(
    private val http: PlHttpClient,
    private val boundaryProvider: () -> String,
) {
    fun ingest(
        manifest: BundleManifest,
        fileBytes: (BundleFile) -> ByteArray,
        host: String? = null,
        platform: String? = null,
    ): IngestOutcome {
        val boundary = boundaryProvider()
        val body = buildMultipartBody(boundary, manifest, fileBytes, host, platform)
        val response = http.request(
            method = "POST",
            path = INGEST_PATH,
            headers = mapOf(
                "Content-Type" to "multipart/form-data; boundary=$boundary",
                PROTOCOL_VERSION_HEADER to INGEST_PROTOCOL_VERSION.toString(),
            ),
            body = body,
        )
        return response.toIngestOutcome()
    }

    private fun buildMultipartBody(
        boundary: String,
        manifest: BundleManifest,
        fileBytes: (BundleFile) -> ByteArray,
        host: String?,
        platform: String?,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.writeJsonPart(boundary, "envelope", buildEnvelope(manifest, host, platform))
        for (file in manifest.files) {
            out.writeFilePart(boundary, "files", file.name, file.mediaType, fileBytes(file))
        }
        out.writeAscii("--$boundary--\r\n")
        return out.toByteArray()
    }

    private fun buildEnvelope(
        manifest: BundleManifest,
        host: String?,
        platform: String?,
    ): String {
        val root = linkedMapOf<String, Any?>(
            "day" to manifest.key.day,
            "segment" to manifest.key.segment,
            "files" to manifest.files.map { file ->
                linkedMapOf<String, Any?>("submitted" to file.name).apply {
                    if (file.sourceId.isNotBlank()) {
                        put("source", file.sourceId)
                    }
                }
            },
        )
        val meta = linkedMapOf<String, Any?>().apply {
            if (host != null) put("host", host)
            if (platform != null) put("platform", platform)
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

    private fun ByteArrayOutputStream.writeFilePart(
        boundary: String,
        name: String,
        filename: String,
        mediaType: String,
        bytes: ByteArray,
    ) {
        writeAscii("--$boundary\r\n")
        writeAscii("Content-Disposition: form-data; name=\"$name\"; filename=\"")
        writeUtf8(quoteFilename(filename))
        writeAscii("\"\r\n")
        if (mediaType.isNotEmpty()) {
            writeAscii("Content-Type: $mediaType\r\n")
        }
        writeAscii("\r\n")
        write(bytes)
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

sealed interface IngestOutcome {
    data class Accepted(val serverSegment: String) : IngestOutcome
    data class Collision(val serverSegment: String) : IngestOutcome
    data class Duplicate(val existingSegment: String?) : IngestOutcome
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
        "ok" -> requiredNonBlankString(root, "segment")
            ?.let(IngestOutcome::Accepted)
            ?: IngestOutcome.MalformedResponse("missing_segment")
        "collision" -> requiredNonBlankString(root, "segment")
            ?.let(IngestOutcome::Collision)
            ?: IngestOutcome.MalformedResponse("missing_segment")
        "duplicate" -> when (val existingSegment = root["existing_segment"]) {
            null -> IngestOutcome.Duplicate(null)
            is String -> IngestOutcome.Duplicate(existingSegment)
            else -> IngestOutcome.MalformedResponse("invalid_existing_segment")
        }
        "failed" -> IngestOutcome.Failed(root["detail"] as? String)
        else -> IngestOutcome.UnknownStatus(responseStatus)
    }
}

private fun requiredNonBlankString(root: Map<*, *>, key: String): String? =
    (root[key] as? String)?.takeIf { it.isNotBlank() }
