// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.observer

import app.solstone.core.model.BundleFile
import app.solstone.core.model.BundleManifest
import app.solstone.core.pl.HttpResponse
import app.solstone.core.pl.PlHttpClient
import app.solstone.core.pl.parseJson
import java.io.ByteArrayOutputStream

class ObserverIngestClient(
    private val http: PlHttpClient,
    private val boundaryProvider: () -> String,
) {
    fun ingest(
        manifest: BundleManifest,
        handle: String,
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
                OBSERVER_HANDLE_HEADER to handle,
                PROTOCOL_VERSION_HEADER to OBSERVER_PROTOCOL_VERSION.toString(),
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
        out.writeTextPart(boundary, "segment", manifest.key.segment)
        out.writeTextPart(boundary, "day", manifest.key.day)
        for (file in manifest.files) {
            out.writeFilePart(boundary, "files", file.name, file.mediaType, fileBytes(file))
        }
        if (host != null) {
            out.writeTextPart(boundary, "host", host)
        }
        if (platform != null) {
            out.writeTextPart(boundary, "platform", platform)
        }
        out.writeAscii("--$boundary--\r\n")
        return out.toByteArray()
    }

    private fun ByteArrayOutputStream.writeTextPart(boundary: String, name: String, value: String) {
        writeAscii("--$boundary\r\n")
        writeAscii("Content-Disposition: form-data; name=\"$name\"\r\n")
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
        writeAscii("Content-Disposition: form-data; name=\"$name\"; filename=\"$filename\"\r\n")
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
}

sealed interface IngestOutcome {
    data class Accepted(val serverSegment: String) : IngestOutcome
    data class Collision(val serverSegment: String) : IngestOutcome
    data class Duplicate(val existingSegment: String?) : IngestOutcome
    data class Rejected(val status: Int, val body: String) : IngestOutcome
}

private fun HttpResponse.toIngestOutcome(): IngestOutcome {
    if (status != 200) {
        return IngestOutcome.Rejected(status, bodyText())
    }
    val root = parseJson(bodyText()) as? Map<*, *> ?: throw IllegalArgumentException("observer ingest response must be an object")
    return when (val responseStatus = requiredString(root, "status")) {
        "ok" -> IngestOutcome.Accepted(requiredString(root, "segment"))
        "collision" -> IngestOutcome.Collision(requiredString(root, "segment"))
        "duplicate" -> IngestOutcome.Duplicate(root["existing_segment"] as? String)
        else -> throw IllegalArgumentException("unknown observer ingest status: $responseStatus")
    }
}

private fun requiredString(root: Map<*, *>, key: String): String =
    root[key] as? String ?: throw IllegalArgumentException("observer ingest response missing $key")
