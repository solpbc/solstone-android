// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc
package app.solstone.core.obsidian

import app.solstone.core.model.WireKeys
import app.solstone.core.pl.parseJson
import app.solstone.core.pl.toJson
import java.time.ZoneId

/** Keys are scoped by a selected tree, never by a journal or its pairing. */
interface ObsidianLedger {
    fun <T> transaction(action: () -> T): T
    fun get(root: String, bucket: String, key: String): String?
    fun put(root: String, bucket: String, key: String, value: String)
    fun remove(root: String, bucket: String, key: String)
    fun keys(root: String, bucket: String): List<String>
    fun roots(): List<String>
    fun prepare(batch: PreparedObsidianBatch, bytes: ByteArray)
    fun batches(): List<PreparedObsidianBatch>
    fun batchBytes(root: String, name: String): ByteArray?
    fun markSpooled(root: String, name: String)
    fun retireBatch(root: String, name: String)
}

data class PreparedObsidianBatch(
    val root: String, val vault: String, val name: String, val keys: WireKeys,
    val lines: Int, val initialNotes: Int, val spooled: Boolean = false,
)

data class ObsidianEntry(val path: String, val modifiedMs: Long?, val size: Long?, val documentId: String)
sealed interface ObsidianRead {
    data class Stable(val text: String, val fileId: String? = null, val observedMs: Long? = null) : ObsidianRead
    data object Unavailable : ObsidianRead
    data object TooLarge : ObsidianRead
}

data class ObsidianScanResult(val found: Int, val changed: Int, val tooLarge: Int, val unavailable: Int)

private data class NoteRow(
    val modifiedMs: Long?, val size: Long?, val fileId: String?, val hash: String?,
    val lastReportedMs: Long?, val missingMs: Long? = null, val removed: Boolean = false,
    val priorSentWindow: String? = null,
) {
    fun encode(): String = toJson(linkedMapOf("mtime" to modifiedMs, "size" to size, "id" to fileId,
        "hash" to hash, "reported" to lastReportedMs, "missing" to missingMs, "removed" to removed,
        "sent" to priorSentWindow))
}

private fun rowFrom(text: String): NoteRow {
    val m = parseJson(text) as Map<*, *>
    fun number(k: String) = (m[k] as? Number)?.toLong()
    return NoteRow(number("mtime"), number("size"), m["id"] as String?, m["hash"] as String?,
        number("reported"), number("missing"), m["removed"] as Boolean, m["sent"] as String?)
}

private data class Pending(val line: ObsidianChange, val first: Boolean, val initial: Boolean, val zoneId: String? = null) {
    fun encode(): String = toJson(linkedMapOf("line" to line.encode(), "first" to first, "initial" to initial, "zone" to zoneId))
}
private fun pendingFrom(text: String): Pending {
    val m = parseJson(text) as Map<*, *>
    return Pending(decodeObsidianChange(m["line"] as String), m["first"] as Boolean, m["initial"] as Boolean, m["zone"] as? String)
}
private fun pendingKey(window: String, path: String) = "$window/$path"

/** All mutation is serialized by the platform session and each scan is a single ledger transaction. */
class ObsidianEngine(private val ledger: ObsidianLedger, private val zone: ZoneId) {
    /** Only content work is resumable. Presence and reported state remain a complete-scan transaction. */
    fun entriesNeedingRead(root: String, entries: List<ObsidianEntry>): List<ObsidianEntry> = entries.filter { entry ->
        reportableObsidianPath(entry.path) && (entry.size == null || entry.size <= OBSIDIAN_MAX_TEXT_BYTES) &&
            !unchanged(ledger.get(root, "rows", entry.path)?.let(::rowFrom), entry)
    }
    private fun unchanged(old: NoteRow?, entry: ObsidianEntry): Boolean =
        old != null && !old.removed && old.hash != null && entry.modifiedMs != null && entry.modifiedMs > 0 &&
            entry.size != null && old.modifiedMs == entry.modifiedMs && old.size == entry.size

