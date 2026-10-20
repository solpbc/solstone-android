// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.persistence.room

import app.solstone.core.queue.RetryDecision
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock

data class ImportProgress(val path: String, val timestamp: Long)
data class ImportBackoff(val attemptCount: Int, val nextAttemptAt: Long)
data class ImportCandidate(val id: String, val progress: ImportProgress?)
data class ImportHeldItem(
    val id: String,
    val state: String,
    val displayName: String,
    val progress: ImportProgress?,
    val backoff: ImportBackoff?,
)
data class ImportAttentionFact(val error: String?, val kind: String?, val displayName: String)
data class ImportCustodyStatus(
    val pendingCount: Int,
    val attentionCount: Int,
    val attention: List<ImportAttentionFact>,
)
data class ImportItemDescriptor(
    val id: String,
    val payloadKind: String,
    val payloadPath: Path,
    val displayName: String,
    val mime: String,
    val size: Long,
)

// Process-wide reentrant lock keyed by canonical root path. Reentrant because recover calls finish while holding it.
internal object ImportRootLock {
    private val locks = ConcurrentHashMap<Path, ReentrantLock>()

    internal fun canonicalKey(root: Path): Path {
        val normalized = root.toAbsolutePath().normalize()
        return try {
            normalized.toRealPath()
        } catch (_: Exception) {
            normalized
        }
    }

    fun lockFor(root: Path): ReentrantLock {
        val key = canonicalKey(root)
        return locks.computeIfAbsent(key) { ReentrantLock() }
    }

    inline fun <T> withLock(root: Path, block: () -> T): T {
        val lock = lockFor(root)
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }
}

private val kindMap = ConcurrentHashMap<String, String>()

private fun kindKey(root: Path, id: String): String {
    val canonical = ImportRootLock.canonicalKey(root)
    return "$canonical\u0000$id"
}

private fun forceImportPath(path: Path) {
    FileChannel.open(path, if (Files.isDirectory(path)) StandardOpenOption.READ else StandardOpenOption.WRITE).use { channel ->
        channel.force(true)
    }
}

