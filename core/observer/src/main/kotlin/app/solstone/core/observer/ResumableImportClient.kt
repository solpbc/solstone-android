// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.observer

import app.solstone.core.pl.StreamingBody
import app.solstone.core.pl.parseJournalCapability
import app.solstone.core.pl.parseJson
import app.solstone.core.pl.readSystemStatus
import app.solstone.core.pl.toJson
import java.io.IOException
import java.math.BigDecimal
import java.util.Locale

const val CAPABILITY_RESUMABLE_IMPORT_UPLOADS = "resumable_import_uploads"
const val MOBILE_DEVICE_NOUN = "phone"
const val RESUMABLE_UPLOAD_PHASE_SENDING = "sending"
const val RESUMABLE_UPLOAD_PHASE_PAUSED = "paused"
const val RESUMABLE_UPLOAD_PHASE_CHECKING = "checking"

private const val SOURCES_PATH = "/app/import/api/sources"
private const val UPLOADS_PATH = "/app/import/api/uploads"
private const val MAX_CHUNK_BYTES_CEILING = 8L * 1024L * 1024L
private const val MAX_CONTROL_RESPONSE_BYTES = 256 * 1024
private val VALID_PHASES = setOf(
    RESUMABLE_UPLOAD_PHASE_SENDING,
    RESUMABLE_UPLOAD_PHASE_PAUSED,
    RESUMABLE_UPLOAD_PHASE_CHECKING,
)

/** Journal-advertised resumable upload thresholds and chunk limits. */
data class ResumableUploadConfig(
    val thresholdBytes: Long,
    val chunkBytes: Long,
    val maxFileBytes: Long,
)

sealed interface ResumableConfigOutcome {
    data class Supported(val config: ResumableUploadConfig) : ResumableConfigOutcome
    data object Unsupported : ResumableConfigOutcome
    data object AuthStop : ResumableConfigOutcome
    data class Retry(val cause: String) : ResumableConfigOutcome
}

data class ResumableUploadCreateRequest(
    val clientItemId: String,
    val filename: String,
    val mediaType: String,
    val totalBytes: Long,
    val sha256: String,
    val deviceNoun: String = MOBILE_DEVICE_NOUN,
    val caption: String? = null,
    val area: String? = null,
    val context: String? = null,
)

sealed interface ResumableCreateOutcome {
    data class Open(
        val uploadId: String,
        val nextOffset: Long,
        val totalBytes: Long,
        val chunkBytes: Long,
        val expiresAt: String?,
    ) : ResumableCreateOutcome

    data class AlreadyStaged(
        val path: String,
        val timestamp: String,
        val replay: Boolean,
    ) : ResumableCreateOutcome

    data class AlreadyImported(val importId: String? = null) : ResumableCreateOutcome
    data object RouteUnavailable : ResumableCreateOutcome
    data object AuthStop : ResumableCreateOutcome
    data class InsufficientSpace(
        val error: String,
        val reasonCode: String,
        val requiredBytes: Long?,
        val availableBytes: Long?,
    ) : ResumableCreateOutcome

    data class Retry(val cause: String) : ResumableCreateOutcome
    data class NeedsAttention(val error: String, val reasonCode: String?, val kind: String?) : ResumableCreateOutcome
}

sealed interface ResumableChunkOutcome {
    data class Accepted(
        val nextOffset: Long,
        val totalBytes: Long,
        val complete: Boolean,
        val expiresAt: String?,
    ) : ResumableChunkOutcome

    /** Journal rejected the offset; [nextOffset] is null when an intermediary stripped the 409 JSON body. */
    data class OffsetMismatch(val nextOffset: Long?) : ResumableChunkOutcome
    data class ChunkChecksumMismatch(val nextOffset: Long?) : ResumableChunkOutcome
    data class UploadNotFound(val error: String) : ResumableChunkOutcome
    data class InsufficientSpace(
        val error: String,
        val reasonCode: String,
        val requiredBytes: Long?,
        val availableBytes: Long?,
    ) : ResumableChunkOutcome

