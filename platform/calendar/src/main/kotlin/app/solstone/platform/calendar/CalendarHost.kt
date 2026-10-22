// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.calendar

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.CalendarContract
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import app.solstone.core.calendar.CalendarFileBytes
import app.solstone.core.calendar.CalendarIntentRecord
import app.solstone.core.calendar.calendarWindow
import app.solstone.core.model.SegmentKey
import app.solstone.core.segment.SealedSegment
import app.solstone.core.segment.SegmentPayload
import app.solstone.core.sources.CALENDAR_STREAM
import app.solstone.core.sources.PayloadRef
import app.solstone.core.spool.FileSpoolWriter
import app.solstone.core.spool.PayloadBytesProvider
import app.solstone.core.spool.SealState
import app.solstone.platform.persistence.room.RoomSealedSegmentSink
import app.solstone.platform.persistence.room.SolstonePersistenceDatabase
import app.solstone.platform.persistence.room.occupiedDirSegments
import java.io.ByteArrayInputStream
import java.nio.file.Path
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

const val CALENDAR_WORK_NAME = "solstone-calendar-check"

object CalendarRuntimes {
    @Volatile var current: CalendarSession? = null
}

interface CalendarWorkerHost {
    fun calendarSessionForWork(): CalendarSession?
}

class CalendarCheckWorker(
    context: Context,
    params: WorkerParameters,
) : Worker(context, params) {
    override fun doWork(): Result {
        val session = CalendarRuntimes.current ?: (applicationContext as? CalendarWorkerHost)?.calendarSessionForWork()
            ?: return Result.retry()
        return if (session.runCheckBlocking { isStopped }) Result.success() else Result.retry()
    }
}

fun newCalendarCheckRequest(): PeriodicWorkRequest =
    PeriodicWorkRequest.Builder(
        CalendarCheckWorker::class.java,
        PeriodicWorkRequest.MIN_PERIODIC_INTERVAL_MILLIS,
        TimeUnit.MILLISECONDS,
    ).build()

fun enqueueCalendarWork(context: Context) {
    WorkManager.getInstance(context).enqueueUniquePeriodicWork(
        CALENDAR_WORK_NAME,
        ExistingPeriodicWorkPolicy.UPDATE,
        newCalendarCheckRequest(),
    )
}

fun cancelCalendarWork(context: Context) {
    WorkManager.getInstance(context).cancelUniqueWork(CALENDAR_WORK_NAME)
}

/**
 * One session for the phone process: one thread, the content observer, and the periodic check.
 * Scheduling stays off until the source is confirmed and on.
 */
class CalendarSession(
    context: Context,
    database: SolstonePersistenceDatabase,
    spoolDir: Path,
    private val permission: () -> Boolean,
    private val wishOn: () -> Boolean,
    private val setEnabled: (Boolean) -> Boolean,
    private val needsAttention: () -> Boolean,
    private val onSealed: () -> Unit,
    private val onChanged: () -> Unit,
    readerOverride: CalendarReader? = null,
) {
    private val appContext = context.applicationContext
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "calendar-read")
    }
    @Volatile private var paneListener: () -> Unit = {}
    private lateinit var watch: CalendarWatch
    private val runtime = CalendarRuntime(
        ledger = RoomCalendarLedger(database.calendarDao(), CalendarPayloads(spoolDir.parent.resolve("calendar-pending"))),
        reader = readerOverride ?: CalendarReader(ContentResolverCalendarQuery(appContext.contentResolver)),
        sealer = { file -> sealCalendarFile(database, spoolDir, file) },
        segmentExists = { intent -> calendarSegmentExists(database, intent) },
        permission = permission,
        wishOn = wishOn,
        setEnabled = setEnabled,
        schedule = { run: Boolean ->
            if (run) {
                enqueueCalendarWork(appContext)
                watch.start()
            } else {
                cancelCalendarWork(appContext)
                watch.stop()
            }
        },
        needsAttention = needsAttention,
        onSealed = onSealed,
    )

    val ui: CalendarUiState get() = runtime.ui

    init {
        watch = CalendarWatch(appContext) { submit { runtime.runCheck() } }
        runtime.onChanged = { onChanged(); paneListener() }
        CalendarRuntimes.current = this
    }

    fun setOnChanged(listener: () -> Unit) {
        paneListener = listener
    }

    fun refreshPreview() {
        val t = runtime.currentTicket()
        submit { if (runtime.currentTicket() == t) {
            runtime.refreshPreview()
            if (!runtime.ui.resume && runtime.currentTicket() == t) runtime.count(t)
        } }
    }

    fun setHistory(kind: String) = setHistory(app.solstone.core.calendar.HistoryKind.valueOf(kind))
    fun setHistory(kind: app.solstone.core.calendar.HistoryKind) {
        val t = runtime.invalidate()
        submit { runtime.setHistory(kind); if (!runtime.ui.resume && runtime.currentTicket() == t) runtime.count(t) }
    }
    fun setSelected(id: Long, selected: Boolean) {
        val t = runtime.invalidate()
        submit { runtime.setSelected(id, selected); if (!runtime.ui.resume && runtime.currentTicket() == t) runtime.count(t) }
    }
    fun count() { val t = runtime.invalidate(); submit { if (runtime.currentTicket() == t) runtime.count(t) } }
    fun primary() { val t = runtime.invalidate(); submit { if (runtime.currentTicket() == t) runtime.primary(t) } }
    fun abandon() { runtime.invalidate(); submit { runtime.abandon() } }
    fun turnOff() { runtime.invalidate(); submit { runtime.turnOff() } }
    fun onPermissionResult(granted: Boolean) {
        val t = runtime.invalidate()
        submit { runtime.onPermissionResult(granted); if (granted && !runtime.ui.resume && runtime.currentTicket() == t) runtime.count(t) }
    }
    fun onProcessStart() = submit { runtime.onProcessStart() }
    fun onOwnerStopped() { runtime.invalidate(); submit { runtime.suspendChecks() } }
    fun onResume() {
        val t = runtime.currentTicket()
        submit { if (runtime.currentTicket() == t) { runtime.onProcessStart(); runtime.runCheck(t) } }
    }
    fun runCheck() { val t = runtime.currentTicket(); submit { runtime.runCheck(t) } }

    fun runCheckBlocking(stopped: () -> Boolean = { false }): Boolean {
        val t = runtime.currentTicket()
        val future = executor.submit<Boolean> {
            try { runtime.runCheck(t) } catch (error: Exception) { failure(error); false }
        }
        while (true) {
            if (stopped() || Thread.currentThread().isInterrupted) {
                runtime.invalidate(); future.cancel(true); return false
            }
            try { return future.get(500, TimeUnit.MILLISECONDS) }
            catch (_: java.util.concurrent.TimeoutException) { /* WorkManager owns the actual operation. */ }
            catch (_: java.util.concurrent.ExecutionException) { return false }
            catch (_: InterruptedException) { runtime.invalidate(); future.cancel(true); Thread.currentThread().interrupt(); return false }
        }
    }

    fun close() {
        watch.stop()
        executor.shutdownNow()
        if (CalendarRuntimes.current === this) CalendarRuntimes.current = null
    }

    private fun submit(block: () -> Unit) {
        executor.execute {
            try {
                block()
            } catch (error: Exception) { failure(error) }
        }
    }

    private fun failure(error: Exception) {
        android.util.Log.w("CalendarSource", "check failed: ${error.javaClass.simpleName}")
        runCatching { runtime.recordFailure() }
    }
}

