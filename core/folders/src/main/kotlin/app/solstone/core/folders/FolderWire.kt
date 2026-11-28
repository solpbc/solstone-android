// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.folders

import app.solstone.core.pl.PlHttpClient
import app.solstone.core.pl.StreamingBody
import app.solstone.core.pl.fetchJournalCapability
import app.solstone.core.pl.parseJson
import app.solstone.core.pl.toJson
import app.solstone.core.sources.FOLDER_WIRE_SOURCE
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.Locale

private val PROTOCOL_HEADERS = mapOf("X-Solstone-Protocol-Version" to FOLDER_PROTOCOL_VERSION)

/** One staged companion file ready to include in a folder multipart upload. */
data class StagedPartPayload(
    val eventId: String,
    val partName: String,
    val contentHash: String,
    val mediaType: String,
    val byteLength: Long,
    val openStream: () -> InputStream,
)

data class PreparedFolderUpload(
    val envelopeJson: String,
    val activityFilename: String,
    val activityBytes: ByteArray,
    val companionParts: List<StagedPartPayload>,
)

sealed class FolderUploadReply {
    data class Outcome(val outcome: DeliveryOutcome) : FolderUploadReply()
    data object AuthStop : FolderUploadReply()
}

sealed class FacetsFetchResult {
    data class Loaded(val facets: List<FacetOption>) : FacetsFetchResult()
    data object AuthStop : FacetsFetchResult()
    data object Unavailable : FacetsFetchResult()
}

sealed class SupportedFetchResult {
    data class Loaded(val supported: SupportedList) : SupportedFetchResult()
    data object AuthStop : SupportedFetchResult()
    data object Unavailable : SupportedFetchResult()
}

sealed class EstimateFetchResult {
    data class Loaded(val estimate: BackfillEstimate) : EstimateFetchResult()
    data object AuthStop : EstimateFetchResult()
    data object Unavailable : EstimateFetchResult()
}

fun fetchFolderCapability(client: PlHttpClient, fingerprint: String = ""): Boolean? =
    fetchJournalCapability(client, FOLDER_WIRE_SOURCE, fingerprint)

fun fetchFolderSupported(client: PlHttpClient): SupportedFetchResult {
    val resp = client.request("GET", "/app/devices/ingest/folder/supported", PROTOCOL_HEADERS, null)
    if (resp.status == 401 || resp.status == 403) return SupportedFetchResult.AuthStop
    if (resp.status != 200) return SupportedFetchResult.Unavailable
    val parsed = SupportedList.parse(resp.bodyText()) ?: return SupportedFetchResult.Unavailable
    return SupportedFetchResult.Loaded(parsed)
}

fun fetchFolderFacets(client: PlHttpClient): FacetsFetchResult {
    val resp = client.request("GET", "/app/devices/ingest/folder/facets", PROTOCOL_HEADERS, null)
    if (resp.status == 401 || resp.status == 403) return FacetsFetchResult.AuthStop
    if (resp.status != 200) return FacetsFetchResult.Unavailable
    val root = runCatching { parseJson(resp.bodyText()) as? Map<*, *> }.getOrNull()
        ?: return FacetsFetchResult.Unavailable
    val rawList = root["facets"] as? List<*> ?: return FacetsFetchResult.Unavailable
    val facets = rawList.mapNotNull { entry ->
        val obj = entry as? Map<*, *> ?: return@mapNotNull null
        val name = (obj["name"] as? String)?.takeIf(String::isNotBlank) ?: return@mapNotNull null
        val title = (obj["title"] as? String)?.takeIf(String::isNotBlank) ?: name
        val color = (obj["color"] as? String)?.takeIf(String::isNotBlank)
        FacetOption(name = name, title = title, color = color)
    }
    return FacetsFetchResult.Loaded(facets)
}