    data object AuthStop : ResumableChunkOutcome
    data class Retry(val cause: String) : ResumableChunkOutcome
    data class NeedsAttention(val error: String, val reasonCode: String?, val kind: String?) : ResumableChunkOutcome
}

sealed interface ResumableStatusOutcome {
    data class Open(
        val uploadId: String,
        val nextOffset: Long,
        val totalBytes: Long,
        val chunkBytes: Long,
        val phase: String,
        val expiresAt: String?,
    ) : ResumableStatusOutcome

    data object UploadNotFound : ResumableStatusOutcome
    data object AuthStop : ResumableStatusOutcome
    data class Retry(val cause: String) : ResumableStatusOutcome
}

sealed interface ResumableCommitOutcome {
    data class Saved(val path: String, val timestamp: String) : ResumableCommitOutcome
    data class Incomplete(val nextOffset: Long?) : ResumableCommitOutcome
    data class WholeFileChecksumMismatch(val error: String) : ResumableCommitOutcome
    data class UploadNotFound(val error: String) : ResumableCommitOutcome
    data class InsufficientSpace(
        val error: String,
        val reasonCode: String,
        val requiredBytes: Long?,
        val availableBytes: Long?,
    ) : ResumableCommitOutcome

    data object AuthStop : ResumableCommitOutcome
    data class Retry(val cause: String) : ResumableCommitOutcome
    data class NeedsAttention(val error: String, val reasonCode: String?, val kind: String?) : ResumableCommitOutcome
}

sealed interface ResumableAbortOutcome {
    data object Deleted : ResumableAbortOutcome
    data object AuthStop : ResumableAbortOutcome
    data class Retry(val cause: String) : ResumableAbortOutcome
}

/**
 * Probe the paired journal's resumable-import capability on `GET /api/system/status`, then read
 * the advertised `threshold_bytes`, `chunk_bytes`, and `max_file_bytes` from `GET /app/import/api/sources`.
 */
fun MobileImportClient.resumableConfig(fingerprint: String = ""): ResumableConfigOutcome {
    val statusResp = try {
        readSystemStatus(http, fingerprint)
    } catch (e: IOException) {
        return ResumableConfigOutcome.Retry(ioCause(e))
    }
    if (statusResp.status == 401 || statusResp.status == 403) {
        return ResumableConfigOutcome.AuthStop
    }
    val advertised = parseJournalCapability(
        statusResp.status,
        statusResp.bodyText(),
        CAPABILITY_RESUMABLE_IMPORT_UPLOADS,
    ) ?: return ResumableConfigOutcome.Retry(
        if (statusResp.status == 200) "unreadable-status" else "http-${statusResp.status}",
    )
    if (!advertised) return ResumableConfigOutcome.Unsupported

    val sourcesResp = try {
        http.request(
            method = "GET",
            path = SOURCES_PATH,
            headers = withLocaleHeader(mapOf("Cache-Control" to "no-cache")),
            body = null,
            maxResponseBytes = MAX_CONTROL_RESPONSE_BYTES,
        )
    } catch (e: IOException) {
        return ResumableConfigOutcome.Retry(ioCause(e))
    }
    return classifyResumableSourcesResponse(sourcesResp.status, sourcesResp.body)
}

