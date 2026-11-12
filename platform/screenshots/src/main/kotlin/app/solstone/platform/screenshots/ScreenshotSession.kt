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
import app.solstone.core.screenshots.KnownFile
import app.solstone.core.screenshots.KnownVerdict
import app.solstone.core.screenshots.PauseSpan
import app.solstone.core.screenshots.ReceivedEnd
import app.solstone.core.screenshots.ReceivedHash
import app.solstone.core.screenshots.SCREENSHOT_HEAD_BYTES
import app.solstone.core.screenshots.SCREENSHOT_MAX_BYTES
import app.solstone.core.screenshots.ScreenshotBook
import app.solstone.core.screenshots.ShotEnd
import app.solstone.core.screenshots.ShotFate
import app.solstone.core.screenshots.ShotRead
import app.solstone.core.screenshots.StagedShot
import app.solstone.core.screenshots.TakenFrom
import app.solstone.core.screenshots.backfillProgress
import app.solstone.core.screenshots.backgroundCheckWanted
import app.solstone.core.screenshots.classifyScreenshot
import app.solstone.core.screenshots.decodeBook
import app.solstone.core.screenshots.decodeKnown
import app.solstone.core.screenshots.encodeBook
import app.solstone.core.screenshots.encodeKnown
import app.solstone.core.screenshots.fileNameFor
import app.solstone.core.screenshots.headRulesOutScreenshot
import app.solstone.core.screenshots.knownVerdict
import app.solstone.core.screenshots.firstSetupAgain
import app.solstone.core.screenshots.historyLowerBound
import app.solstone.core.screenshots.mediaTypeFor
import app.solstone.core.screenshots.parseScreenshotsCapability
import app.solstone.core.screenshots.screenshotKeys
import app.solstone.core.screenshots.screenshotsFolderPath
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
    val folderPath: String = "Screenshots",
    val enabled: Boolean = false,
    val paused: Boolean = false,
    val sourcePaused: Boolean = false,
    val confirmed: Boolean = false,
    val reconfirm: Boolean = false,
    val firstSetUp: Boolean = true,
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
 * One actor reads the granted folder, keeps the record of what the journal paired now has, and
 * hands each screenshot to the ordinary spool. The wish stays off until the owner confirms how far
 * back to start.
 */
