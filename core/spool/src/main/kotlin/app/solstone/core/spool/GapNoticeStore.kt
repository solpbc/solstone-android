// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.spool

import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.SecureRandom
import java.time.ZoneId

sealed interface GapRead {
    data class Ready(val rows: List<GapNoticeRow>) : GapRead
    data object Unavailable : GapRead
}

data class GapSnapshot(
    val rows: List<GapNoticeRow>,
    val unknownItems: List<String>,
    val unknownSeen: Set<String>,
    val conversion: GapConversionState,
    val opened: Boolean,
)

/**
 * One process-wide record of gaps under `filesDir/gap/`.
 * The JVM monitor and the `writer.lock` file lock are held together across every rewrite.
 */
class GapNoticeStore(
    private val directory: Path,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ids: () -> String = ::randomGapId,
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    private val syncDirectory: (Path) -> Unit = ::forceGapDirectory,
    private val beforeWrite: (Path) -> Unit = {},
    private val listener: (kind: String, source: String, reason: String, material: String) -> Unit = { _, _, _, _ -> },
) {
    /** Test seam. Runs while this store holds its lock, before [markSeen] changes rows. */
    @Volatile
    var duringLock: (() -> Unit)? = null

    private val eventsPath get() = directory.resolve(EVENTS)
    private val unknownItemsPath get() = directory.resolve(UNKNOWN_ITEMS)
    private val unknownSeenPath get() = directory.resolve(UNKNOWN_SEEN)
    private val journalPath get() = directory.resolve(JOURNAL_ID)
    private val openedPath get() = directory.resolve(OPENED)
    private val conversionPath get() = directory.resolve(CONVERSION)
    private val lockPath get() = directory.resolve(LOCK)

    fun recordLoss(source: String, reason: String, material: String, capturedEpochMs: Long?): Boolean {
        if (!inputsOk(source, reason, material)) return false
        val pending = mutableListOf<GapNoticeRow>()
        val ok = try {
            exclusive {
                val loaded = loadForWrite(pending)
                if (!loaded.replaced && loaded.rows.any { it.kind == "loss" && it.source == source && it.material == material }) {
                    resync(eventsPath)
                    return@exclusive true
                }
                val now = clock()
                val row = newRow("loss", source, reason, material, capturedEpochMs, now, seen = false)
                pending += row
                writeEvents(prune(loaded.rows + row, now, zone()))
                true
            }
        } catch (_: Exception) {
            false
        }
        if (ok) emit(pending)
        return ok
    }

    fun recordDiagnostic(source: String, reason: String, material: String, capturedEpochMs: Long?): Boolean {
        if (!inputsOk(source, reason, material)) return false
        val pending = mutableListOf<GapNoticeRow>()
        val ok = try {
            exclusive {
                val rows = loadForWrite(pending).rows.toMutableList()
                val now = clock()
                val index = rows.indexOfFirst { it.kind == "diagnostic" && it.source == source && it.reason == reason }
                val row = if (index >= 0) {
                    val prior = rows[index]
                    prior.copy(material = material, count = prior.count + 1, lastEpochMs = now).also { rows[index] = it }
                } else {
                    newRow("diagnostic", source, reason, material, capturedEpochMs, now, seen = false).also { rows += it }
                }
                pending += row
                writeEvents(prune(rows, now, zone()))
                true
            }
        } catch (_: Exception) {
            false
        }
        if (ok) emit(pending)
        return ok
    }

    /**
     * Flips only [lossIds] and writes only [unknownKeys] into unknown-seen.
     * A loss inserted while this call holds the lock is not in [lossIds] and stays unseen.
     */
    fun markSeen(lossIds: Set<String>, unknownKeys: Set<String>): Boolean {
        val pending = mutableListOf<GapNoticeRow>()
        val ok = try {
            exclusive {
                duringLock?.invoke()
                val loaded = loadForWrite(pending)
                val updated = loaded.rows.map { row ->
                    if (row.kind == "loss" && row.id in lossIds) row.copy(seen = true) else row
                }
                if (loaded.replaced || updated != loaded.rows) writeEvents(prune(updated, clock(), zone()))
                val seen = readKeys(unknownSeenPath).toMutableSet()
                seen += unknownKeys
                writeKeys(unknownSeenPath, seen)
                true
            }
        } catch (_: Exception) {
            false
        }
        if (ok) emit(pending)
        return ok
    }

    fun read(zone: ZoneId): GapRead {
        val pending = mutableListOf<GapNoticeRow>()
        return try {
            val result = exclusive {
                when (val loaded = loadEvents()) {
                    is Loaded.Undecodable -> {
                        val row = unreadableRow()
                        pending += row
                        quarantine()
                        writeEvents(prune(listOf(row), clock(), zone))
                        GapRead.Ready(listOf(row))
                    }
                    is Loaded.Rows -> {
                        val pruned = prune(loaded.rows, clock(), zone)
                        if (pruned != loaded.rows) writeEvents(pruned)
                        GapRead.Ready(pruned)
                    }
                }
            }
            emit(pending)
            result
        } catch (_: Exception) {
            GapRead.Unavailable
        }
    }

    fun snapshot(readZone: ZoneId): GapSnapshot? {
        val read = read(readZone)
        if (read is GapRead.Unavailable) return null
        return try {
            exclusive {
                GapSnapshot(
                    rows = (read as GapRead.Ready).rows,
                    unknownItems = readKeys(unknownItemsPath),
                    unknownSeen = readKeys(unknownSeenPath).toSet(),
                    conversion = readConversion(),
                    opened = Files.exists(openedPath),
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    fun conversion(markers: List<Pair<String, Path>>): String {
        return try {
            val pending = mutableListOf<GapNoticeRow>()
            val line = exclusive {
                val existing = readConversionLine()
                if (existing != null && existing.startsWith("complete ")) return@exclusive existing.trim()
                val present = markers.filter { (_, path) -> Files.isRegularFile(path) }
                if (present.isEmpty()) {
                    val line = "complete 0"
                    writeText(conversionPath, line)
                    return@exclusive line
                }
                var rows = loadForWrite(pending).rows
                for ((source, path) in present) {
                    if (source !in GAP_SOURCES) continue
                    val already = rows.any {
                        it.kind == "legacy" && it.source == source && it.reason == "legacy marker" && it.material == "marker"
                    }
                    if (already) {
                        Files.deleteIfExists(path)
                        continue
                    }
                    val found = runCatching { Files.getLastModifiedTime(path).toMillis() }.getOrElse { clock() }
                    val row = newRow("legacy", source, "legacy marker", "marker", null, found, seen = false)
                    rows = rows + row
                    writeEvents(prune(rows, clock(), zone()))
                    pending += row
                    Files.deleteIfExists(path)
                }
                val line = "complete ${present.size}"
                writeText(conversionPath, line)
                line
            }
            emit(pending)
            line
        } catch (error: Exception) {
            val reason = failureReason(error)
            val line = "failed $reason"
            runCatching { exclusive { writeText(conversionPath, line) } }
            line
        }
    }

    /**
     * No current id: leave the file and the seen flags.
     * Absent file and a current id: write the id, do not mark seen.
     * Same id: leave seen flags.
     * Different id: mark every event and every current unknown item seen, then store the new id.
     * A failed rewrite leaves both the seen flags and the old id.
     */
    fun journalId(current: String?): Boolean {
        val id = current?.takeIf { it.isNotBlank() }
        return try {
            exclusive {
                val previous = readText(journalPath)?.trim()?.takeIf { it.isNotEmpty() }
                if (id == null) return@exclusive true
                if (previous == null) {
                    writeText(journalPath, id)
                    return@exclusive true
                }
                if (previous == id) return@exclusive true
                val priorEvents = eventsBytes()
                val priorSeen = runCatching { Files.readAllBytes(unknownSeenPath) }.getOrNull()
                val pending = mutableListOf<GapNoticeRow>()
                val loaded = loadForWrite(pending)
                val rows = loaded.rows.map { it.copy(seen = true) }
                writeEvents(rows)
                val items = readKeys(unknownItemsPath)
                writeKeys(unknownSeenPath, items.toSet())
                try {
                    writeText(journalPath, id)
                } catch (error: Exception) {
                    restoreBytes(eventsPath, priorEvents)
                    if (priorSeen != null) restoreBytes(unknownSeenPath, priorSeen) else Files.deleteIfExists(unknownSeenPath)
                    throw error
                }
                emitLater(pending)
                true
            }
            emitHeld()
            true
        } catch (_: Exception) {
            held.clear()
            false
        }
    }

    fun rewriteUnknown(paths: List<String>): Boolean = try {
        exclusive {
            val clean = paths.filter(::validUnknownKey).distinct()
            val priorItems = runCatching { Files.readAllBytes(unknownItemsPath) }.getOrNull()
            val priorSeen = runCatching { Files.readAllBytes(unknownSeenPath) }.getOrNull()
            try {
                writeKeys(unknownItemsPath, clean)
                val seen = readKeys(unknownSeenPath).filter { it in clean }.toSet()
                writeKeys(unknownSeenPath, seen)
            } catch (error: Exception) {
                if (priorItems != null) restoreBytes(unknownItemsPath, priorItems) else Files.deleteIfExists(unknownItemsPath)
                if (priorSeen != null) restoreBytes(unknownSeenPath, priorSeen) else Files.deleteIfExists(unknownSeenPath)
                throw error
            }
            true
        }
    } catch (_: Exception) {
        false
    }

    /**
     * Caller uses this only when the recovery pass returned normally.
     * If `opened` is absent, mark every current loss and [unknownPaths] seen, flush, then create `opened`.
     * A failed flush does not create `opened`. A later call does not mark losses once `opened` exists.
     */
    fun sweepMilestone(unknownPaths: List<String>): Boolean {
        val pending = mutableListOf<GapNoticeRow>()
        return try {
            exclusive {
                val clean = unknownPaths.filter(::validUnknownKey).distinct()
                val opened = Files.exists(openedPath)
                if (!opened) {
                    val rows = loadForWrite(pending).rows.map { row ->
                        if (row.kind == "loss") row.copy(seen = true) else row
                    }
                    writeEvents(prune(rows, clock(), zone()))
                    writeKeys(unknownItemsPath, clean)
                    writeKeys(unknownSeenPath, clean.toSet())
                    writeBytes(openedPath, ByteArray(0))
                } else {
                    writeKeys(unknownItemsPath, clean)
                    val seen = readKeys(unknownSeenPath).filter { it in clean }.toSet()
                    writeKeys(unknownSeenPath, seen)
                }
                true
            }.also { emit(pending) }
        } catch (_: Exception) {
            false
        }
    }

    fun isOpened(): Boolean = runCatching { Files.exists(openedPath) }.getOrDefault(false)

    private class Prepared(val rows: List<GapNoticeRow>, val replaced: Boolean)

    private fun loadForWrite(pending: MutableList<GapNoticeRow>): Prepared {
        return when (val loaded = loadEvents()) {
            is Loaded.Rows -> Prepared(loaded.rows, replaced = false)
            Loaded.Undecodable -> {
                val row = unreadableRow()
                pending += row
                quarantine()
                Prepared(listOf(row), replaced = true)
            }
        }
    }

    private fun unreadableRow(): GapNoticeRow =
        newRow("diagnostic", "spool", "unreadable record", "events", null, clock(), seen = false)

    private sealed interface Loaded {
        data class Rows(val rows: List<GapNoticeRow>) : Loaded
        data object Undecodable : Loaded
    }

    private fun loadEvents(): Loaded {
        if (Files.notExists(eventsPath)) return Loaded.Rows(emptyList())
        val bytes = Files.readAllBytes(eventsPath)
        val text = decodeStrict(bytes) ?: return Loaded.Undecodable
        if (text.isEmpty()) return Loaded.Rows(emptyList())
        val lines = text.split('\n')
        val rows = mutableListOf<GapNoticeRow>()
        for ((index, line) in lines.withIndex()) {
            if (line.isEmpty() && index == lines.lastIndex) continue
            rows += parseGapLine(line) ?: return Loaded.Undecodable
        }
        return Loaded.Rows(rows)
    }

    private fun newRow(
        kind: String,
        source: String,
        reason: String,
        material: String,
        capturedEpochMs: Long?,
        found: Long,
        seen: Boolean,
    ): GapNoticeRow {
        val id = ids()
        require(ID_PATTERN.matches(id)) { "gap id" }
        return GapNoticeRow(
            id = id,
            kind = kind,
            source = source,
            reason = reason,
            material = material,
            capturedEpochMs = capturedEpochMs,
            foundEpochMs = found,
            seen = seen,
            count = 1,
            firstEpochMs = found,
            lastEpochMs = found,
        )
    }

    private fun inputsOk(source: String, reason: String, material: String): Boolean =
        source in GAP_SOURCES && reason in GAP_REASONS && gapMaterialOk(material)

    private fun writeEvents(rows: List<GapNoticeRow>) {
        writeBytes(eventsPath, encode(rows))
    }

    private fun resync(target: Path) {
        if (Files.isRegularFile(target)) {
            FileOutputStream(target.toFile(), true).use { it.fd.sync() }
        }
        val parent = target.parent ?: directory
        syncDirectory(parent)
    }

    private fun writeText(target: Path, text: String) {
        writeBytes(target, (text.trimEnd() + "\n").toByteArray(StandardCharsets.UTF_8))
    }

    private fun writeBytes(target: Path, bytes: ByteArray) {
        beforeWrite(target)
        val parent = target.parent ?: throw IllegalStateException("gap file has no parent")
        Files.createDirectories(parent)
        val temp = Files.createTempFile(parent, "${target.fileName}-", ".tmp")
        try {
            FileOutputStream(temp.toFile()).use { fos ->
                fos.write(bytes)
                fos.fd.sync()
            }
            try {
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
            }
            syncDirectory(parent)
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    private fun restoreBytes(target: Path, bytes: ByteArray) {
        writeBytes(target, bytes)
    }

    private fun quarantine() {
        if (Files.notExists(eventsPath)) return
        var dest = directory.resolve("$EVENTS.bad-${clock()}")
        var n = 2
        while (Files.exists(dest)) {
            dest = directory.resolve("$EVENTS.bad-${clock()}-$n")
            n++
        }
        try {
            Files.move(eventsPath, dest, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(eventsPath, dest, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun eventsBytes(): ByteArray =
        if (Files.isRegularFile(eventsPath)) Files.readAllBytes(eventsPath) else ByteArray(0)

    private fun readText(target: Path): String? {
        if (Files.notExists(target)) return null
        val bytes = Files.readAllBytes(target)
        return decodeStrict(bytes)
    }

    private fun readConversionLine(): String? = readText(conversionPath)?.lineSequence()?.firstOrNull { it.isNotBlank() }

    private fun readConversion(): GapConversionState {
        val line = readConversionLine() ?: return GapConversionState.NotRun
        if (line.startsWith("complete ")) {
            val count = line.removePrefix("complete ").trim().toIntOrNull() ?: return GapConversionState.NotRun
            return GapConversionState.Complete(count)
        }
        if (line.startsWith("failed ")) return GapConversionState.Failed(line.removePrefix("failed ").trim())
        return GapConversionState.NotRun
    }

    private fun readKeys(target: Path): List<String> {
        val text = readText(target) ?: return emptyList()
        return text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.distinct().toList()
    }

    private fun writeKeys(target: Path, keys: Collection<String>) {
        val body = keys.distinct().joinToString("\n", postfix = if (keys.isEmpty()) "" else "\n")
        writeBytes(target, body.toByteArray(StandardCharsets.UTF_8))
    }

    private val held = mutableListOf<GapNoticeRow>()

    private fun emitLater(rows: List<GapNoticeRow>) {
        held += rows
    }

    private fun emitHeld() {
        val copy = held.toList()
        held.clear()
        emit(copy)
    }

    private fun emit(rows: List<GapNoticeRow>) {
        for (row in rows) {
            try {
                listener(row.kind, row.source, row.reason, row.material)
            } catch (_: Exception) {
                // A listener failure does not change the flushed row.
            }
        }
    }

    private fun <T> exclusive(block: () -> T): T {
        // A stopped capture worker keeps the interrupt flag set. NIO refuses the
        // lock while that flag is set, and the row would never land.
        val wasInterrupted = Thread.interrupted()
        try {
            synchronized(this) {
                Files.createDirectories(directory)
                FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
                    val lock = channel.lock()
                    try {
                        return block()
                    } finally {
                        lock.release()
                    }
                }
            }
        } finally {
            if (wasInterrupted) Thread.currentThread().interrupt()
        }
    }

    companion object {
        const val EVENTS = "events.v1"
        const val UNKNOWN_ITEMS = "unknown-items"
        const val UNKNOWN_SEEN = "unknown-seen"
        const val JOURNAL_ID = "journal-id"
        const val OPENED = "opened"
        const val CONVERSION = "conversion"
        const val LOCK = "writer.lock"

        private val ID_PATTERN = Regex("[0-9a-f]{32}")
        private val random = SecureRandom()

        fun randomGapId(): String {
            val bytes = ByteArray(16)
            random.nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}

internal fun parseGapLine(line: String): GapNoticeRow? {
    if (line.isEmpty() || line.contains('\r')) return null
    val parts = line.split('\t')
    if (parts.size != 11) return null
    val id = parts[0]
    val kind = parts[1]
    val source = parts[2]
    val reason = parts[3]
    val material = parts[4]
    if (!Regex("[0-9a-f]{32}").matches(id)) return null
    if (kind !in GAP_KINDS || source !in GAP_SOURCES || reason !in GAP_REASONS) return null
    if (!gapMaterialOk(material)) return null
    val captured = when (parts[5]) {
        "-" -> null
        else -> parts[5].toLongOrNull() ?: return null
    }
    val found = parts[6].toLongOrNull() ?: return null
    val seen = when (parts[7]) {
        "0" -> false
        "1" -> true
        else -> return null
    }
    val count = parts[8].toIntOrNull() ?: return null
    if (count < 1) return null
    val first = parts[9].toLongOrNull() ?: return null
    val last = parts[10].toLongOrNull() ?: return null
    return GapNoticeRow(id, kind, source, reason, material, captured, found, seen, count, first, last)
}

internal fun encodeGapRows(rows: List<GapNoticeRow>): ByteArray {
    if (rows.isEmpty()) return ByteArray(0)
    val text = rows.joinToString("\n", postfix = "\n") { row ->
        listOf(
            row.id,
            row.kind,
            row.source,
            row.reason,
            row.material,
            row.capturedEpochMs?.toString() ?: "-",
            row.foundEpochMs.toString(),
            if (row.seen) "1" else "0",
            row.count.toString(),
            row.firstEpochMs.toString(),
            row.lastEpochMs.toString(),
        ).joinToString("\t")
    }
    return text.toByteArray(StandardCharsets.UTF_8)
}

private fun encode(rows: List<GapNoticeRow>): ByteArray = encodeGapRows(rows)

internal fun pruneGapRows(rows: List<GapNoticeRow>, nowEpochMs: Long, zone: ZoneId): List<GapNoticeRow> {
    val today = localDate(nowEpochMs, zone)
    val ageCutoff = today.minusDays(30)
    fun protected(row: GapNoticeRow): Boolean =
        row.kind == "loss" && !row.seen && gapFoundInWindow(row.foundEpochMs, nowEpochMs, zone)
    val aged = rows.filter { row ->
        protected(row) || !localDate(row.lastEpochMs, zone).isBefore(ageCutoff)
    }
    val protectedRows = aged.filter(::protected)
    val otherLosses = aged.filter { it.kind == "loss" && !protected(it) }
    val others = aged.filter { it.kind != "loss" }
    val lossRoom = (500 - protectedRows.size).coerceAtLeast(0)
    val byAge = compareBy<GapNoticeRow> { it.firstEpochMs }.thenBy { it.id }
    val keptLosses = if (otherLosses.size > lossRoom) otherLosses.sortedWith(byAge).takeLast(lossRoom) else otherLosses
    val keptOthers = if (others.size > 1000) others.sortedWith(byAge).takeLast(1000) else others
    val keep = (protectedRows + keptLosses + keptOthers).map { it.id }.toSet()
    return aged.filter { it.id in keep }
}

private fun prune(rows: List<GapNoticeRow>, nowEpochMs: Long, zone: ZoneId): List<GapNoticeRow> =
    pruneGapRows(rows, nowEpochMs, zone)

private fun decodeStrict(bytes: ByteArray): String? {
    if (bytes.isEmpty()) return ""
    return try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: CharacterCodingException) {
        null
    }
}

private fun validUnknownKey(path: String): Boolean {
    if (path.isEmpty() || path.length > 512 || path.startsWith("/") || path.contains('\\')) return false
    val parts = path.split('/')
    if (parts.any { it.isEmpty() || it == "." || it == ".." }) return false
    return parts.all { gapMaterialOk(it) }
}

private fun failureReason(error: Exception): String {
    val raw = error.javaClass.simpleName
    val cleaned = raw.filter { it != '\t' && it != '\n' && it != '\r' }
    return cleaned.ifEmpty { "failed" }.take(80)
}