fun MobileImportClient.createUpload(request: ResumableUploadCreateRequest): ResumableCreateOutcome {
    val payload = linkedMapOf<String, Any?>(
        "client_item_id" to request.clientItemId,
        "filename" to sanitizeFilename(request.filename),
        "total_bytes" to request.totalBytes,
        "imported_via" to MOBILE_IMPORTED_VIA,
        "device_noun" to request.deviceNoun,
    )
    val sanitizedMedia = sanitizeMediaType(request.mediaType)
    if (sanitizedMedia.isNotEmpty()) {
        payload["mime_type"] = sanitizedMedia
    }
    val normalizedSha = request.sha256.trim().lowercase(Locale.ROOT)
    if (normalizedSha.isNotEmpty()) {
        payload["sha256"] = normalizedSha
    }
    request.area?.let { area ->
        payload["area"] = area
        if (area != "quick" && area != "recording") {
            payload["source_hint"] = area
        }
    }
    request.context?.let { ctx ->
        val parsed = runCatching { parseJson(ctx) as? Map<*, *> }.getOrNull()
        payload["context"] = parsed ?: ctx
    }
    request.caption?.trim()?.takeIf { it.isNotEmpty() }?.let { caption ->
        payload["caption"] = caption
    }
    val bodyBytes = toJson(payload).toByteArray(Charsets.UTF_8)
    return try {
        val response = http.request(
            method = "POST",
            path = UPLOADS_PATH,
            headers = withLocaleHeader(mapOf("Content-Type" to "application/json")),
            body = bodyBytes,
            maxResponseBytes = MAX_CONTROL_RESPONSE_BYTES,
        )
        classifyResumableCreateResponse(response.status, response.body)
    } catch (e: IOException) {
        ResumableCreateOutcome.Retry(ioCause(e))
    }
}

fun MobileImportClient.uploadChunk(
    uploadId: String,
    offset: Long,
    length: Long,
    chunkSha256: String,
    source: MobileImportSource,
): ResumableChunkOutcome {
    if (!isSafeUploadId(uploadId) || offset < 0L || length <= 0L) {
        source.close()
        return ResumableChunkOutcome.NeedsAttention(GENERIC_REFUSAL, "invalid_chunk_length", null)
    }
    val body = RawChunkStreamingBody(length, source)
    return try {
        val response = http.requestStreaming(
            method = "PUT",
            path = "$UPLOADS_PATH/$uploadId?offset=$offset",
            headers = withLocaleHeader(
                linkedMapOf(
                    "Content-Type" to "application/octet-stream",
                    "X-Chunk-Sha256" to chunkSha256.trim().lowercase(Locale.ROOT),
                ),
            ),
            body = body,
            maxResponseBytes = MAX_CONTROL_RESPONSE_BYTES,
        )
        classifyResumableChunkResponse(response.status, response.body)
    } catch (e: IOException) {
        ResumableChunkOutcome.Retry(ioCause(e))
    } finally {
        body.close()
    }
}

fun MobileImportClient.uploadStatus(uploadId: String): ResumableStatusOutcome {
    if (!isSafeUploadId(uploadId)) {
        return ResumableStatusOutcome.UploadNotFound
    }
    return try {
        val response = http.request(
            method = "GET",
            path = "$UPLOADS_PATH/$uploadId",
            headers = mapOf("Cache-Control" to "no-cache"),
            body = null,
            maxResponseBytes = MAX_CONTROL_RESPONSE_BYTES,
        )
        classifyResumableStatusResponse(response.status, response.body)
    } catch (e: IOException) {
        ResumableStatusOutcome.Retry(ioCause(e))
    }
}

fun MobileImportClient.commitUpload(uploadId: String, sha256: String? = null): ResumableCommitOutcome {
    if (!isSafeUploadId(uploadId)) {
        return ResumableCommitOutcome.UploadNotFound(GENERIC_REFUSAL)
    }
    val payload = if (sha256 != null && sha256.isNotBlank()) {
        toJson(linkedMapOf("sha256" to sha256.trim().lowercase(Locale.ROOT)))
    } else {
        "{}"
    }
    return try {
        val response = http.request(
            method = "POST",
            path = "$UPLOADS_PATH/$uploadId/commit",
            headers = withLocaleHeader(mapOf("Content-Type" to "application/json")),
            body = payload.toByteArray(Charsets.UTF_8),
            maxResponseBytes = MAX_CONTROL_RESPONSE_BYTES,
        )
        classifyResumableCommitResponse(response.status, response.body)
    } catch (e: IOException) {
        ResumableCommitOutcome.Retry(ioCause(e))
    }
}