fun fetchFolderEstimate(
    client: PlHttpClient,
    folderId: String?,
    items: List<EstimateItem>,
): EstimateFetchResult {
    val payload = linkedMapOf<String, Any?>()
    if (!folderId.isNullOrBlank()) {
        payload["folder_id"] = folderId
    }
    payload["items"] = items.take(MAX_ESTIMATE_ITEMS).map { item ->
        linkedMapOf<String, Any?>().apply {
            put("path", item.path)
            put("at", item.at)
            item.fileType?.let { put("file_type", it) }
            item.kindHint?.let { put("kind_hint", it) }
            item.contentHash?.let { put("content_hash", it) }
            put("has_content", item.hasContent)
        }
    }
    val headers = PROTOCOL_HEADERS + ("Content-Type" to "application/json")
    val resp = client.request(
        "POST",
        "/app/devices/ingest/folder/estimate",
        headers,
        toJson(payload).toByteArray(Charsets.UTF_8),
    )
    if (resp.status == 401 || resp.status == 403) return EstimateFetchResult.AuthStop
    if (resp.status != 200) return EstimateFetchResult.Unavailable
    val root = runCatching { parseJson(resp.bodyText()) as? Map<*, *> }.getOrNull()
        ?: return EstimateFetchResult.Unavailable
    val segments = (root["segments"] as? Number)?.toLong() ?: return EstimateFetchResult.Unavailable
    val days = (root["days"] as? Number)?.toLong() ?: return EstimateFetchResult.Unavailable
    val thoughts = (root["thoughts"] as? Number)?.toLong() ?: return EstimateFetchResult.Unavailable
    val already = (root["already_in_journal"] as? Number)?.toLong() ?: 0L
    val paused = root["backfill_paused"] as? Boolean ?: false
    return EstimateFetchResult.Loaded(
        BackfillEstimate(
            segments = segments,
            days = days,
            thoughts = thoughts,
            alreadyInJournal = already,
            backfillPaused = paused,
            fromJournal = true,
        ),
    )
}

/**
 * Builds the exact Protocol v3 `envelope` JSON and `folder_<sha256>.jsonl` bytes for one
 * five-minute folder segment delivery.
 */
fun prepareFolderUpload(
    delivery: SegmentDelivery,
    companionParts: List<StagedPartPayload>,
): PreparedFolderUpload {
    val partsByEventId = companionParts.associateBy { it.eventId }
    val activityOut = ByteArrayOutputStream()
    for (ev in delivery.events) {
        val observedMs = if (ev.observedMs != 0L) ev.observedMs else ev.eventMs
        val at = FolderStamp(ev.eventMs, ev.offsetSeconds).rfc3339()
        val observedAt = FolderStamp(observedMs, ev.offsetSeconds).rfc3339()
        val createdAt = ev.metadata.createdMs?.let { FolderStamp(it, ev.offsetSeconds).rfc3339() }
        val modifiedAt = FolderStamp(ev.metadata.modifiedMs, ev.offsetSeconds).rfc3339()
        val fileType = ev.metadata.extension?.takeIf {
            it.isNotEmpty() && it.length <= 64 && it.none { c -> c.code < 0x20 || c.code == 0x7F }
        }

        val staged = if (ev.verb != ActivityVerb.REMOVED) partsByEventId[ev.id] else null
        val kindHint = if (staged != null) ev.kind.kindHint else null
        val platform = buildPlatformObject(ev.metadata)

        val line = linkedMapOf<String, Any?>(
            "v" to 1,
            "event" to ev.verb.wireEvent,
            "path" to ev.relPath,
        )
        if (ev.verb == ActivityVerb.RENAMED && ev.previousPath != null) {
            line["previous_path"] = ev.previousPath
        }
        line["at"] = at
        line["observed_at"] = observedAt
        if (fileType != null) line["file_type"] = fileType
        line["size"] = ev.metadata.sizeBytes
        if (createdAt != null) line["created_at"] = createdAt
        line["modified_at"] = modifiedAt
        if (staged != null) {
            line["content_hash"] = staged.contentHash
            line["part"] = staged.partName
            if (kindHint != null) line["kind_hint"] = kindHint
        } else {
            line["part"] = null
        }
        if (platform != null) {
            line["platform"] = platform
        }
        activityOut.write(toJson(line).toByteArray(Charsets.UTF_8))
        activityOut.write('\n'.code)
    }

    val activityBytes = activityOut.toByteArray()
    val activityFilename = "folder_${sha256Hex(activityBytes)}.jsonl"

    val submittedFiles = buildList {
        add(linkedMapOf("submitted" to activityFilename))
        for (part in companionParts) {
            add(linkedMapOf("submitted" to part.partName))
        }
    }
    val folderMeta = linkedMapOf<String, Any?>(
        "v" to 1,
        "id" to delivery.folderId,
        "name" to delivery.folderName,
        "facet" to delivery.facet,
        "kinds" to delivery.kinds.enabledNames(),
    )
    val envelope = linkedMapOf<String, Any?>(
        "day" to delivery.window.day,
        "segment" to delivery.window.segment,
        "source" to FOLDER_WIRE_SOURCE,
        "files" to submittedFiles,
        "meta" to linkedMapOf("folder" to folderMeta),
    )
    return PreparedFolderUpload(
        envelopeJson = toJson(envelope),
        activityFilename = activityFilename,
        activityBytes = activityBytes,
        companionParts = companionParts,
    )
}

