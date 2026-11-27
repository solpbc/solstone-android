// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone

import app.solstone.core.pl.parseJson
import app.solstone.core.pl.toJson
import java.math.BigDecimal

/** The journal's native export host contract (v2), as the app vendors it. */
internal data class ExportHostContract(
    val json: String,
    val capability: String,
    val start: String,
    val status: String,
    val pause: String,
    val resume: String,
    val cancel: String,
    val reveal: String,
    val deviceSpace: String,
    val event: String,
    val unsupported: String,
)

/** Parses the vendored export-host-contract.json, or null when unsupported. */
internal fun parseExportHostContract(bytes: ByteArray): ExportHostContract? {
    val text = bytes.toString(Charsets.UTF_8)
    val root = runCatching { parseJson(text) }.getOrNull() as? Map<*, *> ?: return null
    val version = root["version"] as? BigDecimal ?: return null
    if (version.compareTo(BigDecimal(2)) != 0) return null
    fun name(key: String) = (root[key] as? String)?.takeIf { it.isNotEmpty() }
    return ExportHostContract(
        json = text.trim(),
        capability = name("capability") ?: return null,
        start = name("start") ?: return null,
        status = name("status") ?: return null,
        pause = name("pause") ?: return null,
        resume = name("resume") ?: return null,
        cancel = name("cancel") ?: return null,
        reveal = name("reveal") ?: return null,
        deviceSpace = name("deviceSpace") ?: return null,
        event = name("event") ?: return null,
        unsupported = name("unsupported") ?: return null,
    )
}

/** Validated `export.start` request from `/app/export/`. */
internal data class JournalExportStartRequest(
    val exportId: String,
    val downloadPath: String,
    val filename: String,
    val sizeBytes: Long,
    val sha256: String,
    val etag: String,
    val mode: String?,
) {
    companion object {
        fun parse(message: Map<*, *>): JournalExportStartRequest? {
            val exportId = (message["export_id"] as? String)?.takeIf(::isSafeExportId) ?: return null
            val downloadPath = (message["download_path"] as? String)
                ?.takeIf { it == "/app/export/api/ready/$exportId/download" }
                ?: return null
            val rawFilename = message["filename"] as? String ?: return null
            val sizeNum = message["size_bytes"] as? BigDecimal ?: return null
            val sizeBytes = runCatching { sizeNum.longValueExact() }.getOrNull() ?: return null
            if (sizeBytes <= 0L) return null
            val sha256 = (message["sha256"] as? String)?.takeIf(::isValidSha256)?.lowercase() ?: return null
            val etag = (message["etag"] as? String)?.takeIf { it.isNotEmpty() && it.length <= 256 } ?: return null
            val mode = message["mode"] as? String
            return JournalExportStartRequest(
                exportId = exportId,
                downloadPath = downloadPath,
                filename = sanitizeFilename(rawFilename),
                sizeBytes = sizeBytes,
                sha256 = sha256,
                etag = etag,
                mode = mode,
            )
        }

        fun isSafeExportId(value: String): Boolean =
            value.length in 1..64 && value.all { ch ->
                ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9' || ch == '_' || ch == '-'
            }

        fun isValidSha256(value: String): Boolean =
            value.length == 64 && value.all { ch ->
                ch in '0'..'9' || ch in 'a'..'f' || ch in 'A'..'F'
            }

        fun sanitizeFilename(raw: String): String {
            val leaf = raw.substringAfterLast('/').substringAfterLast('\\').trim()
            val ok = leaf.isNotEmpty() &&
                !leaf.startsWith('.') &&
                leaf.length <= 200 &&
                !leaf.contains(':') &&
                leaf.none { it.code < 0x20 || it.code == 0x7f }
            return if (ok) leaf else "solstone-journal.zip"
        }
    }
}

/** Snapshot exposed to `/app/export/` via reply and `solstone-journal-export-status`. */
internal data class JournalExportStatusSnapshot(
    val exportId: String? = null,
    val phase: String = "idle",
    val platform: String = "android",
    val bytesWritten: Long = 0L,
    val totalBytes: Long = 0L,
    val rateBytesPerSec: Double? = null,
    val transport: String? = null,
    val destinationDisplay: String? = null,
    val canReveal: Boolean = false,
    val verified: Boolean = false,
    val pauseReason: String? = null,
    val errorCode: String? = null,
    val requiredBytes: Long? = null,
    val availableBytes: Long? = null,
    val mode: String? = null,
) {
    fun toMap(replyId: String? = null): Map<String, Any?> = buildMap {
        if (replyId != null) put("id", replyId)
        put("phase", phase)
        put("platform", platform)
        put("bytes_written", bytesWritten)
        put("total_bytes", totalBytes)
        put("can_reveal", canReveal)
        put("verified", verified)
        exportId?.let { put("export_id", it) }
        rateBytesPerSec?.takeIf { it.isFinite() && it >= 0.0 }?.let { put("rate_bytes_per_sec", it) }
        transport?.let { put("transport", it) }
        destinationDisplay?.let { put("destination_display", it) }
        pauseReason?.let { put("pause_reason", it) }
        errorCode?.let { put("error_code", it) }
        requiredBytes?.let { put("required_bytes", it) }
        availableBytes?.let { put("available_bytes", it) }
        mode?.let { put("mode", it) }
    }

    companion object {
        val IDLE = JournalExportStatusSnapshot(phase = "idle")
    }
}