    fun scan(root: String, vault: String, entries: List<ObsidianEntry>, now: Long,
        read: (ObsidianEntry) -> ObsidianRead, cancelled: () -> Boolean = { false },
        progress: (Int, Int) -> Unit = { _, _ -> }, readStageToken: String? = null,
    ): ObsidianScanResult = ledger.transaction {
        if (readStageToken != null && ledger.get(root, "meta", "read_stage_applied") == readStageToken) {
            return@transaction ObsidianScanResult(ledger.get(root, "meta", "found")?.toInt() ?: 0, 0,
                ledger.get(root, "meta", "too_large")?.toInt() ?: 0, ledger.get(root, "meta", "unavailable")?.toInt() ?: 0)
        }
        require(vault.toByteArray(Charsets.UTF_8).size in 1..128 && vault.none { Character.isISOControl(it) })
        val notes = entries.filter { reportableObsidianPath(it.path) }.sortedWith { a, b -> compareObsidianText(a.path, b.path) }
        require(notes.map { it.path }.toSet().size == notes.size) { "duplicate vault path" }
        val rows = ledger.keys(root, "rows").associateWith { rowFrom(requireNotNull(ledger.get(root, "rows", it))) }.toMutableMap()
        val present = notes.map { it.path }.toSet()
        val missing = rows.filter { (path, row) -> path !in present && !row.removed }.toMutableMap()
        val baseline = ledger.get(root, "meta", "baseline") == null
        val lastScan = ledger.get(root, "meta", "last_scan")?.toLong() ?: Long.MIN_VALUE
        val earliest = notes.mapNotNull { it.modifiedMs }.filter { it > lastScan && it <= now }.minOrNull() ?: now
        var changes = 0; var tooLarge = 0; var unavailable = 0; var uncertainNewcomer = false
        ledger.put(root, "meta", "vault", vault)
        for ((index, entry) in notes.withIndex()) {
            if (cancelled()) throw java.util.concurrent.CancellationException("vault scan cancelled")
            var old = rows[entry.path]
            // Presence alone resets the missing hold, even when its contents cannot be read.
            if (old?.missingMs != null) {
                old = old.copy(missingMs = null)
                rows[entry.path] = old; save(root, entry.path, old)
            }
            if (unchanged(old, entry)) { progress(index + 1, notes.size); continue }
            if (baseline) known(root, entry.path, "baseline", 0)
            val content = if (entry.size != null && entry.size > OBSIDIAN_MAX_TEXT_BYTES) ObsidianRead.TooLarge else read(entry)
            when (content) {
                ObsidianRead.Unavailable -> { unavailable++; if (old == null || old.removed) uncertainNewcomer = true }
                ObsidianRead.TooLarge -> { tooLarge++; if (old == null || old.removed) uncertainNewcomer = true }
                is ObsidianRead.Stable -> {
                    val bytes = content.text.toByteArray(Charsets.UTF_8)
                    if (bytes.size > OBSIDIAN_MAX_TEXT_BYTES) {
                        tooLarge++
                    } else {
                        val observed = content.observedMs ?: now
                        val hash = obsidianHash(bytes)
                        if (old != null && !old.removed && old.hash == hash) {
                            val unchanged = old.copy(modifiedMs = entry.modifiedMs, size = entry.size,
                                fileId = content.fileId, missingMs = null)
                            rows[entry.path] = unchanged; save(root, entry.path, unchanged)
                        } else {
                            // Provider document IDs are opaque and may change on rename. Only a platform
                            // adapter's measured filesystem identity, or matching text, is a rename witness.
                            val sameId = content.fileId?.let { id -> missing.entries.firstOrNull { it.value.fileId == id } }
                            val partner = if (old == null || old.removed) sameId ?: missing.entries.firstOrNull { it.value.hash == hash } else null
                            val prior = partner?.value ?: old
                            val previous = partner?.key
                            if (previous != null) missing.remove(previous)
                            val pureRename = previous != null && prior?.hash == hash
                            val modified = if (pureRename) minOf(earliest, observed) else entry.modifiedMs?.takeIf {
                                it > 0 && it <= observed && (prior?.lastReportedMs == null || it > prior.lastReportedMs)
                            } ?: observed
                            val sent = listOfNotNull(prior?.priorSentWindow,
                                ledger.get(root, "sent", entry.path), previous?.let { ledger.get(root, "sent", it) }).maxOrNull()
                            var stamp = obsidianStamp(modified, zone)
                            if (sent != null && obsidianWindowId(stamp) <= sent) stamp = obsidianStamp(observed, zone)
                            if (sent != null && obsidianWindowId(stamp) <= sent) {
                                // Do not acknowledge content that cannot yet occupy a fresh window.
                                progress(index + 1, notes.size); continue
                            }
                            val line = ObsidianChange(if (previous == null) "changed" else "renamed", stamp, hash,
                                obsidianStamp(observed, zone), entry.path, previous, content.text, vault)
                            if (line.encode().toByteArray(Charsets.UTF_8).size + 1 > OBSIDIAN_MAX_FILE_BYTES) {
                                tooLarge++; progress(index + 1, notes.size); continue
                            }
                            val first = prior?.lastReportedMs == null
                            emit(root, Pending(line, first, baseline && first, zone.id), rows)
                            val updated = NoteRow(entry.modifiedMs, entry.size, content.fileId, hash,
                                java.time.OffsetDateTime.parse(stamp).toInstant().toEpochMilli(), priorSentWindow = sent)
                            if (previous != null) {
                                ledger.remove(root, "rows", previous); rows.remove(previous); missing.remove(previous)
                                moveKnown(root, previous, entry.path, observed)
                            }
                            rows[entry.path] = updated; save(root, entry.path, updated)
                            if (!baseline && !isKnown(root, entry.path)) {
                                known(root, entry.path, "new", observed)
                                increment(root, "notes_total", 1)
                            }
                            ledger.put(root, "meta", "last_taken", now.toString())
                            if (baseline && first) increment(root, "initial_total", 1)
                            changes++
                        }
                    }
                }
            }
            progress(index + 1, notes.size)
        }
        // An unreadable or oversized newcomer might be a renamed missing note. Hold uncertain deletes.
        if (!uncertainNewcomer) for ((path, row) in missing) {
            if (cancelled()) throw java.util.concurrent.CancellationException("vault scan cancelled")
            if (row.missingMs == null) {
                save(root, path, row.copy(missingMs = now))
            } else if (row.lastReportedMs != null) {
                val sent = listOfNotNull(row.priorSentWindow, ledger.get(root, "sent", path)).maxOrNull()
                var stamp = obsidianStamp(row.missingMs, zone)
                if (sent != null && obsidianWindowId(stamp) <= sent) stamp = obsidianStamp(now, zone)
                if (sent != null && obsidianWindowId(stamp) <= sent) continue
                emit(root, Pending(ObsidianChange("removed", stamp, null, obsidianStamp(row.missingMs, zone),
                    path, null, null, vault), first = false, initial = false, zoneId = zone.id), rows)
                save(root, path, row.copy(removed = true, lastReportedMs = java.time.OffsetDateTime.parse(stamp).toInstant().toEpochMilli()))
                ledger.put(root, "meta", "last_taken", now.toString()); changes++
            }
        }
        ledger.put(root, "meta", "baseline", "1")
        ledger.put(root, "meta", "last_scan", now.toString())
        ledger.put(root, "meta", "found", notes.size.toString())
        ledger.put(root, "meta", "too_large", tooLarge.toString())
        ledger.put(root, "meta", "unavailable", unavailable.toString())
        if (readStageToken != null) ledger.put(root, "meta", "read_stage_applied", readStageToken)
        ObsidianScanResult(notes.size, changes, tooLarge, unavailable)
    }