fun MobileImportClient.abortUpload(uploadId: String): ResumableAbortOutcome {
    if (!isSafeUploadId(uploadId)) {
        return ResumableAbortOutcome.Deleted
    }
    return try {
        val response = http.request(
            method = "DELETE",
            path = "$UPLOADS_PATH/$uploadId",
            headers = emptyMap(),
            body = null,
            maxResponseBytes = MAX_CONTROL_RESPONSE_BYTES,
        )
        classifyResumableAbortResponse(response.status)
    } catch (e: IOException) {
        ResumableAbortOutcome.Retry(ioCause(e))
    }
}

internal fun isSafeUploadId(raw: String): Boolean =
    raw.isNotEmpty() &&
        raw.length <= 128 &&
        raw.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' || it == '_' }

internal fun classifyResumableSourcesResponse(httpStatus: Int, body: ByteArray): ResumableConfigOutcome {
    if (httpStatus == 401 || httpStatus == 403) return ResumableConfigOutcome.AuthStop
    if (httpStatus == 404 || httpStatus == 405) return ResumableConfigOutcome.Unsupported
    if (httpStatus in 500..599) return ResumableConfigOutcome.Retry("http-$httpStatus")
    if (httpStatus != 200) return ResumableConfigOutcome.Retry("http-$httpStatus")
    val root = parseJsonObject(body) ?: return ResumableConfigOutcome.Retry("unreadable-sources")
    val resumable = root["resumable"] as? Map<*, *> ?: return ResumableConfigOutcome.Unsupported
    val threshold = jsonLong(resumable["threshold_bytes"])
    val chunk = jsonLong(resumable["chunk_bytes"])
    val maxFile = jsonLong(resumable["max_file_bytes"])
    if (threshold == null || threshold < 1L || chunk == null || chunk < 1L || maxFile == null || maxFile < 1L) {
        return ResumableConfigOutcome.Unsupported
    }
    return ResumableConfigOutcome.Supported(
        ResumableUploadConfig(
            thresholdBytes = threshold,
            chunkBytes = chunk.coerceAtMost(MAX_CHUNK_BYTES_CEILING),
            maxFileBytes = maxFile,
        ),
    )
}

