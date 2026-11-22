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

const val IMPORT_STATE_PENDING = "pending"
const val IMPORT_STATE_READY_TO_REVIEW = "ready_to_review"
const val IMPORT_STATE_REVIEW_EXPIRED = "review_expired"
const val IMPORT_STATE_ATTENTION = "attention"
const val IMPORT_STATE_DELIVERED = "delivered"

const val RECENT_RECEIPT_WINDOW_MS = 24L * 60L * 60L * 1000L
const val RECENT_RECEIPT_MAX_COUNT = 5

/** Strict ASCII `YYYYMMDD_HHMMSS` validation (never matches Unicode digits). */
fun isValidAsciiImportId(raw: String?): Boolean {
    if (raw == null || raw.length != 15 || raw[8] != '_') return false
    for (i in 0 until 15) {
        if (i == 8) continue
        if (raw[i] !in '0'..'9') return false
    }
    return true
}

/**
 * Refusals a manual `try now` could plausibly change. Confirmation-required `409`s never enter
 * attention; non-confirmation `import_preview_required` kinds like `plan_failed`,
 * `nothing_to_import`, `invalid_request`, `not_found`, or unknown kinds are non-retryable.
 */
fun isImportRefusalRetryable(reasonCode: String?, kind: String?): Boolean {
    if (kind == "client_item_conflict") return false
    return when (reasonCode) {
        "import_queue_unreachable",
        "import_metadata_failed",
        "import_not_found",
        -> true
        "import_preview_required" -> kind == "source_changed" || kind == "already_running"
        else -> false
    }
}

data class ImportProgress(val path: String, val timestamp: String)
data class ImportBackoff(val attemptCount: Int, val nextAttemptAt: Long)
data class ImportCandidate(
    val id: String,
    val progress: ImportProgress?,
    val hasCaption: Boolean = false,
)
data class ImportReviewCandidate(
    val id: String,
    val importId: String,
    val instanceId: String,
)
data class ImportRecentReceipt(
    val id: String,
    val filename: String,
    val importId: String?,
    val instanceId: String,
    val deliveredAt: Long,
)
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
/** What a request to change one item did. */
enum class ImportChange {
    DONE,

    /** The item is in the middle of a journal exchange; nothing was changed. */
    IN_FLIGHT,

    /** The journal already has it; nothing was changed. */
    DELIVERED,

    /** The item isn't in a state this change applies to. */
    REFUSED,

    /** No such item: it was removed, or finished and deleted after delivery. */
    GONE,
}

/** Where an item's attention verdict came from, for what the owner is shown. */
enum class ImportAttentionScope {
    /** No verdict. */
    NONE,

    /** The journal it would go to now refused it, at its current version. */
    HERE,

    /** Another journal, or an older version of this one, refused it; it is going again. */
    ELSEWHERE,

    /** The copy on this device is missing. */
    LOCAL,
}

data class ImportListItem(
    val id: String,
    val displayName: String,
    val state: String,
    val error: String?,
    val reasonCode: String?,
    val kind: String?,
    val scope: ImportAttentionScope,
    val inFlight: Boolean,
    val hasSavedProgress: Boolean,
    val hasCaption: Boolean = false,
    val reviewImportId: String? = null,
    val reviewReasonCode: String? = null,
    val reviewKind: String? = null,
    val reviewInstanceId: String? = null,
    val reviewExpired: Boolean = false,
    val byteSize: Long = 0L,
)

data class ImportLastDelivery(val at: Long, val instanceId: String)

data class ImportItemDescriptor(
    val id: String,
    val payloadKind: String,
    val payloadPath: Path,
    val displayName: String,
    val mime: String,
    val size: Long,
    val caption: String? = null,
    /** The journal import page's card this item was picked for, when the page handed it over. */
    val area: String? = null,
    /** The page's choices for it, as the JSON object text it sent. */
    val context: String? = null,
)

