// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.screenshots

import android.content.Context
import android.net.Uri
import android.os.CancellationSignal
import androidx.work.WorkerParameters
import app.solstone.core.model.SegmentKey
import app.solstone.core.pl.PlHttpClient
import app.solstone.core.pl.fetchJournalCapability
import app.solstone.core.screenshots.DocMark
import app.solstone.core.screenshots.HistoryChoice
import app.solstone.core.screenshots.KnownFile
import app.solstone.core.screenshots.KnownVerdict
import app.solstone.core.screenshots.PauseSpan
import app.solstone.core.screenshots.ReceivedEnd
import app.solstone.core.screenshots.ReceivedHash
import app.solstone.core.screenshots.SCREENSHOT_HEAD_BYTES
import app.solstone.core.screenshots.SCREENSHOT_MAX_BYTES
import app.solstone.core.screenshots.SavedTextFile
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
import app.solstone.core.screenshots.screenshotKeys
import app.solstone.core.screenshots.screenshotsFolderPath
import app.solstone.core.screenshots.screenshotWhen
import app.solstone.core.screenshots.shotFate
import app.solstone.core.segment.SealedSegment
import app.solstone.core.segment.SegmentPayload
import app.solstone.core.sources.PayloadRef
import app.solstone.core.sources.SCREENSHOTS_STREAM
import app.solstone.platform.foldersync.FolderCheckWorker
import app.solstone.platform.foldersync.FolderChecks
import app.solstone.platform.foldersync.FolderDelivery
import app.solstone.platform.foldersync.FolderFile
import app.solstone.platform.foldersync.FolderRead
import app.solstone.platform.foldersync.FolderRuntime
import app.solstone.platform.foldersync.SafFolder
import app.solstone.platform.foldersync.journalHasSegment
import app.solstone.platform.persistence.room.SolstonePersistenceDatabase
import java.nio.file.Path
import java.security.MessageDigest
import java.time.LocalDate
import java.time.ZoneId

const val SCREENSHOTS_WORK_NAME = "solstone-screenshots-check"

object ScreenshotsRuntimes : FolderRuntime<ScreenshotSession>()

interface ScreenshotsWorkerHost {
    fun screenshotsSessionForWork(): ScreenshotSession?
}

// Keep this class name for WorkManager requests persisted by older installs.
class ScreenshotsCheckWorker(context: Context, params: WorkerParameters) : FolderCheckWorker(context, params) {
    override fun runCheck(stopped: () -> Boolean): Boolean =
        (ScreenshotsRuntimes.current ?: (applicationContext as? ScreenshotsWorkerHost)?.screenshotsSessionForWork())
            ?.runCheckBlocking(stopped) == true
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
class ScreenshotSession internal constructor(
    context: Context,
    database: SolstonePersistenceDatabase,
    spoolDir: Path,
    private val wishOn: () -> Boolean,
    private val setEnabled: (Boolean) -> Boolean,
    private val ownerStopped: () -> Boolean,
    private val onSealed: () -> Unit,
    private val onChanged: () -> Unit,
    private val folder: SafFolder,
) {
    constructor(
        context: Context,
        database: SolstonePersistenceDatabase,
        spoolDir: Path,
        wishOn: () -> Boolean,
        setEnabled: (Boolean) -> Boolean,
        ownerStopped: () -> Boolean,
        onSealed: () -> Unit,
        onChanged: () -> Unit,
    ) : this(
        context, database, spoolDir, wishOn, setEnabled, ownerStopped, onSealed, onChanged,
        SafFolder(context.applicationContext.contentResolver),
    )

    private val appContext = context.applicationContext
    private val delivery = FolderDelivery(spoolDir, database.segmentDao())
    private val bookFile = SavedTextFile(appContext.filesDir.resolve("screenshots").resolve("book.txt"))
    // The check time has its own record, so a check that finds nothing new rewrites neither the book nor the known list.
    private val checkedFile = SavedTextFile(appContext.filesDir.resolve("screenshots").resolve("checked.txt"))
    private var book = load()
    private val knownFile = SavedTextFile(appContext.filesDir.resolve("screenshots").resolve("known.txt"))
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

    private val checks = FolderChecks(
        appContext, SCREENSHOTS_WORK_NAME, ScreenshotsCheckWorker::class.java, "screenshots-read",
        check = ::checkNow, onFailure = ::recordFailure, backgroundWanted = { backgroundCheckWanted(book, wishOn()) },
        workerTimeoutMs = 120_000,
    )

    init {
        ScreenshotsRuntimes.register(this)
        checks.submit { refresh() }
    }

    fun setListener(next: () -> Unit) { listener = next }
    fun hasAccess(): Boolean = book.treeUri != null && !accessLost && grantHeld()
    fun isPaused(): Boolean = pausedNow() || book.pauses.any { it.endExclusiveMs == null }
    private fun pausedNow(): Boolean = ownerStopped() || book.sourcePaused

    fun onProcessStart() = checks.submit { checks.scheduleBackground(); refresh() }
    fun onForeground(value: Boolean) = checks.onForeground(value)
    fun onOwnerStopRequested() = checks.submit { notePause(stopped = true); refresh() }
    fun onPauseChanged() = checks.submit { notePause(pausedNow()); refresh() }

    /** The owner's own pause of this source. A screenshot taken while it holds never comes in. */
    fun pause() = checks.submit {
        if (!book.confirmed) return@submit
        book = book.copy(sourcePaused = true)
        notePause(true)
        save()
        refresh()
    }

    /** Resume starts from now: the paused window stays closed to every later folder read. */
    fun resume() = checks.submit {
        book = book.copy(sourcePaused = false)
        notePause(pausedNow())
        save()
        checks.checkNow()
    }
    fun check() = checks.submit { checks.checkNow() }

    /** Reads which staged screenshots the journal now holds, without reading the folder. */
    fun observeDelivery() = checks.submit {
        if (book.staged.isEmpty()) return@submit
        observeStaged()
        save()
        refresh()
    }

    fun choose(uri: Uri) = checks.submit {
        try {
            folder.persist(uri)
            val name = folder.root(uri)?.name?.takeIf { it.isNotBlank() } ?: "Screenshots"
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
                checks.stopScheduling()
                book = book.copy(period = book.period + 1, pauses = emptyList(), sourcePaused = false)
            }
            accessLost = false
            countFailed = false
            save()
            checks.checkNow()
        } catch (_: SecurityException) {
            accessLost = true
            refresh()
        }
    }