internal fun classifyResumableCreateResponse(httpStatus: Int, body: ByteArray): ResumableCreateOutcome {
    if (httpStatus == 401 || httpStatus == 403) return ResumableCreateOutcome.AuthStop
    if (httpStatus == 404 || httpStatus == 405) return ResumableCreateOutcome.RouteUnavailable
    if (httpStatus == 507) {
        val root = parseJsonObject(body)
        return ResumableCreateOutcome.InsufficientSpace(
            error = (root?.get("error") as? String) ?: GENERIC_REFUSAL,
            reasonCode = (root?.get("reason_code") as? String) ?: "insufficient_journal_space",
            requiredBytes = jsonLong(root?.get("required_bytes")),
            availableBytes = jsonLong(root?.get("available_bytes")),
        )
    }
    if (httpStatus == 408 || httpStatus == 429 || httpStatus in 500..599) return ResumableCreateOutcome.Retry("http-$httpStatus")
    if (httpStatus == 200 || httpStatus == 201) {
        val root = parseJsonObject(body) ?: return ResumableCreateOutcome.Retry("unreadable-answer")
        return when (val status = root["status"] as? String) {
            "open" -> {
                val uploadId = (root["upload_id"] as? String)?.takeIf(::isSafeUploadId)
                val nextOffset = jsonLong(root["next_offset"])
                val totalBytes = jsonLong(root["total_bytes"])
                val chunkBytes = jsonLong(root["chunk_bytes"])
                if (uploadId != null && nextOffset != null && nextOffset >= 0L &&
                    totalBytes != null && totalBytes >= 1L && chunkBytes != null && chunkBytes >= 1L
                ) {
                    ResumableCreateOutcome.Open(
                        uploadId = uploadId,
                        nextOffset = nextOffset,
                        totalBytes = totalBytes,
                        chunkBytes = chunkBytes.coerceAtMost(MAX_CHUNK_BYTES_CEILING),
                        expiresAt = root["expires_at"] as? String,
                    )
                } else {
                    ResumableCreateOutcome.Retry("answer-incomplete")
                }
            }
            "already_staged" -> {
                val saveResp = root["save_response"] as? Map<*, *>
                    ?: return ResumableCreateOutcome.Retry("answer-incomplete")
                val saveStatus = saveResp["status"] as? String
                val path = (saveResp["path"] as? String)?.takeIf { it.isNotEmpty() }
                val timestamp = (saveResp["timestamp"] as? String)?.takeIf { it.isNotEmpty() }
                val replay = root["replay"] as? Boolean ?: false
                if (saveStatus == "staged" && path != null && timestamp != null) {
                    ResumableCreateOutcome.AlreadyStaged(path, timestamp, replay)
                } else {
                    ResumableCreateOutcome.Retry("answer-incomplete")
                }
            }
            "already_imported" -> {
                val importId = (root["import_id"] as? String)?.takeIf(::isValidImportId)
                ResumableCreateOutcome.AlreadyImported(importId)
            }
            else -> ResumableCreateOutcome.Retry(unexpectedAnswer(status, "open"))
        }
    }
    if (httpStatus in 200..299) return ResumableCreateOutcome.Retry("http-$httpStatus")

    val root = parseJsonObject(body)
        ?: return ResumableCreateOutcome.NeedsAttention(GENERIC_REFUSAL, null, null)
    val error = root["error"] as? String
    val reasonCode = root["reason_code"] as? String
    val kind = root["kind"] as? String
    if (httpStatus == 409 && reasonCode == "journal_busy") {
        return ResumableCreateOutcome.Retry("http-409-journal_busy")
    }
    if (httpStatus == 400 && reasonCode == "invalid_operation_for_state" && kind == "already_imported") {
        return ResumableCreateOutcome.AlreadyImported()
    }
    return if (error != null && reasonCode != null) {
        ResumableCreateOutcome.NeedsAttention(error, reasonCode, kind)
    } else {
        ResumableCreateOutcome.NeedsAttention(GENERIC_REFUSAL, null, null)
    }
}