    /**
     * A new pairing: the next scan of every tree is a first send again, each note stamped at its own
     * change time as a first turn-on stamps it, and the same-name approvals kept under [settingsRoot]
     * are dropped so the check runs before that send. The tree, its grant, pending lines, frozen
     * batches and the lifetime counters stay: what is held goes to the journal paired now.
     */
    fun firstSetupAgain(settingsRoot: String) = ledger.transaction {
        for (root in ledger.roots().filter { it != settingsRoot }) {
            for (bucket in listOf("rows", "sent", "read_stage")) {
                for (key in ledger.keys(root, bucket)) ledger.remove(root, bucket, key)
            }
            for (key in listOf("baseline", "last_scan", "initial_total", "initial_sent", "read_stage_active", "read_stage_applied")) {
                ledger.remove(root, "meta", key)
            }
        }
        for (key in ledger.keys(settingsRoot, OBSIDIAN_APPROVALS)) ledger.remove(settingsRoot, OBSIDIAN_APPROVALS, key)
    }

    private fun save(root: String, path: String, row: NoteRow) = ledger.put(root, "rows", path, row.encode())
    private fun known(root: String, path: String, kind: String, observed: Long) {
        val hash = obsidianHash(path.toByteArray(Charsets.UTF_8))
        if (ledger.get(root, "known", hash) != null) return
        ledger.put(root, "known", hash, "1")
        enqueueObsidianCounter(ledger, root, kind, hash, observedMs = observed)
    }
    private fun isKnown(root: String, path: String) = ledger.get(root, "known", obsidianHash(path.toByteArray(Charsets.UTF_8))) != null
    private fun moveKnown(root: String, from: String, to: String, observed: Long) {
        val oldHash = obsidianHash(from.toByteArray(Charsets.UTF_8))
        if (ledger.get(root, "known", oldHash) != null) {
            val newHash = obsidianHash(to.toByteArray(Charsets.UTF_8))
            ledger.remove(root, "known", oldHash); ledger.put(root, "known", newHash, "1")
            enqueueObsidianCounter(ledger, root, "rename", newHash, oldHash, observed)
        }
    }
    private fun increment(root: String, key: String, n: Int) {
        ledger.put(root, "meta", key, ((ledger.get(root, "meta", key)?.toLong() ?: 0) + n).toString())
    }

