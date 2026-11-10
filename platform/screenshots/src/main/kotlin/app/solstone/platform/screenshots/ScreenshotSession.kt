// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.screenshots

import android.content.Context
import android.net.Uri
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import app.solstone.core.model.QueueState
import app.solstone.core.model.SegmentKey
import app.solstone.core.pl.PlHttpClient
import app.solstone.core.screenshots.DocMark
import app.solstone.core.screenshots.HistoryChoice
import app.solstone.core.screenshots.PauseSpan
import app.solstone.core.screenshots.ReceivedEnd
import app.solstone.core.screenshots.ReceivedHash
import app.solstone.core.screenshots.SCREENSHOT_MAX_BYTES
import app.solstone.core.screenshots.ScreenshotBook
import app.solstone.core.screenshots.ShotEnd
import app.solstone.core.screenshots.ShotFate
import app.solstone.core.screenshots.ShotRead
import app.solstone.core.screenshots.StagedShot
import app.solstone.core.screenshots.TakenFrom
import app.solstone.core.screenshots.backfillProgress
import app.solstone.core.screenshots.classifyScreenshot
import app.solstone.core.screenshots.decodeBook
import app.solstone.core.screenshots.encodeBook
import app.solstone.core.screenshots.fileNameFor
import app.solstone.core.screenshots.historyLowerBound
import app.solstone.core.screenshots.mediaTypeFor
import app.solstone.core.screenshots.parseScreenshotsCapability
import app.solstone.core.screenshots.screenshotKeys
import app.solstone.core.screenshots.screenshotWhen
import app.solstone.core.screenshots.shotFate
import app.solstone.core.segment.SealedSegment
import app.solstone.core.segment.SegmentPayload
import app.solstone.core.sources.PayloadRef
import app.solstone.core.sources.SCREENSHOTS_STREAM
import app.solstone.core.spool.FileSpoolWriter
import app.solstone.core.spool.PayloadBytesProvider
import app.solstone.core.spool.SealState
import app.solstone.platform.persistence.room.RoomSealedSegmentSink
import app.solstone.platform.persistence.room.SolstonePersistenceDatabase
import app.solstone.platform.persistence.room.occupiedDirSegments
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

const val SCREENSHOTS_WORK_NAME = "solstone-screenshots-check"

object ScreenshotsRuntimes {
    @Volatile var current: ScreenshotSession? = null
}

interface ScreenshotsWorkerHost {
    fun screenshotsSessionForWork(): ScreenshotSession?
}

class ScreenshotsCheckWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val session = ScreenshotsRuntimes.current
            ?: (applicationContext as? ScreenshotsWorkerHost)?.screenshotsSessionForWork()
            ?: return Result.retry()
        return if (session.runCheckBlocking()) Result.success() else Result.retry()
    }
}

data class ScreenshotUi(
    val accessGranted: Boolean = false,
    val accessLost: Boolean = false,
    val folderName: String = "Screenshots",
    val enabled: Boolean = false,
    val paused: Boolean = false,
    val confirmed: Boolean = false,
    val reconfirm: Boolean = false,
    val history: HistoryChoice = HistoryChoice.LAST_30_DAYS,
    val counting: Boolean = false,
    val countFailed: Boolean = false,
    val previewCount: Int? = null,
    val backfillDone: Int = 0,
    val backfillTotal: Int = 0,
    val lastChecked: Long? = null,
    val waiting: Int = 0,
    val capability: Boolean? = null,
    val refusedSize: Int = 0,
    val refusedUnread: Int = 0,
    val refusedJournal: Int = 0,
)

/**
 * One actor reads the granted folder, keeps the per-journal record, and hands each screenshot
 * to the ordinary spool. The wish stays off until the owner confirms how far back to start.
 */