internal fun classifyResumableChunkResponse(httpStatus: Int, body: ByteArray): ResumableChunkOutcome {
    if (httpStatus == 401 || httpStatus == 403) return ResumableChunkOutcome.AuthStop
    if (httpStatus == 507) {
        val root = parseJsonObject(body)
        return ResumableChunkOutcome.InsufficientSpace(
            error = (root?.get("error") as? String) ?: GENERIC_REFUSAL,
            reasonCode = (root?.get("reason_code") as? String) ?: "insufficient_journal_space",
            requiredBytes = jsonLong(root?.get("required_bytes")),
            availableBytes = jsonLong(root?.get("available_bytes")),
        )
    }
    if (httpStatus == 408 || httpStatus == 429 || httpStatus in 500..599) return ResumableChunkOutcome.Retry("http-$httpStatus")
    if (httpStatus == 200) {
        val root = parseJsonObject(body) ?: return ResumableChunkOutcome.Retry("unreadable-answer")
        val uploadId = (root["upload_id"] as? String)?.takeIf(::isSafeUploadId)
        val nextOffset = jsonLong(root["next_offset"])
        val totalBytes = jsonLong(root["total_bytes"])
        val complete = root["complete"] as? Boolean
        return if (uploadId != null && nextOffset != null && nextOffset >= 1L &&
            totalBytes != null && totalBytes >= 1L && complete != null
        ) {
            ResumableChunkOutcome.Accepted(
                nextOffset = nextOffset,
                totalBytes = totalBytes,
                complete = complete,
                expiresAt = root["expires_at"] as? String,
            )
        } else {
            ResumableChunkOutcome.Retry("answer-incomplete")
        }
    }
    if (httpStatus in 200..299) return ResumableChunkOutcome.Retry("http-$httpStatus")

    val root = parseJsonObject(body)
    val error = root?.get("error") as? String
    val reasonCode = root?.get("reason_code") as? String
    val kind = root?.get("kind") as? String
    if (httpStatus == 404) {
        return ResumableChunkOutcome.UploadNotFound(error ?: GENERIC_REFUSAL)
    }
    if (httpStatus == 409) {
        if (reasonCode == "journal_busy") return ResumableChunkOutcome.Retry("http-409-journal_busy")
        if (reasonCode == "offset_mismatch") {
            return ResumableChunkOutcome.OffsetMismatch(jsonLong(root["next_offset"]))
        }
        // Proxy or bridge may drop a 409 response body; fall back to GET /uploads/{id} resync.
        if (root == null || reasonCode == null) {
            return ResumableChunkOutcome.OffsetMismatch(null)
        }
    }
    if (httpStatus == 422 && reasonCode == "chunk_checksum_mismatch") {
        return ResumableChunkOutcome.ChunkChecksumMismatch(jsonLong(root["next_offset"]))
    }
    return if (error != null && reasonCode != null) {
        ResumableChunkOutcome.NeedsAttention(error, reasonCode, kind)
    } else {
        ResumableChunkOutcome.NeedsAttention(GENERIC_REFUSAL, null, null)
    }
}

internal fun classifyResumableStatusResponse(httpStatus: Int, body: ByteArray): ResumableStatusOutcome {
    if (httpStatus == 401 || httpStatus == 403) return ResumableStatusOutcome.AuthStop
    if (httpStatus == 404) return ResumableStatusOutcome.UploadNotFound
    if (httpStatus != 200) return ResumableStatusOutcome.Retry("http-$httpStatus")
    val root = parseJsonObject(body) ?: return ResumableStatusOutcome.Retry("unreadable-answer")
    val status = root["status"] as? String
    val uploadId = (root["upload_id"] as? String)?.takeIf(::isSafeUploadId)
    val nextOffset = jsonLong(root["next_offset"])
    val totalBytes = jsonLong(root["total_bytes"])
    val chunkBytes = jsonLong(root["chunk_bytes"])
    val phase = (root["phase"] as? String)?.takeIf { it in VALID_PHASES } ?: RESUMABLE_UPLOAD_PHASE_SENDING
    return if (status == "open" && uploadId != null && nextOffset != null && nextOffset >= 0L &&
        totalBytes != null && totalBytes >= 1L && chunkBytes != null && chunkBytes >= 1L
    ) {
        ResumableStatusOutcome.Open(
            uploadId = uploadId,
            nextOffset = nextOffset,
            totalBytes = totalBytes,
            chunkBytes = chunkBytes.coerceAtMost(MAX_CHUNK_BYTES_CEILING),
            phase = phase,
            expiresAt = root["expires_at"] as? String,
        )
    } else {
        ResumableStatusOutcome.Retry(unexpectedAnswer(status, "open"))
    }
}