    fun setHistory(choice: HistoryChoice) = checks.submit {
        if (book.confirmed) return@submit
        book = book.copy(history = choice)
        save()
        checks.checkNow()
    }

    fun confirm() = checks.submit {
        val uri = book.treeUri?.let(Uri::parse) ?: return@submit
        if (accessLost || countFailed || book.confirmed) return@submit
        val zone = ZoneId.systemDefault()
        val lower = historyLowerBound(book.history, LocalDate.now(zone), zone)
        if (!folder.hasReadGrant(uri) || folder.root(uri)?.directory != true) {
            accessLost = true
            refresh()
            return@submit
        }
        val ids = eligibleIds(uri, lower)
        if (accessLost) {
            refresh()
            return@submit
        }
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
        checks.scheduleBackground(backgroundCheckWanted(book, wishOn = true))
        checks.checkNow()
    }

    fun turnOff() = checks.submit {
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
        checks.stopScheduling()
        refresh()
    }

    /**
     * A new pairing: the page asks how far back again, with a fresh count, and nothing comes in until
     * the owner confirms. Runs before the pairing is confirmed, so it is saved before anything can go
     * to the new journal. Returns once saved, or false if that did not happen in time.
     */
    fun restartFirstSetup(): Boolean {
        return checks.restartFirstSetup {
            // Anything that reached a journal so far reached the last one: settle it before the
            // record is cleared, so it is not taken for something the new journal has.
            observeStaged()
            setEnabled(false)
            checks.stopScheduling()
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
    }

    fun retryUnread() = checks.submit {
        retryUnread = true
        checks.checkNow()
    }

    fun prepareDelivery(client: PlHttpClient, pairingCurrent: () -> Boolean, fingerprint: String = ""): Boolean = checks.blocking(30) {
        if (!pairingCurrent() || !wishOn() || isPaused() || !hasAccess()) return@blocking false
        capability = fetchJournalCapability(client, SCREENSHOTS_STREAM, fingerprint)
        refresh()
        capability == true && pairingCurrent()
    } ?: false

    fun runCheckBlocking(stopped: () -> Boolean = { false }): Boolean = checks.runFromWorker(stopped)

    fun close() {
        ScreenshotsRuntimes.unregister(this)
        checks.close()
    }

    private fun checkNow(signal: CancellationSignal) {
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
            if (folder.root(uri, signal)?.directory != true) {
                accessLost = true
                counting = false
                refresh()
                return
            }
            folder.walk(uri, signal) { document, _ -> document.file }
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
        try {
            for (file in files) {
                signal.throwIfCanceled()
                seen += file.documentId
                when (knownVerdict(known[file.documentId], file.size, file.modifiedMs)) {
                    KnownVerdict.NotScreenshot -> continue
                    KnownVerdict.InJournal -> {
                        mark(file.documentId, ReceivedEnd.DELIVERED)
                        continue
                    }
                    null -> Unit
                }
                val read = when (val probe = readShot(uri, file, signal)) {
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
            signal.throwIfCanceled()
        } catch (error: Exception) {
            // A cancelled check can already have handed complete items to the spool.
            if (stagedNow) {
                save()
                onSealed()
            }
            throw error
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
        val checkedAt = System.currentTimeMillis()
        book = book.copy(lastCheckedMs = checkedAt)
        checkedFile.replace(checkedAt.toString())
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

    private fun readShot(uri: Uri, file: FolderFile, signal: CancellationSignal): ReadShot? {
        val read = try {
            // A photo or video is ruled out by its first bytes, so it is never read whole.
            val first = folder.read(uri, file, signal, SCREENSHOT_HEAD_BYTES, SCREENSHOT_HEAD_BYTES)
            when (first) {
                FolderRead.Unavailable -> return null
                is FolderRead.Stable -> return readShotFrom(classifyScreenshot(first.bytes), first.bytes, oversize = false)
                is FolderRead.TooLarge -> {
                    if (headRulesOutScreenshot(first.head)) return NOT_A_SCREENSHOT
                    if ((file.size ?: 0L) > SCREENSHOT_MAX_BYTES) first
                    else folder.read(uri, file, signal, SCREENSHOT_MAX_BYTES.toInt(), SCREENSHOT_HEAD_BYTES)
                }
            }
        } catch (cancelled: android.os.OperationCanceledException) {
            throw cancelled
        } catch (_: SecurityException) {
            accessLost = true
            return null
        } catch (_: Exception) {
            return null
        }
        val head = when (read) {
            FolderRead.Unavailable -> return null
            is FolderRead.Stable -> read.bytes
            is FolderRead.TooLarge -> read.head
        }
        val oversize = read is FolderRead.TooLarge
        val classified = if (oversize) {
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
        return readShotFrom(classified, head, oversize)
    }

    private fun readShotFrom(classified: ShotRead, bytes: ByteArray, oversize: Boolean): ReadShot? {
        if (classified is ShotRead.NotScreenshot) return NOT_A_SCREENSHOT
        val shot = classified as? ShotRead.Shot
        return ReadShot(classified, if (oversize) null else bytes, shot?.localDateTime, shot?.offset)
    }

    private fun eligibleIds(uri: Uri, lower: Long): Set<String> {
        val zone = ZoneId.systemDefault()
        val signal = CancellationSignal()
        return folder.walk(uri, signal) { document, _ -> document.file }.mapNotNull { file ->
            if (knownVerdict(known[file.documentId], file.size, file.modifiedMs) == KnownVerdict.NotScreenshot) return@mapNotNull null
            val shot = readShot(uri, file, signal)?.takeIf { it !== NOT_A_SCREENSHOT } ?: return@mapNotNull null
            val at = screenshotWhen(shot.localDateTime, file.modifiedMs, zone, shot.offset)?.epochMs ?: return@mapNotNull null
            val fate = shotFate(shot.classified, at, null, emptyList(), lower, book.pauses, confirmed = false)
            when (fate) {
                ShotFate.COUNT, ShotFate.REFUSED_SIZE, ShotFate.REFUSED_TYPE, ShotFate.UNREADABLE -> file.documentId
                else -> null
            }
        }.toSet()
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
        return delivery.seal(segment, bytes)
    }

    private fun observeStaged() {
        val still = mutableListOf<StagedShot>()
        for (staged in book.staged) {
            val row = delivery.receipt(staged.segmentId)
            when {
                row == null -> still += staged
                row.journalHasSegment() -> {
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

    private fun recordFailure(error: Exception) {
        if (error is SecurityException) accessLost = true
        countFailed = error !is android.os.OperationCanceledException
        if (!book.confirmed && countFailed) previewCount = null
        counting = false
        refresh()
    }

    private fun remember(file: FolderFile, verdict: KnownVerdict) {
        val size = file.size ?: return
        val modified = file.modifiedMs ?: return
        known[file.documentId] = KnownFile(file.documentId, size, modified, verdict)
    }

    private fun loadKnown(): MutableMap<String, KnownFile> =
        knownFile.read()?.let { text -> runCatching { decodeKnown(text).toMutableMap() }.getOrNull() } ?: linkedMapOf()

    private fun saveKnown() {
        knownFile.replace(encodeKnown(known.values))
    }

    private fun load(): ScreenshotBook {
        // A book saved before the check time had its own record carries it inline.
        val saved = bookFile.read()?.let { text -> runCatching { decodeBook(text) }.getOrNull() } ?: ScreenshotBook()
        val checked = checkedFile.read()?.trim()?.toLongOrNull()
        return saved.copy(lastCheckedMs = listOfNotNull(saved.lastCheckedMs, checked).maxOrNull())
    }

    private fun save() {
        bookFile.replace(encodeBook(book))
    }
}

private fun sha256(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    return digest.joinToString("") { "%02x".format(it) }
}