class ScreenshotSession(
    context: Context,
    private val database: SolstonePersistenceDatabase,
    private val spoolDir: Path,
    private val wishOn: () -> Boolean,
    private val setEnabled: (Boolean) -> Boolean,
    private val ownerStopped: () -> Boolean,
    private val journalId: () -> String?,
    private val onSealed: () -> Unit,
    private val onChanged: () -> Unit,
) {
    private val appContext = context.applicationContext
    private val folder = SafScreenshotFolder(appContext.contentResolver)
    private val actor = ScheduledThreadPoolExecutor(1) { runnable -> Thread(runnable, "screenshots-read") }
    private val bookFile = appContext.filesDir.resolve("screenshots").resolve("book.txt")
    private var book = load()
    private var capability: Boolean? = null
    private var accessLost = false
    private var counting = false
    private var countFailed = false
    private var previewCount: Int? = null
    private var refusedSize = 0
    private var refusedUnread = 0
    private var refusedJournal = 0
    private var retryUnread = false
    @Volatile var ui = ScreenshotUi()
        private set
    @Volatile private var listener: () -> Unit = {}

    init {
        ScreenshotsRuntimes.current = this
        submit { refresh() }
    }

    fun setListener(next: () -> Unit) { listener = next }
    fun hasAccess(): Boolean = book.treeUri != null && !accessLost && grantHeld()
    fun isPaused(): Boolean = ownerStopped() || book.pauses.any { it.endExclusiveMs == null }

    fun onProcessStart() = submit { schedule(); refresh() }
    fun onForeground(value: Boolean) = submit { if (value) checkNow() }
    fun onOwnerStopRequested() = submit { notePause(stopped = true); refresh() }
    fun onPauseChanged() = submit { notePause(ownerStopped()); refresh() }
    fun check() = submit { checkNow() }

    fun choose(uri: Uri) = submit {
        try {
            folder.persist(uri)
            val name = folder.displayName(uri)
            val wasConfirmed = book.confirmed
            book = book.copy(
                treeUri = uri.toString(),
                folderName = name,
                confirmed = false,
                reconfirm = wasConfirmed || book.reconfirm,
                frozen = emptySet(),
                marks = emptyList(),
            )
            accessLost = false
            countFailed = false
            save()
            checkNow()
        } catch (_: SecurityException) {
            accessLost = true
            refresh()
        }
    }

    fun setHistory(choice: HistoryChoice) = submit {
        if (book.confirmed) return@submit
        book = book.copy(history = choice)
        save()
        checkNow()
    }

    fun confirm() = submit {
        if (book.treeUri == null || accessLost) return@submit
        val zone = ZoneId.systemDefault()
        val lower = historyLowerBound(book.history, LocalDate.now(zone), zone)
        val ids = eligibleIds(lower)
        book = book.copy(
            confirmed = true,
            reconfirm = false,
            lowerBoundMs = lower,
            frozen = ids,
            marks = emptyList(),
        )
        save()
        if (!setEnabled(true)) {
            book = book.copy(confirmed = false)
            save()
            refresh()
            return@submit
        }
        checkNow()
    }

    fun turnOff() = submit {
        setEnabled(false)
        book = book.copy(
            confirmed = false,
            reconfirm = true,
            period = book.period + 1,
            pauses = emptyList(),
            frozen = emptySet(),
            marks = emptyList(),
        )
        save()
        refresh()
    }

    fun retryUnread() = submit {
        retryUnread = true
        checkNow()
    }

    fun prepareDelivery(client: PlHttpClient, instanceId: String, pairingCurrent: () -> Boolean): Boolean {
        val ready = actor.submit<Boolean> {
            if (!pairingCurrent() || !wishOn() || isPaused() || !hasAccess()) return@submit false
            val status = client.request(
                "GET",
                "/api/system/status",
                mapOf("Cache-Control" to "no-cache"),
                null,
                maxResponseBytes = 64 * 1024,
            )
            capability = parseScreenshotsCapability(status.status, status.bodyText())
            refresh()
            capability == true && pairingCurrent() && instanceId.isNotEmpty()
        }
        return try {
            ready.get(30, TimeUnit.SECONDS)
        } catch (_: Exception) {
            false
        }
    }

    fun runCheckBlocking(): Boolean {
        val ready = actor.submit<Boolean> {
            try {
                checkNow()
                true
            } catch (_: Exception) {
                false
            }
        }
        return try {
            ready.get(2, TimeUnit.MINUTES)
        } catch (_: Exception) {
            false
        }
    }

    fun close() {
        if (ScreenshotsRuntimes.current === this) ScreenshotsRuntimes.current = null
        actor.shutdownNow()
    }

    private fun checkNow() {
        notePause(ownerStopped())
        val uri = book.treeUri?.let { runCatching { Uri.parse(it) }.getOrNull() } ?: run {
            refresh()
            return
        }
        if (!folder.hasReadGrant(uri)) {
            accessLost = true
            refresh()
            return
        }
        counting = !book.confirmed
        countFailed = false
        refresh()
        val files = try {
            folder.list(uri)
        } catch (_: SecurityException) {
            accessLost = true
            counting = false
            countFailed = true
            refresh()
            return
        }
        accessLost = false
        val zone = ZoneId.systemDefault()
        val journal = journalId()
        val seen = mutableSetOf<String>()
        var preview = 0
        var tooBig = 0
        var unread = 0
        var refusedByJournal = 0
        var stagedNow = false
        observeStaged(journal)
        for (file in files) {
            seen += file.documentId
            val read = readShot(uri, file) ?: continue
            val whenShot = screenshotWhen(read.localDateTime, file.modifiedMs, zone, read.offset)
            val full = read.bytes
            val sha = full?.let { sha256(it) }
            val marked = book.marks.firstOrNull { it.documentId == file.documentId }
            val retrying = retryUnread && marked?.end == ReceivedEnd.UNREADABLE
            val fate = shotFate(
                read = read.classified,
                atMs = whenShot?.epochMs,
                sha256 = sha,
                journalId = journal,
                received = book.received,
                lowerBoundMs = if (book.confirmed) book.lowerBoundMs else historyLowerBound(book.history, LocalDate.now(zone), zone),
                pauses = book.pauses,
                confirmed = book.confirmed,
            )
            when (fate) {
                ShotFate.SKIP -> Unit
                ShotFate.COUNT -> preview++
                ShotFate.ALREADY -> {
                    mark(file.documentId, ReceivedEnd.DELIVERED)
                    if (journal != null && sha != null) {
                        when (book.received.firstOrNull { it.journalId == journal && it.sha256 == sha }?.end) {
                            ReceivedEnd.REFUSED_SIZE -> tooBig++
                            ReceivedEnd.REFUSED_TYPE -> refusedByJournal++
                            else -> Unit
                        }
                    }
                }
                ShotFate.REFUSED_SIZE -> {
                    tooBig++
                    if (!book.confirmed) preview++ else mark(file.documentId, ReceivedEnd.REFUSED_SIZE)
                }
                ShotFate.REFUSED_TYPE -> {
                    if (!book.confirmed) preview++ else mark(file.documentId, ReceivedEnd.REFUSED_TYPE)
                }
                ShotFate.UNREADABLE -> {
                    unread++
                    if (!book.confirmed) preview++ else if (!retrying) mark(file.documentId, ReceivedEnd.UNREADABLE)
                }
                ShotFate.SEND -> {
                    if (full == null || whenShot == null || sha == null) {
                        unread++
                        mark(file.documentId, ReceivedEnd.UNREADABLE)
                    } else if (marked?.end == ReceivedEnd.UNREADABLE && !retrying) {
                        Unit
                    } else if (book.staged.any { it.sha256 == sha && it.documentId == file.documentId }) {
                        Unit
                    } else {
                        val segmentId = seal(full, read.classified as ShotRead.Shot, whenShot.capturedAt, whenShot.takenFrom)
                        if (segmentId != null) {
                            book = book.copy(
                                staged = book.staged + StagedShot(
                                    file.documentId, sha, whenShot.capturedAt, whenShot.takenFrom.name.lowercase(), segmentId, whenShot.epochMs,
                                ),
                            )
                            stagedNow = true
                        }
                    }
                }
            }
        }
        retryUnread = false
        if (book.confirmed) {
            for (id in book.frozen) {
                if (id !in seen && book.marks.none { it.documentId == id }) mark(id, ReceivedEnd.GONE)
            }
        }
        previewCount = preview
        refusedSize = tooBig
        refusedUnread = unread
        refusedJournal = refusedByJournal
        counting = false
        book = book.copy(lastCheckedMs = System.currentTimeMillis())
        save()
        if (stagedNow) onSealed()
        refresh()
    }

    private data class ReadShot(
        val classified: ShotRead,
        val bytes: ByteArray?,
        val localDateTime: String?,
        val offset: String?,
    )

    private fun readShot(uri: Uri, file: FolderFile): ReadShot? {
        val cap = SCREENSHOT_MAX_BYTES.toInt()
        val knownOver = file.size != null && file.size > SCREENSHOT_MAX_BYTES
        val read = try {
            if (knownOver) {
                folder.read(uri, file.documentId, 256 * 1024, 256 * 1024).let { FolderRead(it.bytes, true) }
            } else {
                folder.read(uri, file.documentId, cap, 256 * 1024)
            }
        } catch (_: SecurityException) {
            accessLost = true
            return null
        } catch (_: Exception) {
            return null
        }
        val head = read.bytes ?: return null
        val classified = if (read.oversize) {
            when (val probed = classifyScreenshot(head)) {
                is ShotRead.Shot -> ShotRead.Ended(probed.kind, ShotEnd.REFUSED_SIZE)
                is ShotRead.Ended -> when (probed.end) {
                    ShotEnd.REFUSED_SIZE -> probed
                    ShotEnd.UNREADABLE -> ShotRead.Ended(probed.kind, ShotEnd.REFUSED_SIZE)
                    ShotEnd.REFUSED_TYPE -> ShotRead.NotScreenshot
                }
                ShotRead.NotScreenshot -> ShotRead.NotScreenshot
            }
        } else {
            classifyScreenshot(head)
        }
        if (classified is ShotRead.NotScreenshot) return null
        val shot = classified as? ShotRead.Shot
        return ReadShot(classified, if (read.oversize) null else head, shot?.localDateTime, shot?.offset)
    }

    private fun eligibleIds(lower: Long): Set<String> {
        val uri = book.treeUri?.let(Uri::parse) ?: return emptySet()
        val zone = ZoneId.systemDefault()
        return try {
            folder.list(uri).mapNotNull { file ->
                val shot = readShot(uri, file) ?: return@mapNotNull null
                val at = screenshotWhen(shot.localDateTime, file.modifiedMs, zone, shot.offset)?.epochMs ?: return@mapNotNull null
                val fate = shotFate(shot.classified, at, null, null, emptyList(), lower, book.pauses, confirmed = false)
                if (fate == ShotFate.COUNT || fate == ShotFate.REFUSED_SIZE || fate == ShotFate.REFUSED_TYPE || fate == ShotFate.UNREADABLE) {
                    file.documentId
                } else {
                    null
                }
            }.toSet()
        } catch (_: Exception) {
            emptySet()
        }
    }

    private fun seal(bytes: ByteArray, shot: ShotRead.Shot, capturedAt: String, takenFrom: TakenFrom): String? {
        val keys = screenshotKeys(capturedAt)
        val name = fileNameFor(shot.kind)
        val segment = SealedSegment(
            stream = SCREENSHOTS_STREAM,
            key = SegmentKey(keys.day, keys.segment),
            wireKeys = keys,
            payloads = listOf(
                SegmentPayload(
                    sourceId = SCREENSHOTS_STREAM,
                    ref = PayloadRef(name, mediaTypeFor(shot.kind), bytes.size.toLong(), null),
                    captureStartEpochMs = keys.startEpochMs,
                    captureEndEpochMs = keys.endEpochMs,
                ),
            ),
            gaps = emptyList(),
            metadata = mapOf(
                "screenshotCapturedAt" to capturedAt,
                "screenshotTakenFrom" to takenFrom.name.lowercase(),
            ),
        )
        val dao = database.segmentDao()
        val result = FileSpoolWriter(spoolDir, occupiedLeaves = { day, stream -> dao.occupiedDirSegments(day, stream) }).seal(
            segment,
            object : PayloadBytesProvider {
                override fun open(payload: SegmentPayload) = ByteArrayInputStream(bytes)
            },
        )
        if (result.state != SealState.SEALED) return null
        RoomSealedSegmentSink(dao).persistSealed(segment, result, System.currentTimeMillis())
        val leaf = result.directory?.fileName?.toString() ?: segment.key.segment
        return "$leaf".let { "${keys.day}/$SCREENSHOTS_STREAM/$it" }
    }

    private fun observeStaged(journal: String?) {
        val dao = database.segmentDao()
        val still = mutableListOf<StagedShot>()
        for (staged in book.staged) {
            val row = dao.segmentById(staged.segmentId)
            val home = row?.homeInstanceId ?: journal
            when {
                row == null -> still += staged
                row.state == QueueState.UPLOADED || row.state == QueueState.EVICTED -> {
                    if (home != null) receive(home, staged.sha256, ReceivedEnd.DELIVERED)
                    mark(staged.documentId, ReceivedEnd.DELIVERED)
                }
                row.lastError == "screenshot_invalid" -> {
                    if (home != null) receive(home, staged.sha256, ReceivedEnd.REFUSED_TYPE)
                    mark(staged.documentId, ReceivedEnd.REFUSED_TYPE)
                }
                row.lastError == "multipart_part_too_large" -> {
                    if (home != null) receive(home, staged.sha256, ReceivedEnd.REFUSED_SIZE)
                    mark(staged.documentId, ReceivedEnd.REFUSED_SIZE)
                }
                else -> still += staged
            }
        }
        book = book.copy(staged = still)
    }

    private fun mark(documentId: String, end: ReceivedEnd) {
        if (documentId !in book.frozen) return
        if (book.marks.any { it.documentId == documentId }) return
        book = book.copy(marks = book.marks + DocMark(documentId, end))
    }

    private fun receive(journal: String, sha: String, end: ReceivedEnd) {
        if (book.received.any { it.journalId == journal && it.sha256 == sha }) return
        book = book.copy(received = book.received + ReceivedHash(journal, sha, end))
    }

    private fun notePause(stopped: Boolean) {
        val now = System.currentTimeMillis()
        val open = book.pauses.indexOfFirst { it.endExclusiveMs == null }
        if (stopped && open < 0) {
            book = book.copy(pauses = book.pauses + PauseSpan(now, null))
            save()
        } else if (!stopped && open >= 0) {
            val next = book.pauses.toMutableList()
            next[open] = next[open].copy(endExclusiveMs = now)
            book = book.copy(pauses = next)
            save()
        }
    }

    private fun schedule() {
        if (book.treeUri == null) return
        val work = PeriodicWorkRequest.Builder(
            ScreenshotsCheckWorker::class.java,
            PeriodicWorkRequest.MIN_PERIODIC_INTERVAL_MILLIS,
            TimeUnit.MILLISECONDS,
        ).build()
        WorkManager.getInstance(appContext).enqueueUniquePeriodicWork(
            SCREENSHOTS_WORK_NAME,
            ExistingPeriodicWorkPolicy.UPDATE,
            work,
        )
    }

    private fun refresh() {
        val progress = backfillProgress(book)
        val waiting = book.staged.size
        ui = ScreenshotUi(
            accessGranted = book.treeUri != null && !accessLost,
            accessLost = accessLost && book.treeUri != null,
            folderName = book.folderName,
            enabled = wishOn(),
            paused = isPaused(),
            confirmed = book.confirmed,
            reconfirm = book.reconfirm,
            history = book.history,
            counting = counting,
            countFailed = countFailed,
            previewCount = previewCount,
            backfillDone = progress.first,
            backfillTotal = progress.second,
            lastChecked = book.lastCheckedMs,
            waiting = waiting,
            capability = capability,
            refusedSize = refusedSize,
            refusedUnread = refusedUnread,
            refusedJournal = refusedJournal,
        )
        onChanged()
        listener()
    }

    private fun grantHeld(): Boolean {
        val uri = book.treeUri?.let { runCatching { Uri.parse(it) }.getOrNull() } ?: return false
        return folder.hasReadGrant(uri)
    }

    private fun submit(action: () -> Unit) {
        actor.execute {
            try {
                action()
            } catch (_: Exception) {
                countFailed = true
                counting = false
                refresh()
            }
        }
    }

    private fun load(): ScreenshotBook {
        val file = bookFile
        if (!file.isFile) return ScreenshotBook()
        return runCatching { decodeBook(file.readText()) }.getOrDefault(ScreenshotBook())
    }

    private fun save() {
        val dir = bookFile.parentFile ?: return
        dir.mkdirs()
        val tmp = dir.resolve("book.txt.tmp")
        tmp.writeText(encodeBook(book))
        try {
            Files.move(tmp.toPath(), bookFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), bookFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }
}

private fun sha256(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    return digest.joinToString("") { "%02x".format(it) }
}