/** Where one handed-over item is now, as the journal page is told. */
data class ImportHandoffStatus(
    /** `waiting`, `uploading`, `attention`, `delivered`, or `gone`. */
    val state: String,
    /** The journal's import row, when it is known. */
    val importId: String? = null,
    /** The journal's own sentence for a refusal. */
    val reason: String? = null,
    val reviewRequired: Boolean? = null,
    val reviewExpired: Boolean? = null,
    val reasonCode: String? = null,
    val kind: String? = null,
    val retryable: Boolean? = null,
    val deviceNoun: String? = null,
    val sizeBytes: Long? = null,
)

/** The page's card and choices for a picked item, kept beside its bytes. */
const val IMPORT_AREA_FILE = "area.txt"
const val IMPORT_CONTEXT_FILE = "context.json"

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

private data class HandoffReceipt(val importId: String?)

/**
 * The journal row each recently delivered item became, in process memory, so a journal page that
 * handed an item over can link to the row after the delivered item has left the device.
 */
private val receipts = object : LinkedHashMap<String, HandoffReceipt>() {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, HandoffReceipt>?): Boolean = size > 256
}

/**
 * Items in the middle of a journal exchange, in process memory: the sync worker and the screens
 * run in one process, and a process death ends every exchange, so nothing is persisted.
 */