    private fun emit(root: String, incoming: Pending, rows: Map<String, NoteRow>) {
        var next = incoming
        val window = obsidianWindowId(next.line.changedAt)
        val here = pendingKey(window, next.line.path)
        val existing = ledger.get(root, "pending", here)?.let(::pendingFrom)
        val from = next.line.previousPath?.let { pendingKey(window, it) }
        val previous = from?.let { ledger.get(root, "pending", it)?.let(::pendingFrom) }
        when (next.line.change) {
            // After a new pairing a waiting line can meet that pairing's first send of the same note:
            // it is then part of the first send, so the first-send count still closes.
            "changed" -> if (existing != null) {
                next = next.copy(first = existing.first || next.first, initial = existing.initial || next.initial,
                    line = next.line.copy(change = existing.line.change.takeIf { it == "renamed" } ?: "changed",
                        previousPath = existing.line.previousPath))
            }
            "renamed" -> if (previous != null) {
                next = when {
                    previous.first -> next.copy(line = next.line.copy(change = "changed", previousPath = null), first = true, initial = previous.initial)
                    previous.line.change == "renamed" && previous.line.previousPath == next.line.path ->
                        next.copy(line = next.line.copy(change = "changed", previousPath = null), initial = previous.initial)
                    previous.line.change == "renamed" -> next.copy(line = next.line.copy(previousPath = previous.line.previousPath), initial = previous.initial)
                    else -> next
                }
            }
            "removed" -> if (existing != null) {
                ledger.remove(root, "pending", here)
                if (existing.first) {
                    if (existing.initial) increment(root, "initial_total", -1)
                    return
                }
                if (existing.line.change == "renamed") {
                    val origin = requireNotNull(existing.line.previousPath)
                    if (rows[origin]?.removed == false) return
                    next = next.copy(line = next.line.copy(path = origin))
                }
            }
        }
        if (from != null) ledger.remove(root, "pending", from)
        ledger.remove(root, "pending", here)
        ledger.put(root, "pending", pendingKey(window, next.line.path), next.encode())
    }

    /** Freeze immutable bytes before staging into the shared spool. A restart resumes these exact bytes. */
    fun closeDueWindows(now: Long, cancelled: () -> Boolean = { false }) {
        for (root in ledger.roots()) {
            val keys = ledger.keys(root, "pending").sortedWith(::compareObsidianText)
            val groups = keys.groupBy { it.substring(0, 19) }
            for ((_, windowKeys) in groups) {
                val first = pendingFrom(requireNotNull(ledger.get(root, "pending", windowKeys.first())))
                val window = obsidianWindow(first.line.changedAt).copy(zoneId = first.zoneId ?: zone.id)
                if (window.endEpochMs + OBSIDIAN_CLOSE_GRACE_MS > now) continue
                var builder = ObsidianFileBuilder(window)
                var taken = mutableListOf<Pair<String, Pending>>()
                fun flush() {
                    val file = builder.finish() ?: return
                    ledger.transaction {
                        ledger.prepare(PreparedObsidianBatch(root, first.line.vault, file.name, file.keys,
                            taken.size, taken.count { it.second.initial }), file.bytes)
                        for ((key, pending) in taken) {
                            ledger.remove(root, "pending", key)
                            ledger.put(root, "sent", pending.line.path, obsidianWindowId(pending.line.changedAt))
                        }
                    }
                    builder = ObsidianFileBuilder(window); taken = mutableListOf()
                }
                for (key in windowKeys) {
                    if (cancelled()) throw java.util.concurrent.CancellationException("vault flush cancelled")
                    val pending = pendingFrom(requireNotNull(ledger.get(root, "pending", key)))
                    val line = (pending.line.encode() + "\n").toByteArray(Charsets.UTF_8)
                    if (!builder.fits(line)) flush()
                    builder.add(line); taken += key to pending
                }
                flush()
            }
        }
    }
}