private fun cleanPlatformField(raw: String?): String? {
    val trimmed = raw?.trim() ?: return null
    if (trimmed.isEmpty() || trimmed.any { it.code < 0x20 || it.code == 0x7F }) return null
    val bytes = trimmed.toByteArray(Charsets.UTF_8)
    if (bytes.size <= 2048) return trimmed
    var len = trimmed.length
    while (len > 0 && trimmed.substring(0, len).toByteArray(Charsets.UTF_8).size > 2048) {
        len--
    }
    return trimmed.substring(0, len).takeIf { it.isNotEmpty() }
}

private fun buildPlatformObject(meta: PlatformMetadata): Map<String, Any?>? {
    val obj = linkedMapOf<String, Any?>()
    cleanPlatformField(meta.originUrl)?.let { obj["origin_url"] = it }
    cleanPlatformField(meta.referrerUrl)?.let { obj["referrer_url"] = it }
    cleanPlatformField(meta.downloadedBy)?.let { obj["downloaded_by"] = it }
    cleanPlatformField(meta.sourceApp)?.let { obj["source_app"] = it }
    return obj.takeIf { it.isNotEmpty() }
}

fun mediaTypeForPartName(partName: String): String = when (extensionLower(partName)) {
    "jsonl" -> "application/x-ndjson"
    "md", "markdown" -> "text/markdown"
    "txt", "log" -> "text/plain"
    "pdf" -> "application/pdf"
    "vtt" -> "text/vtt"
    "srt" -> "application/x-subrip"
    "png" -> "image/png"
    "jpg", "jpeg" -> "image/jpeg"
    "webp" -> "image/webp"
    else -> "application/octet-stream"
}

/** Uploads one prepared folder segment using Protocol v3 streaming multipart. */
fun uploadPreparedFolderSegment(
    client: PlHttpClient,
    prepared: PreparedFolderUpload,
    boundary: String = "solstoneFolder${System.nanoTime()}",
): FolderUploadReply {
    val body = buildFolderMultipartBody(prepared, boundary)
    val headers = PROTOCOL_HEADERS + (
        "Content-Type" to "multipart/form-data; boundary=$boundary"
    )
    val resp = client.requestStreaming("POST", "/app/devices/ingest", headers, body)
    return classifyFolderIngestResponse(resp.status, resp.bodyText())
}

/** Parses the Protocol v3 answers table for `POST /app/devices/ingest` (`source = "folder"`). */
fun classifyFolderIngestResponse(status: Int, bodyText: String): FolderUploadReply {
    if (status == 401 || status == 403) return FolderUploadReply.AuthStop
    val json = runCatching { parseJson(bodyText) as? Map<*, *> }.getOrNull()
    val refusedPaths = extractRefusedPaths(json)
    return when (status) {
        200 -> {
            val statusWord = (json?.get("status") as? String)?.lowercase(Locale.ROOT)
            when (statusWord) {
                "duplicate" -> FolderUploadReply.Outcome(DeliveryOutcome.Delivered)
                "ok" -> if (refusedPaths.isEmpty()) {
                    FolderUploadReply.Outcome(DeliveryOutcome.Delivered)
                } else {
                    FolderUploadReply.Outcome(DeliveryOutcome.DeliveredWithRefusedContent(refusedPaths))
                }
                else -> FolderUploadReply.Outcome(DeliveryOutcome.Hold)
            }
        }
        409 -> {
            val reason = (json?.get("reason_code") as? String)?.lowercase(Locale.ROOT)
            when (reason) {
                "segment_removed", "duplicate", "collision" -> FolderUploadReply.Outcome(DeliveryOutcome.Delivered)
                else -> FolderUploadReply.Outcome(DeliveryOutcome.Hold)
            }
        }
        400 -> {
            val reason = (json?.get("reason_code") as? String)?.lowercase(Locale.ROOT)
            when (reason) {
                "segment_removed", "duplicate", "collision" -> FolderUploadReply.Outcome(DeliveryOutcome.Delivered)
                "folder_file_invalid" -> FolderUploadReply.Outcome(DeliveryOutcome.AllFilesRefused(refusedPaths))
                "folder_ahead" -> FolderUploadReply.Outcome(DeliveryOutcome.Hold)
                "folder_invalid" -> FolderUploadReply.Outcome(DeliveryOutcome.InvalidPermanent)
                else -> FolderUploadReply.Outcome(DeliveryOutcome.InvalidPermanent)
            }
        }
        503 -> FolderUploadReply.Outcome(DeliveryOutcome.Hold)
        in 500..599 -> FolderUploadReply.Outcome(DeliveryOutcome.Hold)
        else -> FolderUploadReply.Outcome(DeliveryOutcome.Hold)
    }
}