class ScreenshotSession(
    context: Context,
    private val database: SolstonePersistenceDatabase,
    private val spoolDir: Path,
    private val wishOn: () -> Boolean,
    private val setEnabled: (Boolean) -> Boolean,
    private val ownerStopped: () -> Boolean,
    private val onSealed: () -> Unit,
    private val onChanged: () -> Unit,
) {
    private val appContext = context.applicationContext
    private val folder = SafScreenshotFolder(appContext.contentResolver)
    private val actor = ScheduledThreadPoolExecutor(1) { runnable -> Thread(runnable, "screenshots-read") }
    private val bookFile = appContext.filesDir.resolve("screenshots").resolve("book.txt")
    private var book = load()
    private val knownFile = appContext.filesDir.resolve("screenshots").resolve("known.txt")
    private var known: MutableMap<String, KnownFile> = loadKnown()
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
    fun isPaused(): Boolean = pausedNow() || book.pauses.any { it.endExclusiveMs == null }
    private fun pausedNow(): Boolean = ownerStopped() || book.sourcePaused

    fun onProcessStart() = submit { schedule(); refresh() }
    fun onForeground(value: Boolean) = submit { if (value) checkNow() }
    fun onOwnerStopRequested() = submit { notePause(stopped = true); refresh() }
    fun onPauseChanged() = submit { notePause(pausedNow()); refresh() }

    /** The owner's own pause of this source. A screenshot taken while it holds never comes in. */
    fun pause() = submit {
        if (!book.confirmed) return@submit
        book = book.copy(sourcePaused = true)
        notePause(true)
        save()
        refresh()
    }

    /** Resume starts from now: the paused window stays closed to every later folder read. */
    fun resume() = submit {
        book = book.copy(sourcePaused = false)
        notePause(pausedNow())
        save()
        checkNow()
    }
    fun check() = submit { checkNow() }

    /** Reads which staged screenshots the journal now holds, without reading the folder. */
    fun observeDelivery() = submit {
        if (book.staged.isEmpty()) return@submit
        observeStaged()
        save()
        refresh()
    }

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
            if (wasConfirmed) {
                // A new folder asks how far back again, so the source is `ready to set up` until the
                // confirm, as after turning it off: never `on` over a chooser nobody has answered.
                setEnabled(false)
                schedule(on = false)
                book = book.copy(period = book.period + 1, pauses = emptyList(), sourcePaused = false)
            }
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
        schedule(backgroundCheckWanted(book, wishOn = true))
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
            sourcePaused = false,
        )
        save()
        schedule(on = false)
        refresh()
    }

    /**
     * A new pairing: the page asks how far back again, with a fresh count, and nothing comes in until
     * the owner confirms. Runs before the pairing is confirmed, so it is saved before anything can go
     * to the new journal. Returns once saved, or false if that did not happen in time.
     */
    fun restartFirstSetup(): Boolean {
        val saved = actor.submit<Unit> {
            // Anything that reached a journal so far reached the last one: settle it before the
            // record is cleared, so it is not taken for something the new journal has.
            observeStaged()
            setEnabled(false)
            schedule(on = false)
            book = firstSetupAgain(book)
            known.values.removeAll { it.verdict == KnownVerdict.InJournal }
            saveKnown()
            save()
            capability = null
            previewCount = null
            refusedSize = 0
            refusedUnread = 0
            refusedJournal = 0
            refresh()
        }
        val done = try {
            saved.get(30, TimeUnit.SECONDS)
            true
        } catch (_: Exception) {
            false
        }
        // The fresh count for the page.
        submit { checkNow() }
        return done
    }

    fun retryUnread() = submit {
        retryUnread = true
        checkNow()
    }

    fun prepareDelivery(client: PlHttpClient, pairingCurrent: () -> Boolean): Boolean {
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
            capability == true && pairingCurrent()
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
                if (backgroundCheckWanted(book, wishOn())) checkNow()
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
        notePause(pausedNow())
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
            if (!folder.reachable(uri)) {
                accessLost = true
                counting = false
                refresh()
                return
            }
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
        val seen = mutableSetOf<String>()
        var preview = 0
        var tooBig = 0
        var unread = 0
        var refusedByJournal = 0
        var stagedNow = false
        observeStaged()
        for (file in files) {
            seen += file.documentId
            when (knownVerdict(known[file.documentId], file.size, file.modifiedMs)) {
                KnownVerdict.NotScreenshot -> continue
                KnownVerdict.InJournal -> {
                    mark(file.documentId, ReceivedEnd.DELIVERED)
                    continue
                }
                null -> Unit
            }
            val read = when (val probe = readShot(uri, file)) {
                null -> continue
                NOT_A_SCREENSHOT -> {
                    remember(file, KnownVerdict.NotScreenshot)
                    continue
                }
                else -> probe
            }
            val whenShot = screenshotWhen(read.localDateTime, file.modifiedMs, zone, read.offset)
            val full = read.bytes
            val sha = full?.let { sha256(it) }
            val marked = book.marks.firstOrNull { it.documentId == file.documentId }
            val retrying = retryUnread && marked?.end == ReceivedEnd.UNREADABLE
            val fate = shotFate(
                read = read.classified,
                atMs = whenShot?.epochMs,
                sha256 = sha,
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
                    if (book.received.any { it.sha256 == sha && it.end == ReceivedEnd.DELIVERED }) {
                        remember(file, KnownVerdict.InJournal)
                    }
                    if (sha != null) {
                        when (book.received.firstOrNull { it.sha256 == sha }?.end) {
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
        known.keys.retainAll(seen)
        saveKnown()
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

    /** Read and proven not to be a screenshot, as against a read that failed (null). */
    private val NOT_A_SCREENSHOT = ReadShot(ShotRead.NotScreenshot, null, null, null)

    private fun readShot(uri: Uri, file: FolderFile): ReadShot? {
        val cap = SCREENSHOT_MAX_BYTES.toInt()
        val knownOver = file.size != null && file.size > SCREENSHOT_MAX_BYTES
        try {
            // A photo or video is ruled out by its first bytes, so it is never read whole.
            val first = folder.read(uri, file.documentId, SCREENSHOT_HEAD_BYTES, SCREENSHOT_HEAD_BYTES)
            val head = first.bytes ?: return null
            if (!first.oversize) return readShotFrom(classifyScreenshot(head), head, oversize = false)
            if (headRulesOutScreenshot(head)) return NOT_A_SCREENSHOT
        } catch (_: SecurityException) {
            accessLost = true
            return null
        } catch (_: Exception) {
            return null
        }
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
        return readShotFrom(classified, head, read.oversize)
    }

    private fun readShotFrom(classified: ShotRead, bytes: ByteArray, oversize: Boolean): ReadShot? {
        if (classified is ShotRead.NotScreenshot) return NOT_A_SCREENSHOT
        val shot = classified as? ShotRead.Shot
        return ReadShot(classified, if (oversize) null else bytes, shot?.localDateTime, shot?.offset)
    }

    private fun eligibleIds(lower: Long): Set<String> {
        val uri = book.treeUri?.let(Uri::parse) ?: return emptySet()
        val zone = ZoneId.systemDefault()
        return try {
            folder.list(uri).mapNotNull { file ->
                if (knownVerdict(known[file.documentId], file.size, file.modifiedMs) == KnownVerdict.NotScreenshot) return@mapNotNull null
                val shot = readShot(uri, file)?.takeIf { it !== NOT_A_SCREENSHOT } ?: return@mapNotNull null
                val at = screenshotWhen(shot.localDateTime, file.modifiedMs, zone, shot.offset)?.epochMs ?: return@mapNotNull null
                val fate = shotFate(shot.classified, at, null, emptyList(), lower, book.pauses, confirmed = false)
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

    private fun observeStaged() {
        val dao = database.segmentDao()
        val still = mutableListOf<StagedShot>()
        for (staged in book.staged) {
            val row = dao.segmentById(staged.segmentId)
            when {
                row == null -> still += staged
                row.state == QueueState.UPLOADED || row.state == QueueState.EVICTED -> {
                    receive(staged.sha256, ReceivedEnd.DELIVERED)
                    mark(staged.documentId, ReceivedEnd.DELIVERED)
                }
                row.lastError == "screenshot_invalid" -> {
                    receive(staged.sha256, ReceivedEnd.REFUSED_TYPE)
                    mark(staged.documentId, ReceivedEnd.REFUSED_TYPE)
                }
                row.lastError == "multipart_part_too_large" -> {
                    receive(staged.sha256, ReceivedEnd.REFUSED_SIZE)
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

    private fun receive(sha: String, end: ReceivedEnd) {
        if (book.received.any { it.sha256 == sha }) return
        book = book.copy(received = book.received + ReceivedHash(sha, end))
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

    /** Keeps the periodic check enqueued exactly while the source is on. */
    private fun schedule(on: Boolean = backgroundCheckWanted(book, wishOn())) {
        val manager = WorkManager.getInstance(appContext)
        if (!on) {
            manager.cancelUniqueWork(SCREENSHOTS_WORK_NAME)
            return
        }
        val work = PeriodicWorkRequest.Builder(
            ScreenshotsCheckWorker::class.java,
            PeriodicWorkRequest.MIN_PERIODIC_INTERVAL_MILLIS,
            TimeUnit.MILLISECONDS,
        ).build()
        manager.enqueueUniquePeriodicWork(
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
            folderPath = screenshotsFolderPath(
                book.treeUri?.let { runCatching { Uri.parse(it) }.getOrNull() }
                    ?.takeIf { it.authority == "com.android.externalstorage.documents" }
                    ?.let { runCatching { android.provider.DocumentsContract.getTreeDocumentId(it) }.getOrNull() },
                book.folderName,
            ),
            enabled = wishOn(),
            paused = isPaused(),
            sourcePaused = book.sourcePaused,
            confirmed = book.confirmed,
            reconfirm = book.reconfirm,
            firstSetUp = book.period == 1 && !book.confirmed,
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

    private fun remember(file: FolderFile, verdict: KnownVerdict) {
        val size = file.size ?: return
        val modified = file.modifiedMs ?: return
        known[file.documentId] = KnownFile(file.documentId, size, modified, verdict)
    }

    private fun loadKnown(): MutableMap<String, KnownFile> =
        if (!knownFile.isFile) linkedMapOf() else runCatching { decodeKnown(knownFile.readText()).toMutableMap() }.getOrDefault(linkedMapOf())

    private fun saveKnown() {
        val dir = knownFile.parentFile ?: return
        dir.mkdirs()
        val tmp = dir.resolve("known.txt.tmp")
        tmp.writeText(encodeKnown(known.values))
        try {
            Files.move(tmp.toPath(), knownFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), knownFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
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