/**
 * Pure-Kotlin resumable SHA-256 (FIPS 180-4) with serializable midstate, used as a fallback
 * verifier for write-only SAF providers that refuse read-back (`openInputStream`).
 */
internal class ResumableSha256(
    state: IntArray = INITIAL_H.copyOf(),
    totalBytes: Long = 0L,
    pending: ByteArray = ByteArray(0),
) {
    private val h = state.copyOf(8)
    private var totalLen: Long = totalBytes
    private val block = ByteArray(64)
    private var blockLen: Int = pending.size.coerceAtMost(64)

    init {
        if (blockLen > 0) {
            System.arraycopy(pending, 0, block, 0, blockLen)
        }
    }

    fun update(input: ByteArray, offset: Int, length: Int) {
        var pos = offset
        var remaining = length
        totalLen += remaining.toLong()
        while (remaining > 0) {
            val take = minOf(remaining, 64 - blockLen)
            System.arraycopy(input, pos, block, blockLen, take)
            blockLen += take
            pos += take
            remaining -= take
            if (blockLen == 64) {
                compress(block)
                blockLen = 0
            }
        }
    }

    fun snapshotMidstate(): Midstate = Midstate(
        h = h.copyOf(),
        totalBytes = totalLen,
        pending = block.copyOf(blockLen),
    )

    fun digestHex(): String {
        val copy = ResumableSha256(h, totalLen, block.copyOf(blockLen))
        val bitLen = copy.totalLen * 8L
        val pad = ByteArray(1) { 0x80.toByte() }
        copy.updateRawNoCount(pad, 0, 1)
        val zero = ByteArray(64)
        val padZeros = if (copy.blockLen <= 56) 56 - copy.blockLen else 120 - copy.blockLen
        if (padZeros > 0) {
            copy.updateRawNoCount(zero, 0, padZeros)
        }
        val lenBytes = ByteArray(8)
        for (i in 0 until 8) {
            lenBytes[i] = ((bitLen ushr (56 - i * 8)) and 0xffL).toByte()
        }
        copy.updateRawNoCount(lenBytes, 0, 8)
        val sb = StringBuilder(64)
        for (word in copy.h) {
            for (shift in 28 downTo 0 step 4) {
                sb.append(HEX[(word ushr shift) and 0xf])
            }
        }
        return sb.toString()
    }

    private fun updateRawNoCount(input: ByteArray, offset: Int, length: Int) {
        var pos = offset
        var remaining = length
        while (remaining > 0) {
            val take = minOf(remaining, 64 - blockLen)
            System.arraycopy(input, pos, block, blockLen, take)
            blockLen += take
            pos += take
            remaining -= take
            if (blockLen == 64) {
                compress(block)
                blockLen = 0
            }
        }
    }

    private fun compress(chunk: ByteArray) {
        val w = IntArray(64)
        for (i in 0 until 16) {
            val b = i * 4
            w[i] = ((chunk[b].toInt() and 0xff) shl 24) or
                ((chunk[b + 1].toInt() and 0xff) shl 16) or
                ((chunk[b + 2].toInt() and 0xff) shl 8) or
                (chunk[b + 3].toInt() and 0xff)
        }
        for (i in 16 until 64) {
            val s0 = w[i - 15].rotateRight(7) xor w[i - 15].rotateRight(18) xor (w[i - 15] ushr 3)
            val s1 = w[i - 2].rotateRight(17) xor w[i - 2].rotateRight(19) xor (w[i - 2] ushr 10)
            w[i] = w[i - 16] + s0 + w[i - 7] + s1
        }
        var a = h[0]
        var b = h[1]
        var c = h[2]
        var d = h[3]
        var e = h[4]
        var f = h[5]
        var g = h[6]
        var hh = h[7]
        for (i in 0 until 64) {
            val s1 = e.rotateRight(6) xor e.rotateRight(11) xor e.rotateRight(25)
            val ch = (e and f) xor (e.inv() and g)
            val t1 = hh + s1 + ch + K[i] + w[i]
            val s0 = a.rotateRight(2) xor a.rotateRight(13) xor a.rotateRight(22)
            val maj = (a and b) xor (a and c) xor (b and c)
            val t2 = s0 + maj
            hh = g
            g = f
            f = e
            e = d + t1
            d = c
            c = b
            b = a
            a = t1 + t2
        }
        h[0] += a
        h[1] += b
        h[2] += c
        h[3] += d
        h[4] += e
        h[5] += f
        h[6] += g
        h[7] += hh
    }

    data class Midstate(
        val h: IntArray,
        val totalBytes: Long,
        val pending: ByteArray,
    )

    companion object {
        private val HEX = "0123456789abcdef".toCharArray()
        private val INITIAL_H = intArrayOf(
            0x6a09e667, -0x4498517b, 0x3c6ef372, -0x5ab00ac6,
            0x510e527f, -0x64fa9774, 0x1f83d9ab, 0x5be0cd19,
        )
        private val K = intArrayOf(
            0x428a2f98, 0x71374491, -0x4a3f0431, -0x164a245b, 0x3956c25b, 0x59f111f1, -0x6dc07d5c, -0x54e3a12b,
            -0x27f85568, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, -0x7f214e02, -0x6423f959, -0x3e640e8c,
            -0x1b64963f, -0x1041b87a, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
            -0x67c1aeae, -0x57ce3993, -0x4ffcd838, -0x40a68039, -0x391ff40d, -0x2a586eb9, 0x06ca6351, 0x14292967,
            0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13, 0x650a7354, 0x766a0abb, -0x7e3d36d2, -0x6d8dd37b,
            -0x5d40175f, -0x57e599b5, -0x3db47490, -0x3893ae5d, -0x2e6d17e7, -0x2966f9dc, -0xbf1ca7b, 0x106aa070,
            0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
            0x748f82ee, 0x78a5636f, -0x7b3787ec, -0x7338fdf8, -0x6f410006, -0x5baf9315, -0x41065c09, -0x398e870e,
        )
    }
}

