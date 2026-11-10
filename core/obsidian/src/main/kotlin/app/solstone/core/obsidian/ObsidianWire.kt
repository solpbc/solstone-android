// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc
package app.solstone.core.obsidian

import app.solstone.core.model.WireKeys
import app.solstone.core.pl.parseJson
import app.solstone.core.pl.toJson
import app.solstone.core.segment.wireKeys
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

const val OBSIDIAN_WINDOW_MS = 300_000L
const val OBSIDIAN_CLOSE_GRACE_MS = 90_000L
const val OBSIDIAN_MAX_TEXT_BYTES = 4 * 1024 * 1024
const val OBSIDIAN_MAX_FILE_BYTES = 8 * 1024 * 1024
const val OBSIDIAN_MAX_LINES = 5_000
const val OBSIDIAN_MEDIA_TYPE = "application/x-ndjson"

fun obsidianHash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes).joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }

fun reportableObsidianPath(path: String): Boolean = path.isNotEmpty() &&
    !path.startsWith('/') && '\\' !in path && path.none { Character.isISOControl(it) } &&
    path.split('/').all { it.isNotEmpty() && it != "." && it != ".." && !it.startsWith('.') &&
        it.lowercase(Locale.ROOT) !in setOf("templates", "_templates") } && path.endsWith(".md", ignoreCase = true)

fun obsidianStamp(ms: Long, zone: ZoneId): String {
    val t = Instant.ofEpochMilli(ms).truncatedTo(ChronoUnit.SECONDS)
    val offset = t.atZone(zone).offset.let { if (it.totalSeconds % 60 == 0) it else ZoneOffset.UTC }
    return DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(t.atOffset(offset))
}

fun obsidianWindow(stamp: String): WireKeys {
    val p = OffsetDateTime.parse(stamp)
    val offset = p.offset.totalSeconds * 1000L
    val start = Math.floorDiv(p.toInstant().toEpochMilli() + offset, OBSIDIAN_WINDOW_MS) * OBSIDIAN_WINDOW_MS - offset
    return wireKeys(start, start + OBSIDIAN_WINDOW_MS, p.offset)
}

fun obsidianWindowId(stamp: String): String = obsidianWindow(stamp).let { "${it.day}/${it.segment}" }

/** The complete note is on the wire; interpretation and diffs belong to the journal. */
data class ObsidianChange(
    val change: String,
    val changedAt: String,
    val contentHash: String?,
    val observedAt: String,
    val path: String,
    val previousPath: String?,
    val text: String?,
    val vault: String,
) {
    fun encode(): String {
        require(change in setOf("changed", "renamed", "removed"))
        require(vault.toByteArray(Charsets.UTF_8).size in 1..128 && vault.none { Character.isISOControl(it) })
        require(reportableObsidianPath(path))
        require((change == "renamed") == (previousPath != null))
        require(previousPath == null || reportableObsidianPath(previousPath))
        for (stamp in listOf(changedAt, observedAt)) {
            val p = OffsetDateTime.parse(stamp)
            require(p.nano == 0 && p.offset.totalSeconds % 60 == 0)
        }
        if (change == "removed") require(text == null && contentHash == null)
        else {
            require(text != null && text.toByteArray(Charsets.UTF_8).size <= OBSIDIAN_MAX_TEXT_BYTES)
            require(contentHash == obsidianHash(text.toByteArray(Charsets.UTF_8)))
        }
        return toJson(linkedMapOf(
            "change" to change, "changed_at" to changedAt, "content_hash" to contentHash,
            "observed_at" to observedAt, "path" to path, "previous_path" to previousPath,
            "text" to text, "v" to 1, "vault" to vault,
        ))
    }
}

fun decodeObsidianChange(line: String): ObsidianChange {
    val m = parseJson(line) as? Map<*, *> ?: error("invalid Obsidian note")
    val keys = setOf("change", "changed_at", "content_hash", "observed_at", "path", "previous_path", "text", "v", "vault")
    require(m.keys.containsAll(keys) && (m["v"] as? Number)?.toInt() == 1)
    return ObsidianChange(m["change"] as String, m["changed_at"] as String, m["content_hash"] as String?,
        m["observed_at"] as String, m["path"] as String, m["previous_path"] as String?, m["text"] as String?,
        m["vault"] as String).also { it.encode() }
}

data class ObsidianFile(val name: String, val bytes: ByteArray, val keys: WireKeys)

/** UTF-8 order matches the contract's Unicode ordering, including supplementary characters. */
fun compareObsidianText(a: String, b: String): Int {
    val x = a.toByteArray(Charsets.UTF_8); val y = b.toByteArray(Charsets.UTF_8)
    for (i in 0 until minOf(x.size, y.size)) {
        val c = (x[i].toInt() and 255).compareTo(y[i].toInt() and 255)
        if (c != 0) return c
    }
    return x.size.compareTo(y.size)
}

/** One bounded file at a time; callers commit each file and its pending keys together. */
class ObsidianFileBuilder(private val keys: WireKeys) {
    private var out = ByteArrayOutputStream()
    var count: Int = 0; private set
    fun fits(line: ByteArray): Boolean = count < OBSIDIAN_MAX_LINES && out.size() + line.size <= OBSIDIAN_MAX_FILE_BYTES
    fun add(line: ByteArray) { require(fits(line)); out.write(line); count++ }
    fun finish(): ObsidianFile? {
        if (count == 0) return null
        val bytes = out.toByteArray()
        return ObsidianFile("obsidian_${obsidianHash(bytes)}.jsonl", bytes, keys)
    }
}