class ImportCustodyStore(
    private val root: Path,
    private val dao: ImportDao,
    private val clock: () -> Long = System::currentTimeMillis,
    private val fsync: (Path) -> Unit = ::forceImportPath,
    private val move: (Path, Path) -> Unit = { from, to ->
        Files.move(from, to, StandardCopyOption.ATOMIC_MOVE)
    },
    private val afterMove: () -> Unit = {},
    private val remover: ImportDirectoryRemover = NioImportDirectoryRemover(),
) {
    private fun isUnsafeId(id: String): Boolean =
        id.isEmpty() || id.contains('/') || id.contains('\\') || id.contains('.')

    fun begin(payloadKind: String): String? {
        if (payloadKind != "file" && payloadKind != "text") return null
        return try {
            val id = UUID.randomUUID().toString()
            if (isUnsafeId(id)) return null
            val stagingDir = root.resolve(".staging").resolve(id)
            Files.createDirectories(stagingDir)
            kindMap[kindKey(root, id)] = payloadKind
            id
        } catch (_: Exception) {
            null
        }
    }

    fun publish(id: String, displayName: String, mime: String, size: Long): Boolean =
        ImportRootLock.withLock(root) {
            if (isUnsafeId(id)) return@withLock false
            val key = kindKey(root, id)
            val kind = kindMap[key] ?: return@withLock false
            val stagingDir = root.resolve(".staging").resolve(id)
            if (!Files.isDirectory(stagingDir, NOFOLLOW_LINKS)) return@withLock false
            val payloadPath = stagingDir.resolve("payload")
            if (!Files.isRegularFile(payloadPath, NOFOLLOW_LINKS)) return@withLock false
            val actualSize = try { Files.size(payloadPath) } catch (_: Exception) { return@withLock false }
            if (size != actualSize) return@withLock false

            var moved = false
            try {
                fsync(payloadPath)
                fsync(stagingDir)
                val targetDir = root.resolve(id)
                move(stagingDir, targetDir)
                moved = true
                afterMove()
                var current: Path? = targetDir
                while (current != null) {
                    fsync(current)
                    if (current == root.parent) break
                    current = current.parent
                }
                val row = ImportItemRow(
                    id = id,
                    payloadKind = kind,
                    displayName = displayName,
                    mime = mime,
                    byteSize = size,
                    state = "pending",
                    createdAt = clock(),
                    progressPath = null,
                    progressAt = null,
                    progressInstanceId = null,
                    backoffInstanceId = null,
                    attemptCount = 0,
                    nextAttemptAt = null,
                    attentionError = null,
                    attentionReasonCode = null,
                    attentionKind = null,
                    attentionInstanceId = null,
                    attentionJournalVersion = null,
                )
                dao.insert(row)
                kindMap.remove(key)
                true
            } catch (_: Exception) {
                if (moved) {
                    kindMap.remove(key)
                }
                false
            }
        }

    fun abandon(id: String): Unit =
        ImportRootLock.withLock(root) {
            if (isUnsafeId(id)) return@withLock
            val key = kindKey(root, id)
            kindMap.remove(key)
            val stagingDir = root.resolve(".staging").resolve(id)
            remover.remove(stagingDir)
        }

    fun finish(): Unit =
        ImportRootLock.withLock(root) {
            val all = try { dao.all() } catch (_: Exception) { emptyList() }
            val delivered = all.filter { it.state == "delivered" }
                .sortedWith(compareBy<ImportItemRow> { it.createdAt }.thenBy { it.id })
            for (row in delivered) {
                if (isUnsafeId(row.id)) continue
                val itemDir = root.resolve(row.id)
                val removed = remover.remove(itemDir)
                if (!removed) continue
                try {
                    dao.delete(row.id)
                } catch (_: Exception) {
                }
            }
        }

    fun recover(): Unit =
        ImportRootLock.withLock(root) {
            finish()

            val stagingRoot = root.resolve(".staging")
            if (Files.isDirectory(stagingRoot, NOFOLLOW_LINKS)) {
                try {
                    Files.newDirectoryStream(stagingRoot).use { stream ->
                        for (child in stream) {
                            try {
                                if (Files.isDirectory(child, NOFOLLOW_LINKS)) {
                                    val mtime = Files.getLastModifiedTime(child).toMillis()
                                    val age = clock() - mtime
                                    if (age > 3_600_000L) {
                                        remover.remove(child)
                                        val childId = child.fileName.toString()
                                        kindMap.remove(kindKey(root, childId))
                                    }
                                }
                            } catch (_: Exception) {
                            }
                        }
                    }
                } catch (_: Exception) {
                }
            }

            val allRows = try { dao.all() } catch (_: Exception) { emptyList() }
            val rowIds = allRows.map { it.id }.toSet()
            if (Files.isDirectory(root, NOFOLLOW_LINKS)) {
                try {
                    Files.newDirectoryStream(root).use { stream ->
                        for (child in stream) {
                            try {
                                val name = child.fileName.toString()
                                if (Files.isDirectory(child, NOFOLLOW_LINKS) && name != ".staging" && child.parent == root) {
                                    if (name !in rowIds) {
                                        remover.remove(child)
                                    }
                                }
                            } catch (_: Exception) {
                            }
                        }
                    }
                } catch (_: Exception) {
                }
            }

            for (row in allRows.sortedWith(compareBy<ImportItemRow> { it.createdAt }.thenBy { it.id })) {
                if (isUnsafeId(row.id)) continue
                val itemDir = root.resolve(row.id)
                if (!Files.exists(itemDir, NOFOLLOW_LINKS)) {
                    if (row.state == "delivered") {
                        try {
                            dao.delete(row.id)
                        } catch (_: Exception) {
                        }
                    } else if (row.state == "attention" && row.attentionKind == "missing_copy" && row.attentionInstanceId == null && row.attentionJournalVersion == null) {
                        // Already local attention, left as it is
                    } else {
                        val localAttention = row.copy(
                            state = "attention",
                            attentionKind = "missing_copy",
                            attentionError = null,
                            attentionReasonCode = null,
                            attentionInstanceId = null,
                            attentionJournalVersion = null,
                            progressPath = null,
                            progressAt = null,
                            progressInstanceId = null,
                            backoffInstanceId = null,
                            attemptCount = 0,
                            nextAttemptAt = null,
                        )
                        try {
                            dao.update(localAttention)
                        } catch (_: Exception) {
                        }
                    }
                }
            }
        }

    fun markSaved(id: String, path: String, timestamp: Long, instance: String): Boolean {
        val row = try { dao.byId(id) } catch (_: Exception) { null } ?: return false
        val isPending = row.state == "pending"
        val isJournalAttention = row.state == "attention" && row.attentionInstanceId != null && row.attentionJournalVersion != null
        if (!isPending && !isJournalAttention) return false
        val updated = row.copy(
            progressPath = path,
            progressAt = timestamp,
            progressInstanceId = instance,
        )
        return try { dao.update(updated) > 0 } catch (_: Exception) { false }
    }

    fun markRetry(id: String, instance: String, now: Long): Boolean {
        val row = try { dao.byId(id) } catch (_: Exception) { null } ?: return false
        if (row.state != "pending") return false
        val count = if (row.backoffInstanceId == instance) row.attemptCount + 1 else 1
        val nextAttempt = now + app.solstone.core.queue.retryBackoffMs(count, RetryDecision.RETRY)
        val updated = row.copy(
            backoffInstanceId = instance,
            attemptCount = count,
            nextAttemptAt = nextAttempt,
        )
        return try { dao.update(updated) > 0 } catch (_: Exception) { false }
    }

    fun markAttention(
        id: String,
        error: String,
        reasonCode: String,
        kind: String?,
        instance: String?,
        journalVersion: String?,
    ): Boolean {
        if (instance == null || journalVersion == null) return false
        val row = try { dao.byId(id) } catch (_: Exception) { null } ?: return false
        val isPending = row.state == "pending"
        val isJournalAttention = row.state == "attention" && row.attentionInstanceId != null && row.attentionJournalVersion != null
        if (!isPending && !isJournalAttention) return false
        val updated = row.copy(
            state = "attention",
            attentionInstanceId = instance,
            attentionJournalVersion = journalVersion,
            attentionKind = kind,
            attentionError = error,
            attentionReasonCode = reasonCode,
        )
        return try { dao.update(updated) > 0 } catch (_: Exception) { false }
    }

    fun markDelivered(id: String): Boolean {
        val row = try { dao.byId(id) } catch (_: Exception) { null } ?: return false
        if (row.state == "delivered") return true
        val isPending = row.state == "pending"
        val isJournalAttention = row.state == "attention" && row.attentionInstanceId != null && row.attentionJournalVersion != null
        if (!isPending && !isJournalAttention) return false
        val updated = row.copy(state = "delivered")
        return try { dao.update(updated) > 0 } catch (_: Exception) { false }
    }

    fun resetAttention(id: String): Boolean {
        val row = try { dao.byId(id) } catch (_: Exception) { null } ?: return false
        val isJournalAttention = row.state == "attention" && row.attentionInstanceId != null && row.attentionJournalVersion != null
        if (!isJournalAttention) return false
        val updated = row.copy(
            state = "pending",
            progressPath = null,
            progressAt = null,
            progressInstanceId = null,
            backoffInstanceId = null,
            attemptCount = 0,
            nextAttemptAt = null,
            attentionError = null,
            attentionReasonCode = null,
            attentionKind = null,
            attentionInstanceId = null,
            attentionJournalVersion = null,
        )
        return try { dao.update(updated) > 0 } catch (_: Exception) { false }
    }

    fun eligible(instance: String, journalVersion: String, now: Long, limit: Int): List<ImportCandidate> {
        if (limit <= 0) return emptyList()
        val rows = try { dao.all() } catch (_: Exception) { emptyList() }
            .sortedWith(compareBy<ImportItemRow> { it.createdAt }.thenBy { it.id })
        val candidates = mutableListOf<ImportCandidate>()
        for (row in rows) {
            if (row.state == "delivered") continue
            val isJournalAttention = row.state == "attention" && row.attentionInstanceId != null && row.attentionJournalVersion != null
            val isLocalAttention = row.state == "attention" && !isJournalAttention
            if (isLocalAttention) continue

            val isEligible = when {
                row.state == "pending" -> {
                    if (row.backoffInstanceId == instance && row.nextAttemptAt != null) {
                        now >= row.nextAttemptAt
                    } else {
                        true
                    }
                }
                isJournalAttention -> {
                    row.attentionInstanceId != instance || row.attentionJournalVersion != journalVersion
                }
                else -> false
            }

            if (isEligible) {
                val progress = if (row.progressInstanceId == instance && row.progressPath != null && row.progressAt != null) {
                    ImportProgress(row.progressPath, row.progressAt)
                } else {
                    null
                }
                candidates.add(ImportCandidate(row.id, progress))
                if (candidates.size == limit) break
            }
        }
        return candidates
    }

    fun held(instance: String): List<ImportHeldItem> {
        val rows = try { dao.all() } catch (_: Exception) { emptyList() }
            .filter { it.state != "delivered" }
            .sortedWith(compareBy<ImportItemRow> { it.createdAt }.thenBy { it.id })
        return rows.map { row ->
            val progress = if (row.progressInstanceId == instance && row.progressPath != null && row.progressAt != null) {
                ImportProgress(row.progressPath, row.progressAt)
            } else {
                null
            }
            val backoff = if (row.backoffInstanceId == instance && row.nextAttemptAt != null) {
                ImportBackoff(row.attemptCount, row.nextAttemptAt)
            } else {
                null
            }
            ImportHeldItem(row.id, row.state, row.displayName, progress, backoff)
        }
    }

    fun status(): ImportCustodyStatus {
        val rows = try { dao.all() } catch (_: Exception) { emptyList() }
            .sortedWith(compareBy<ImportItemRow> { it.createdAt }.thenBy { it.id })
        val pendingCount = rows.count { it.state == "pending" }
        val attentionRows = rows.filter { it.state == "attention" }
        val attentionCount = attentionRows.size
        val attentionFacts = attentionRows.map { row ->
            ImportAttentionFact(row.attentionError, row.attentionKind, row.displayName)
        }
        return ImportCustodyStatus(pendingCount, attentionCount, attentionFacts)
    }

    fun item(id: String): ImportItemDescriptor? {
        if (isUnsafeId(id)) return null
        val row = try { dao.byId(id) } catch (_: Exception) { null } ?: return null
        return ImportItemDescriptor(
            id = row.id,
            payloadKind = row.payloadKind,
            payloadPath = root.resolve(row.id).resolve("payload"),
            displayName = row.displayName,
            mime = row.mime,
            size = row.byteSize,
        )
    }
}