/**
 * Per-WebView adapter bridging `window.solstoneJournalHost` v2 export messages to the
 * app-scoped [JournalExportTransferManager]. Detaching the WebView leaves any active
 * export transfer running.
 */
internal class JournalExportHost(
    internal val contract: ExportHostContract,
    private val manager: JournalExportTransferManager,
    /** Opens Android SAF `ACTION_CREATE_DOCUMENT` with the suggested filename. */
    private val pickDestination: (suggestedFilename: String, onPicked: (ExportDestination?) -> Unit) -> Unit,
) {
    private var listenerHandle: (() -> Unit)? = null
    @Volatile private var pushEvent: ((String) -> Unit)? = null

    fun attachEventSink(sink: (String) -> Unit) {
        detachEventSink()
        pushEvent = sink
        listenerHandle = manager.addStatusListener { snapshot ->
            val payload = toJson(linkedMapOf("event" to contract.event, "detail" to snapshot.toMap()))
            pushEvent?.invoke(payload)
        }
    }

    fun detachEventSink() {
        listenerHandle?.invoke()
        listenerHandle = null
        pushEvent = null
    }

    fun handles(type: Any?): Boolean = type is String && (
        type == contract.start ||
            type == contract.status ||
            type == contract.pause ||
            type == contract.resume ||
            type == contract.cancel ||
            type == contract.reveal ||
            type == contract.deviceSpace
        )

    fun handle(message: Map<*, *>, answer: (Map<String, Any?>) -> Unit) {
        val type = message["type"] as? String ?: return
        val id = message["id"] as? String
        if (id.isNullOrEmpty() || id.length > 128) {
            answer(mapOf("type" to contract.unsupported, "id" to id))
            return
        }
        when (type) {
            contract.deviceSpace -> {
                val space = manager.deviceSpaceSnapshot().toMutableMap()
                space["id"] = id
                answer(space)
            }
            contract.status -> {
                answer(manager.currentSnapshot().toMap(replyId = id))
            }
            contract.start -> {
                val request = JournalExportStartRequest.parse(message)
                if (request == null) {
                    answer(mapOf("type" to contract.unsupported, "id" to id))
                    return
                }
                manager.start(request, pickDestination) { snapshot ->
                    answer(snapshot.toMap(replyId = id))
                }
            }
            contract.pause -> {
                val exportId = message["export_id"] as? String
                answer(manager.pause(exportId).toMap(replyId = id))
            }
            contract.resume -> {
                val exportId = message["export_id"] as? String
                answer(manager.resume(exportId).toMap(replyId = id))
            }
            contract.cancel -> {
                val exportId = message["export_id"] as? String
                answer(manager.cancel(exportId).toMap(replyId = id))
            }
            contract.reveal -> {
                answer(mapOf("id" to id, "revealed" to false))
            }
            else -> answer(mapOf("type" to contract.unsupported, "id" to id))
        }
    }
}