internal fun classifyResumableCommitResponse(httpStatus: Int, body: ByteArray): ResumableCommitOutcome {
    if (httpStatus == 401 || httpStatus == 403) return ResumableCommitOutcome.AuthStop
    if (httpStatus == 507) {
        val root = parseJsonObject(body)
        return ResumableCommitOutcome.InsufficientSpace(
            error = (root?.get("error") as? String) ?: GENERIC_REFUSAL,
            reasonCode = (root?.get("reason_code") as? String) ?: "insufficient_journal_space",
            requiredBytes = jsonLong(root?.get("required_bytes")),
            availableBytes = jsonLong(root?.get("available_bytes")),
        )
    }
    if (httpStatus == 408 || httpStatus == 429 || httpStatus in 500..599) return ResumableCommitOutcome.Retry("http-$httpStatus")
    if (httpStatus == 200) {
        val root = parseJsonObject(body) ?: return ResumableCommitOutcome.Retry("unreadable-answer")
        val status = root["status"] as? String
        val path = (root["path"] as? String)?.takeIf { it.isNotEmpty() }
        val timestamp = (root["timestamp"] as? String)?.takeIf { it.isNotEmpty() }
        return if (status == "staged" && path != null && timestamp != null) {
            ResumableCommitOutcome.Saved(path, timestamp)
        } else {
            ResumableCommitOutcome.Retry(unexpectedAnswer(status, "staged"))
        }
    }
    if (httpStatus in 200..299) return ResumableCommitOutcome.Retry("http-$httpStatus")

    val root = parseJsonObject(body)
    val error = root?.get("error") as? String
    val reasonCode = root?.get("reason_code") as? String
    val kind = root?.get("kind") as? String
    if (httpStatus == 404) {
        return ResumableCommitOutcome.UploadNotFound(error ?: GENERIC_REFUSAL)
    }
    if (httpStatus == 409) {
        if (reasonCode == "journal_busy") return ResumableCommitOutcome.Retry("http-409-journal_busy")
        if (reasonCode == "incomplete_upload") {
            return ResumableCommitOutcome.Incomplete(jsonLong(root["next_offset"]))
        }
        if (root == null || reasonCode == null) {
            return ResumableCommitOutcome.Incomplete(null)
        }
    }
    if (httpStatus == 422 && reasonCode == "whole_file_checksum_mismatch") {
        return ResumableCommitOutcome.WholeFileChecksumMismatch(error ?: GENERIC_REFUSAL)
    }
    return if (error != null && reasonCode != null) {
        ResumableCommitOutcome.NeedsAttention(error, reasonCode, kind)
    } else {
        ResumableCommitOutcome.NeedsAttention(GENERIC_REFUSAL, null, null)
    }
}

internal fun classifyResumableAbortResponse(httpStatus: Int): ResumableAbortOutcome =
    when (httpStatus) {
        200, 404 -> ResumableAbortOutcome.Deleted
        401, 403 -> ResumableAbortOutcome.AuthStop
        else -> ResumableAbortOutcome.Retry("http-$httpStatus")
    }

private fun parseJsonObject(body: ByteArray): Map<*, *>? =
    if (body.isEmpty()) {
        null
    } else {
        try {
            parseJson(body.toString(Charsets.UTF_8)) as? Map<*, *>
        } catch (_: IllegalArgumentException) {
            null
        }
    }

private fun jsonLong(value: Any?): Long? =
    when (value) {
        is BigDecimal -> runCatching { value.longValueExact() }.getOrNull()
        is Number -> value.toLong()
        else -> null
    }

private class RawChunkStreamingBody(
    override val contentLength: Long,
    private val source: MobileImportSource,
) : StreamingBody {
    private var readSoFar = 0L
    private var closed = false

    override fun readChunk(target: ByteArray, offset: Int, length: Int): Int {
        if (closed) throw IOException("StreamingBody is closed")
        if (length <= 0) return 0
        val remaining = contentLength - readSoFar
        if (remaining <= 0L) return -1
        val want = if (remaining > length.toLong()) length else remaining.toInt()
        val n = source.read(target, offset, want)
        if (n <= 0 || n > want) {
            throw IOException("Chunk source read unexpected count $n (want $want, remaining $remaining)")
        }
        readSoFar += n
        return n
    }

    override fun close() {
        if (!closed) {
            closed = true
            source.close()
        }
    }
}