private val inFlight: MutableSet<String> = ConcurrentHashMap.newKeySet()

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
                for (sidecar in listOf("caption.txt", IMPORT_AREA_FILE, IMPORT_CONTEXT_FILE)) {
                    val sidecarPath = stagingDir.resolve(sidecar)
                    if (Files.isRegularFile(sidecarPath, NOFOLLOW_LINKS)) {
                        fsync(sidecarPath)
                    }
                }
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
                    progressTimestamp = null,
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
                if (!dao.insertIfNew(row, row.createdAt)) {
                    kindMap.remove(key)
                    return@withLock false
                }
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
                            state = IMPORT_STATE_ATTENTION,
                            attentionKind = "missing_copy",
                            attentionError = null,
                            attentionReasonCode = null,
                            attentionInstanceId = null,
                            attentionJournalVersion = null,
                            progressPath = null,
                            progressTimestamp = null,
                            progressInstanceId = null,
                            backoffInstanceId = null,
                            attemptCount = 0,
                            nextAttemptAt = null,
                            reviewImportId = null,
                            reviewReasonCode = null,
                            reviewKind = null,
                            reviewInstanceId = null,
                            reviewExpired = false,
                        )
                        try {
                            dao.update(localAttention)
                        } catch (_: Exception) {
                        }
                    }
                }
            }
        }

    fun markSaved(id: String, path: String, timestamp: String, instance: String): Boolean {
        val row = try { dao.byId(id) } catch (_: Exception) { null } ?: return false
        val isPending = row.state == "pending"
        val isJournalAttention = row.state == "attention" && row.attentionInstanceId != null && row.attentionJournalVersion != null
        if (!isPending && !isJournalAttention) return false
        val updated = row.copy(
            progressPath = path,
            progressTimestamp = timestamp,
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
        if (row.state == IMPORT_STATE_DELIVERED) return true
        val isPending = row.state == IMPORT_STATE_PENDING
        val isReview = row.state == IMPORT_STATE_READY_TO_REVIEW || row.state == IMPORT_STATE_REVIEW_EXPIRED
        val isJournalAttention = row.state == IMPORT_STATE_ATTENTION && row.attentionInstanceId != null && row.attentionJournalVersion != null
        if (!isPending && !isReview && !isJournalAttention) return false
        val updated = row.copy(state = IMPORT_STATE_DELIVERED)
        return try { dao.update(updated) > 0 } catch (_: Exception) { false }
    }

    fun resetAttention(id: String): Boolean {
        val row = try { dao.byId(id) } catch (_: Exception) { null } ?: return false
        val isJournalAttention = row.state == IMPORT_STATE_ATTENTION && row.attentionInstanceId != null && row.attentionJournalVersion != null
        if (!isJournalAttention) return false
        val updated = row.copy(
            state = IMPORT_STATE_PENDING,
            progressPath = null,
            progressTimestamp = null,
            progressInstanceId = null,
            backoffInstanceId = null,
            attemptCount = 0,
            nextAttemptAt = null,
            attentionError = null,
            attentionReasonCode = null,
            attentionKind = null,
            attentionInstanceId = null,
            attentionJournalVersion = null,
            reviewImportId = null,
            reviewReasonCode = null,
            reviewKind = null,
            reviewInstanceId = null,
            reviewExpired = false,
        )
        return try { dao.update(updated) > 0 } catch (_: Exception) { false }
    }

    fun eligible(instance: String, journalVersion: String, now: Long, limit: Int): List<ImportCandidate> {
        if (limit <= 0) return emptyList()
        val rows = try { dao.all() } catch (_: Exception) { emptyList() }
            .sortedWith(compareBy<ImportItemRow> { it.createdAt }.thenBy { it.id })
        val candidates = mutableListOf<ImportCandidate>()
        for (row in rows) {
            if (row.state == IMPORT_STATE_DELIVERED ||
                row.state == IMPORT_STATE_READY_TO_REVIEW ||
                row.state == IMPORT_STATE_REVIEW_EXPIRED
            ) {
                continue
            }
            val isJournalAttention = row.state == IMPORT_STATE_ATTENTION && row.attentionInstanceId != null && row.attentionJournalVersion != null
            val isLocalAttention = row.state == IMPORT_STATE_ATTENTION && !isJournalAttention
            if (isLocalAttention) continue

            val isEligible = when {
                row.state == IMPORT_STATE_PENDING -> {
                    if (row.backoffInstanceId == instance && row.nextAttemptAt != null) {
                        now >= row.nextAttemptAt
                    } else {
                        true
                    }
                }
                isJournalAttention -> {
                    row.attentionInstanceId != instance ||
                        (row.autoRetriesOnVersionBump() && row.attentionJournalVersion != journalVersion)
                }
                else -> false
            }

            if (isEligible) {
                val progress = if (row.progressInstanceId == instance && row.progressPath != null && row.progressTimestamp != null) {
                    ImportProgress(row.progressPath, row.progressTimestamp)
                } else {
                    null
                }
                candidates.add(ImportCandidate(row.id, progress, hasCaption = readCaption(root.resolve(row.id)) != null))
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
            val progress = if (row.progressInstanceId == instance && row.progressPath != null && row.progressTimestamp != null) {
                ImportProgress(row.progressPath, row.progressTimestamp)
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

    /**
     * Mark [id] as in a journal exchange with [instance], under the store lock. An item whose
     * attention came from another journal or an older journal version goes back to pending,
     * since it is now being sent again. Returns [ImportChange.DONE] only when the exchange may go.
     */
    fun beginExchange(id: String, instance: String, journalVersion: String): ImportChange =
        ImportRootLock.withLock(root) {
            val row = try { dao.byId(id) } catch (_: Exception) { null } ?: return@withLock ImportChange.GONE
            val key = kindKey(root, id)
            if (key in inFlight) return@withLock ImportChange.IN_FLIGHT
            if (row.state == "delivered") return@withLock ImportChange.DELIVERED
            val isJournalAttention = row.state == "attention" && row.attentionInstanceId != null && row.attentionJournalVersion != null
            when {
                row.state == IMPORT_STATE_PENDING -> Unit
                isJournalAttention && (
                    row.attentionInstanceId != instance ||
                        (row.autoRetriesOnVersionBump() && row.attentionJournalVersion != journalVersion)
                    ) -> {
                    val reopened = row.copy(
                        state = IMPORT_STATE_PENDING,
                        attentionError = null,
                        attentionReasonCode = null,
                        attentionKind = null,
                        attentionInstanceId = null,
                        attentionJournalVersion = null,
                    )
                    if ((try { dao.update(reopened) } catch (_: Exception) { 0 }) == 0) return@withLock ImportChange.REFUSED
                }
                else -> return@withLock ImportChange.REFUSED
            }
            inFlight.add(key)
            ImportChange.DONE
        }

    /**
     * Record that the journal staged [id], keeping the exchange open for its start. Under the store
     * lock, so nothing can land between the answer and the record.
     */
    fun recordSaved(id: String, path: String, timestamp: String, instance: String): ImportChange =
        ImportRootLock.withLock(root) {
            if (try { dao.byId(id) } catch (_: Exception) { null } == null) {
                inFlight.remove(kindKey(root, id))
                return@withLock ImportChange.GONE
            }
            if (markSaved(id, path, timestamp, instance)) ImportChange.DONE else ImportChange.REFUSED
        }

    /**
     * Record that [id] is staged on [instance] awaiting owner preview & confirmation (`409
     * import_preview_required` with a confirmation-required kind and valid ASCII `import_id`),
     * preserving local payload bytes on disk and ending the active exchange.
     */
    fun recordReviewRequired(
        id: String,
        importId: String,
        reasonCode: String,
        kind: String,
        error: String?,
        instance: String,
    ): ImportChange =
        ImportRootLock.withLock(root) {
            try {
                if (!isValidAsciiImportId(importId)) return@withLock ImportChange.REFUSED
                val row = try { dao.byId(id) } catch (_: Exception) { null } ?: return@withLock ImportChange.GONE
                val isJournalAttention = row.state == IMPORT_STATE_ATTENTION && row.attentionInstanceId != null && row.attentionJournalVersion != null
                if (row.state != IMPORT_STATE_PENDING && row.state != IMPORT_STATE_READY_TO_REVIEW && !isJournalAttention) {
                    return@withLock ImportChange.REFUSED
                }
                val updated = row.copy(
                    state = IMPORT_STATE_READY_TO_REVIEW,
                    progressTimestamp = row.progressTimestamp ?: importId,
                    progressInstanceId = row.progressInstanceId ?: instance,
                    backoffInstanceId = null,
                    attemptCount = 0,
                    nextAttemptAt = null,
                    attentionError = null,
                    attentionReasonCode = null,
                    attentionKind = null,
                    attentionInstanceId = null,
                    attentionJournalVersion = null,
                    reviewImportId = importId,
                    reviewReasonCode = reasonCode,
                    reviewKind = kind,
                    reviewInstanceId = instance,
                    reviewExpired = false,
                )
                if ((try { dao.update(updated) } catch (_: Exception) { 0 }) > 0) ImportChange.DONE else ImportChange.REFUSED
            } finally {
                inFlight.remove(kindKey(root, id))
            }
        }

    /**
     * Record that [id] reached the journal at [instance] and end its exchange, in one hold of the
     * store lock. The 24-hour recent receipt is persisted durably BEFORE the item is marked
     * delivered for payload deletion.
     */
    fun recordDelivered(id: String, instance: String, importId: String? = null): ImportChange =
        ImportRootLock.withLock(root) {
            try {
                val row = try { dao.byId(id) } catch (_: Exception) { null } ?: return@withLock ImportChange.GONE
                val now = clock()
                val rawHandoffId = importId ?: row.reviewImportId ?: row.progressTimestamp
                val validReceiptId = rawHandoffId?.takeIf(::isValidAsciiImportId)
                // Persist the 24h recent receipt durably before marking delivered / deleting bytes.
                runCatching { persistRecentReceiptLocked(row, instance, validReceiptId, now) }
                if (!markDelivered(id)) return@withLock ImportChange.REFUSED
                synchronized(receipts) { receipts[kindKey(root, id)] = HandoffReceipt(rawHandoffId) }
                runCatching { dao.putDelivery(ImportDeliveryRow(lastDeliveredAt = now, instanceId = instance)) }
                ImportChange.DONE
            } finally {
                inFlight.remove(kindKey(root, id))
            }
        }

    /** Record the journal's refusal of [id] and end its exchange, in one hold of the store lock. */
    fun recordAttention(id: String, error: String, reasonCode: String, kind: String?, instance: String, journalVersion: String): ImportChange =
        ImportRootLock.withLock(root) {
            try {
                if (try { dao.byId(id) } catch (_: Exception) { null } == null) return@withLock ImportChange.GONE
                if (markAttention(id, error, reasonCode, kind, instance, journalVersion)) ImportChange.DONE else ImportChange.REFUSED
            } finally {
                inFlight.remove(kindKey(root, id))
            }
        }

    /** End [id]'s exchange without recording anything (a retry, a pairing change, an error). */
    fun endExchange(id: String) {
        ImportRootLock.withLock(root) { inFlight.remove(kindKey(root, id)) }
    }

    /** Whether [id] is in a journal exchange now. */
    fun isInFlight(id: String): Boolean = kindKey(root, id) in inFlight

    /** Items currently awaiting review on [instance], oldest first, eligible for safe reconciliation. */
    fun reviewCandidates(instance: String): List<ImportReviewCandidate> {
        val rows = try { dao.all() } catch (_: Exception) { emptyList() }
            .sortedWith(compareBy<ImportItemRow> { it.createdAt }.thenBy { it.id })
        return rows.mapNotNull { row ->
            val importId = row.reviewImportId
            if (row.state == IMPORT_STATE_READY_TO_REVIEW &&
                row.reviewInstanceId == instance &&
                isValidAsciiImportId(importId) &&
                kindKey(root, row.id) !in inFlight
            ) {
                ImportReviewCandidate(row.id, importId!!, instance)
            } else {
                null
            }
        }
    }

    /**
     * When the device pairs with a different journal instance, reset `ready_to_review` and
     * `review_expired` items belonging to the prior instance back to pending/waiting (clearing
     * saved path/timestamp/instance/review fields) so they deliver cleanly to the new journal
     * without ever querying Journal B with Journal A's `import_id`.
     */
    fun resetReviewItemsForInstanceChange(currentInstanceId: String): Int =
        ImportRootLock.withLock(root) {
            val rows = try { dao.all() } catch (_: Exception) { emptyList() }
            var reset = 0
            for (row in rows) {
                if (row.state != IMPORT_STATE_READY_TO_REVIEW && row.state != IMPORT_STATE_REVIEW_EXPIRED) continue
                if (row.reviewInstanceId == currentInstanceId) continue
                if (kindKey(root, row.id) in inFlight) continue
                val cleared = row.copy(
                    state = IMPORT_STATE_PENDING,
                    progressPath = null,
                    progressTimestamp = null,
                    progressInstanceId = null,
                    backoffInstanceId = null,
                    attemptCount = 0,
                    nextAttemptAt = null,
                    attentionError = null,
                    attentionReasonCode = null,
                    attentionKind = null,
                    attentionInstanceId = null,
                    attentionJournalVersion = null,
                    reviewImportId = null,
                    reviewReasonCode = null,
                    reviewKind = null,
                    reviewInstanceId = null,
                    reviewExpired = false,
                )
                if ((try { dao.update(cleared) } catch (_: Exception) { 0 }) > 0) {
                    reset++
                }
            }
            reset
        }

    /**
     * Complete reconciliation for a `ready_to_review` item confirmed on the journal. Re-verifies
     * `(id, expectedImportId, expectedInstanceId)` under the store lock so a concurrent `sendAgain`
     * or `remove` cannot be clobbered by a stale response. Persists the 24h recent receipt durably
     * BEFORE deleting local payload bytes.
     */
    fun reconcileConfirmed(id: String, expectedImportId: String, expectedInstanceId: String): ImportChange =
        ImportRootLock.withLock(root) {
            if (isUnsafeId(id) || !isValidAsciiImportId(expectedImportId)) return@withLock ImportChange.REFUSED
            val row = try { dao.byId(id) } catch (_: Exception) { null } ?: return@withLock ImportChange.GONE
            if (kindKey(root, id) in inFlight) return@withLock ImportChange.IN_FLIGHT
            if (row.state != IMPORT_STATE_READY_TO_REVIEW ||
                row.reviewImportId != expectedImportId ||
                row.reviewInstanceId != expectedInstanceId
            ) {
                return@withLock ImportChange.REFUSED
            }
            val now = clock()
            // Persist the 24h receipt durably BEFORE mutating the row to delivered or deleting local bytes.
            try {
                persistRecentReceiptLocked(row, expectedInstanceId, expectedImportId, now)
            } catch (_: Exception) {
                return@withLock ImportChange.REFUSED
            }
            val marked = try { dao.update(row.copy(state = IMPORT_STATE_DELIVERED)) > 0 } catch (_: Exception) { false }
            if (!marked) return@withLock ImportChange.REFUSED
            synchronized(receipts) { receipts[kindKey(root, id)] = HandoffReceipt(expectedImportId) }
            runCatching { dao.putDelivery(ImportDeliveryRow(lastDeliveredAt = now, instanceId = expectedInstanceId)) }
            if (remover.remove(root.resolve(id))) {
                runCatching { dao.delete(id) }
            }
            ImportChange.DONE
        }

    /**
     * Mark a `ready_to_review` item as `review_expired` after `404` on reconciliation, keeping
     * local payload bytes intact for one-tap `send again to review`. Re-verifies `(id,
     * expectedImportId, expectedInstanceId)` under the store lock.
     */
    fun reconcileExpired(id: String, expectedImportId: String, expectedInstanceId: String): ImportChange =
        ImportRootLock.withLock(root) {
            if (isUnsafeId(id) || !isValidAsciiImportId(expectedImportId)) return@withLock ImportChange.REFUSED
            val row = try { dao.byId(id) } catch (_: Exception) { null } ?: return@withLock ImportChange.GONE
            if (kindKey(root, id) in inFlight) return@withLock ImportChange.IN_FLIGHT
            if (row.state != IMPORT_STATE_READY_TO_REVIEW ||
                row.reviewImportId != expectedImportId ||
                row.reviewInstanceId != expectedInstanceId
            ) {
                return@withLock ImportChange.REFUSED
            }
            val updated = row.copy(
                state = IMPORT_STATE_REVIEW_EXPIRED,
                reviewExpired = true,
            )
            if ((try { dao.update(updated) } catch (_: Exception) { 0 }) > 0) ImportChange.DONE else ImportChange.REFUSED
        }

    /**
     * Re-enqueue an expired (or ready-to-review) item from local bytes by clearing saved staging
     * and review state. Never double-counts `source_intake`.
     */
    fun sendAgain(id: String): ImportChange =
        ImportRootLock.withLock(root) {
            if (isUnsafeId(id)) return@withLock ImportChange.GONE
            val row = try { dao.byId(id) } catch (_: Exception) { null } ?: return@withLock ImportChange.GONE
            if (kindKey(root, id) in inFlight) return@withLock ImportChange.IN_FLIGHT
            if (row.state == IMPORT_STATE_DELIVERED) return@withLock ImportChange.DELIVERED
            if (row.state != IMPORT_STATE_REVIEW_EXPIRED && row.state != IMPORT_STATE_READY_TO_REVIEW) {
                return@withLock ImportChange.REFUSED
            }
            val updated = row.copy(
                state = IMPORT_STATE_PENDING,
                progressPath = null,
                progressTimestamp = null,
                progressInstanceId = null,
                backoffInstanceId = null,
                attemptCount = 0,
                nextAttemptAt = null,
                attentionError = null,
                attentionReasonCode = null,
                attentionKind = null,
                attentionInstanceId = null,
                attentionJournalVersion = null,
                reviewImportId = null,
                reviewReasonCode = null,
                reviewKind = null,
                reviewInstanceId = null,
                reviewExpired = false,
            )
            if ((try { dao.update(updated) } catch (_: Exception) { 0 }) > 0) ImportChange.DONE else ImportChange.REFUSED
        }

    /** Persisted 24h recent receipts (max 5 newest) for [instanceId], pruned on read. */
    fun recentReceipts(instanceId: String?): List<ImportRecentReceipt> =
        ImportRootLock.withLock(root) {
            if (instanceId.isNullOrEmpty()) return@withLock emptyList()
            val now = clock()
            val cutoff = now - RECENT_RECEIPT_WINDOW_MS
            runCatching { dao.pruneExpiredReceipts(cutoff) }
            runCatching { dao.trimRecentReceipts(instanceId, RECENT_RECEIPT_MAX_COUNT) }
            val rows = try {
                dao.recentReceipts(instanceId, cutoff, RECENT_RECEIPT_MAX_COUNT)
            } catch (_: Exception) {
                emptyList()
            }
            rows.map { row ->
                ImportRecentReceipt(
                    id = row.id,
                    filename = row.filename,
                    importId = row.importId?.takeIf(::isValidAsciiImportId),
                    instanceId = row.instanceId,
                    deliveredAt = row.deliveredAt,
                )
            }
        }

    private fun persistRecentReceiptLocked(
        row: ImportItemRow,
        instanceId: String,
        validImportId: String?,
        now: Long,
    ) {
        dao.putRecentReceipt(
            ImportRecentReceiptRow(
                id = row.id,
                filename = row.displayName,
                importId = validImportId,
                instanceId = instanceId,
                deliveredAt = now,
            ),
        )
        dao.pruneExpiredReceipts(now - RECENT_RECEIPT_WINDOW_MS)
        dao.trimRecentReceipts(instanceId, RECENT_RECEIPT_MAX_COUNT)
    }

    private fun ImportItemRow.autoRetriesOnVersionBump(): Boolean =
        attentionReasonCode != "import_preview_required"

    /**
     * Remove [id] from this device: its row first, then its bytes, so an interrupted removal
     * leaves only a directory without a row, which [recover] reaps. Refused for an item in an
     * exchange or one the journal already has.
     */
    fun remove(id: String): ImportChange =
        ImportRootLock.withLock(root) {
            if (isUnsafeId(id)) return@withLock ImportChange.GONE
            val row = try { dao.byId(id) } catch (_: Exception) { null } ?: return@withLock ImportChange.GONE
            if (kindKey(root, id) in inFlight) return@withLock ImportChange.IN_FLIGHT
            if (row.state == "delivered") return@withLock ImportChange.DELIVERED
            val deleted = try { dao.delete(id) } catch (_: Exception) { 0 }
            if (deleted == 0) return@withLock ImportChange.REFUSED
            remover.remove(root.resolve(id))
            ImportChange.DONE
        }

    /** The owner's retry of an item the journal refused; refused while it is in an exchange. */
    fun retry(id: String): ImportChange =
        ImportRootLock.withLock(root) {
            val row = try { dao.byId(id) } catch (_: Exception) { null } ?: return@withLock ImportChange.GONE
            if (kindKey(root, id) in inFlight) return@withLock ImportChange.IN_FLIGHT
            if (row.state == "delivered") return@withLock ImportChange.DELIVERED
            if (resetAttention(id)) ImportChange.DONE else ImportChange.REFUSED
        }

    /** Every item still on this device, oldest first, with where its attention came from. */
    fun items(instance: String?, journalVersion: String?): List<ImportListItem> {
        val rows = try { dao.all() } catch (_: Exception) { emptyList() }
            .filter { it.state != IMPORT_STATE_DELIVERED }
            .sortedWith(compareBy<ImportItemRow> { it.createdAt }.thenBy { it.id })
        return rows.map { row ->
            val journalAttention = row.state == IMPORT_STATE_ATTENTION && row.attentionInstanceId != null && row.attentionJournalVersion != null
            val scope = when {
                row.state != IMPORT_STATE_ATTENTION -> ImportAttentionScope.NONE
                !journalAttention -> ImportAttentionScope.LOCAL
                row.attentionInstanceId == instance && (!row.autoRetriesOnVersionBump() || row.attentionJournalVersion == journalVersion) -> ImportAttentionScope.HERE
                else -> ImportAttentionScope.ELSEWHERE
            }
            ImportListItem(
                id = row.id,
                displayName = row.displayName,
                state = row.state,
                error = row.attentionError,
                reasonCode = row.attentionReasonCode,
                kind = row.attentionKind,
                scope = scope,
                inFlight = kindKey(root, row.id) in inFlight,
                hasSavedProgress = row.progressPath != null && row.progressTimestamp != null,
                hasCaption = readCaption(root.resolve(row.id)) != null,
                reviewImportId = row.reviewImportId?.takeIf(::isValidAsciiImportId),
                reviewReasonCode = row.reviewReasonCode,
                reviewKind = row.reviewKind,
                reviewInstanceId = row.reviewInstanceId,
                reviewExpired = row.reviewExpired || row.state == IMPORT_STATE_REVIEW_EXPIRED,
                byteSize = row.byteSize,
            )
        }
    }

    /** When an import last reached a journal, and which journal; null when none has. */
    fun lastDelivery(): ImportLastDelivery? =
        (try { dao.delivery() } catch (_: Exception) { null })?.let { ImportLastDelivery(it.lastDeliveredAt, it.instanceId) }

    fun item(id: String): ImportItemDescriptor? {
        if (isUnsafeId(id)) return null
        val row = try { dao.byId(id) } catch (_: Exception) { null } ?: return null
        val itemDir = root.resolve(row.id)
        val caption = readCaption(itemDir)
        return ImportItemDescriptor(
            id = row.id,
            payloadKind = row.payloadKind,
            payloadPath = itemDir.resolve("payload"),
            displayName = row.displayName,
            mime = row.mime,
            size = row.byteSize,
            caption = caption,
            area = readSidecar(itemDir, IMPORT_AREA_FILE, 96),
            context = readSidecar(itemDir, IMPORT_CONTEXT_FILE, 16 * 1024),
        )
    }

    /** Where [id] is now, for the journal page that handed it over. */
    fun handoffStatus(id: String): ImportHandoffStatus {
        if (isUnsafeId(id)) return ImportHandoffStatus("gone")
        val key = kindKey(root, id)
        synchronized(receipts) { receipts[key] }?.let { return ImportHandoffStatus("delivered", importId = it.importId) }
        val row = try { dao.byId(id) } catch (_: Exception) { null } ?: return ImportHandoffStatus("gone")
        return when {
            row.state == IMPORT_STATE_DELIVERED -> ImportHandoffStatus(
                state = "delivered",
                importId = (row.reviewImportId ?: row.progressTimestamp)?.takeIf(::isValidAsciiImportId),
            )
            row.state == IMPORT_STATE_READY_TO_REVIEW -> ImportHandoffStatus(
                state = "attention",
                importId = row.reviewImportId?.takeIf(::isValidAsciiImportId),
                reason = "saved in your journal. preview and confirm to start.",
                reviewRequired = true,
                reviewExpired = false,
                reasonCode = row.reviewReasonCode ?: "import_preview_required",
                kind = row.reviewKind,
                retryable = false,
            )
            row.state == IMPORT_STATE_REVIEW_EXPIRED -> ImportHandoffStatus(
                state = "attention",
                importId = row.reviewImportId?.takeIf(::isValidAsciiImportId),
                reason = "the waiting copy in your journal expired. this device still holds the file.",
                reviewRequired = false,
                reviewExpired = true,
                reasonCode = row.reviewReasonCode ?: "import_preview_required",
                kind = row.reviewKind,
                retryable = true,
            )
            row.state == IMPORT_STATE_ATTENTION -> {
                val isLocalMissing = row.attentionInstanceId == null && row.attentionJournalVersion == null
                ImportHandoffStatus(
                    state = "attention",
                    importId = row.progressTimestamp?.takeIf(::isValidAsciiImportId),
                    reason = row.attentionError,
                    reviewRequired = false,
                    reasonCode = row.attentionReasonCode,
                    kind = row.attentionKind,
                    retryable = !isLocalMissing && isImportRefusalRetryable(row.attentionReasonCode, row.attentionKind),
                )
            }
            key in inFlight -> ImportHandoffStatus(
                state = "uploading",
                deviceNoun = "phone",
                sizeBytes = row.byteSize,
            )
            else -> ImportHandoffStatus(
                state = "waiting",
                deviceNoun = "phone",
                sizeBytes = row.byteSize,
            )
        }
    }

    private fun readSidecar(itemDir: Path, name: String, maxBytes: Int): String? {
        val path = itemDir.resolve(name)
        return try {
            if (Files.isRegularFile(path, NOFOLLOW_LINKS) && Files.size(path) <= maxBytes) {
                String(Files.readAllBytes(path), Charsets.UTF_8).takeIf { it.isNotEmpty() }
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun readCaption(itemDir: Path): String? {
        val captionPath = itemDir.resolve("caption.txt")
        return try {
            if (Files.isRegularFile(captionPath, NOFOLLOW_LINKS) && Files.size(captionPath) <= 256 * 1024L) {
                String(Files.readAllBytes(captionPath), Charsets.UTF_8).trim().takeIf { it.isNotEmpty() }
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }
}