private fun extractRefusedPaths(json: Map<*, *>?): List<String> {
    val list = json?.get("refused_files") as? List<*> ?: return emptyList()
    return list.mapNotNull { entry ->
        val obj = entry as? Map<*, *> ?: return@mapNotNull null
        (obj["path"] as? String)?.takeIf(String::isNotBlank)
    }
}

private data class FolderMultipartSlice(val length: Long, val open: () -> InputStream)

fun buildFolderMultipartBody(
    prepared: PreparedFolderUpload,
    boundary: String,
): StreamingBody {
    val slices = mutableListOf<FolderMultipartSlice>()
    fun addFraming(block: ByteArrayOutputStream.() -> Unit) {
        val bytes = ByteArrayOutputStream().apply(block).toByteArray()
        slices += FolderMultipartSlice(bytes.size.toLong()) { ByteArrayInputStream(bytes) }
    }
    addFraming {
        writeAscii("--$boundary\r\n")
        writeAscii("Content-Disposition: form-data; name=\"envelope\"\r\n")
        writeAscii("Content-Type: application/json\r\n")
        writeAscii("\r\n")
        writeUtf8(prepared.envelopeJson)
        writeAscii("\r\n")
    }
    addFraming {
        writeFileHeader(boundary, prepared.activityFilename, "application/x-ndjson")
    }
    slices += FolderMultipartSlice(prepared.activityBytes.size.toLong()) {
        ByteArrayInputStream(prepared.activityBytes)
    }
    addFraming { writeAscii("\r\n") }

    for (part in prepared.companionParts) {
        require(part.byteLength >= 0L) { "negative companion part length" }
        addFraming { writeFileHeader(boundary, part.partName, part.mediaType) }
        slices += FolderMultipartSlice(part.byteLength, part.openStream)
        addFraming { writeAscii("\r\n") }
    }
    addFraming { writeAscii("--$boundary--\r\n") }
    return FolderMultipartStreamingBody(slices)
}

private fun ByteArrayOutputStream.writeFileHeader(
    boundary: String,
    filename: String,
    mediaType: String,
) {
    writeAscii("--$boundary\r\n")
    writeAscii("Content-Disposition: form-data; name=\"files\"; filename=\"")
    writeUtf8(quoteFilename(filename))
    writeAscii("\"\r\n")
    writeAscii("Content-Type: $mediaType\r\n")
    writeAscii("\r\n")
}

private fun ByteArrayOutputStream.writeAscii(text: String) {
    write(text.toByteArray(Charsets.US_ASCII))
}

private fun ByteArrayOutputStream.writeUtf8(text: String) {
    write(text.toByteArray(Charsets.UTF_8))
}

private fun quoteFilename(filename: String): String = buildString(filename.length) {
    for (ch in filename) {
        require(!Character.isISOControl(ch)) { "multipart filename contains a control character" }
        if (ch == '\\' || ch == '"') append('\\')
        append(ch)
    }
}

private class FolderMultipartStreamingBody(
    private val slices: List<FolderMultipartSlice>,
) : StreamingBody {
    override val contentLength: Long = slices.sumOf { it.length }
    private var index = 0
    private var current: InputStream? = null
    private var remaining = 0L
    private var closed = false

    override fun readChunk(target: ByteArray, offset: Int, length: Int): Int {
        if (closed) throw IOException("multipart body already closed")
        if (length == 0) return 0
        while (true) {
            if (current == null) {
                if (index >= slices.size) return -1
                val slice = slices[index]
                remaining = slice.length
                current = slice.open()
            }
            if (remaining == 0L) {
                val extra = current!!.read()
                if (extra != -1) {
                    closeCurrent()
                    throw IOException("part exceeded declared length")
                }
                closeCurrent()
                index++
                continue
            }
            val toRead = minOf(length.toLong(), remaining).toInt()
            val n = current!!.read(target, offset, toRead)
            if (n < 0) {
                closeCurrent()
                throw IOException("part shorter than declared length ($remaining bytes left)")
            }
            if (n > 0) {
                remaining -= n.toLong()
                if (remaining == 0L) {
                    val extra = current!!.read()
                    if (extra != -1) {
                        closeCurrent()
                        throw IOException("part exceeded declared length")
                    }
                    closeCurrent()
                    index++
                }
                return n
            }
        }
    }

    private fun closeCurrent() {
        val stream = current
        current = null
        if (stream != null) {
            runCatching { stream.close() }
        }
    }

    override fun close() {
        closed = true
        closeCurrent()
    }
}