internal fun sealCalendarFile(database: SolstonePersistenceDatabase, spoolDir: Path, file: CalendarFileBytes): Boolean {
    val line = file.bytes.toString(Charsets.UTF_8).lineSequence().firstOrNull { it.isNotBlank() } ?: return false
    val changedAt = ((app.solstone.core.pl.parseJson(line) as? Map<*, *>)?.get("changed_at") as? String) ?: return false
    val keys = calendarWindow(changedAt)
    val segment = SealedSegment(
        stream = CALENDAR_STREAM,
        key = SegmentKey(file.day, file.segment),
        wireKeys = keys,
        payloads = listOf(
            SegmentPayload(
                sourceId = CALENDAR_STREAM,
                ref = PayloadRef(file.name, file.mediaType, file.bytes.size.toLong(), file.name.removePrefix("calendar_").removeSuffix(".jsonl")),
                captureStartEpochMs = keys.startEpochMs,
                captureEndEpochMs = keys.endEpochMs,
            ),
        ),
        gaps = emptyList(),
    )
    val writer = FileSpoolWriter(
        baseDir = spoolDir,
        occupiedLeaves = { day, stream -> database.segmentDao().occupiedDirSegments(day, stream) },
    )
    val result = writer.seal(
        segment,
        object : PayloadBytesProvider {
            override fun open(payload: SegmentPayload) = ByteArrayInputStream(file.bytes)
        },
    )
    if (result.state != SealState.SEALED) return false
    RoomSealedSegmentSink(database.segmentDao()).persistSealed(segment, result, System.currentTimeMillis())
    return true
}

internal fun calendarSegmentExists(database: SolstonePersistenceDatabase, intent: CalendarIntentRecord): Boolean {
    val name = "calendar_${intent.id}.jsonl"
    return database.segmentDao().segmentsByDay(intent.day).any { row ->
        row.stream == CALENDAR_STREAM && database.segmentDao().filesBySegmentId(row.id).any { it.name == name }
    }
}

private class CalendarWatch(
    context: Context,
    private val onChange: () -> Unit,
) {
    private val resolver = context.contentResolver
    private val handler = Handler(Looper.getMainLooper())
    private val fire = Runnable { onChange() }
    private var registered = false
    private val observer = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) = debounce()
        override fun onChange(selfChange: Boolean, uri: Uri?) = debounce()
        private fun debounce() {
            handler.removeCallbacks(fire)
            handler.postDelayed(fire, 3_000)
        }
    }

    fun start() {
        if (registered) return
        resolver.registerContentObserver(CalendarContract.CONTENT_URI, true, observer)
        registered = true
    }

    fun stop() {
        if (!registered) return
        handler.removeCallbacks(fire)
        resolver.unregisterContentObserver(observer)
        registered = false
    }
}
