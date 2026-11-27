// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.StatFs
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import app.solstone.core.pl.browser.BrowserResponseSink
import app.solstone.core.pl.browser.JournalBrowserIdentityException
import app.solstone.core.pl.browser.JournalBrowserUpstream
import app.solstone.core.pl.parseJson
import app.solstone.core.pl.toJson
import app.solstone.platform.work.JournalBrowserUpstreamAdapter
import app.solstone.platform.work.SyncDrainGate
import app.solstone.platform.work.syncStores
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.math.BigDecimal
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Destination document owned by an active export download (SAF `Uri` in production, file/memory in tests). */
internal interface ExportDestination {
    val uriString: String
    val displayName: String
    val supportsRandomWrite: Boolean
    fun currentSizeBytes(): Long
    fun openWriterAt(offset: Long, truncate: Boolean): OutputStream
    fun computeSha256OrNull(): String?
    fun deleteCreatedDocument()
}

/** Opened streaming upstream paired with its observed transport (`"local"` or `"relay"`). */
internal data class OpenedExportUpstream(
    val upstream: JournalBrowserUpstream,
    val transport: String,
)

/** Abstraction for opening a streaming SPL connection independent of `JournalSheet`. */
internal fun interface ExportUpstreamOpener {
    fun open(): OpenedExportUpstream
}

/**
 * Abstraction for scheduling the active export loop:
 * - On API 34+ (`Build.VERSION.SDK_INT >= 34`), schedules [ExportDownloadJobService] (UIDT, zero `dataSync` FGS).
 * - On API 26–33 (or when UIDT is unavailable), runs on the app's background daemon executor with an
 *   ordinary progress notification (zero `dataSync` FGS) and persisted resume state.
 */
internal interface ExportExecutionScheduler {
    fun scheduleRun(estimatedRemainingBytes: Long)
    fun onProgressUpdated(snapshot: JournalExportStatusSnapshot, filename: String)
    fun onEnded(snapshot: JournalExportStatusSnapshot)
}

/** Persisted resumable export metadata so pauses or process death on API 26–33 or 34+ resume cleanly. */
internal data class PersistedExportState(
    val exportId: String,
    val downloadPath: String,
    val filename: String,
    val sizeBytes: Long,
    val sha256: String,
    val etag: String,
    val mode: String?,
    val uriString: String,
    val displayName: String,
    val bytesWritten: Long,
    val initialTransport: String?,
    val pauseReason: String,
)

internal class ExportStateStore(private val file: File) {
    fun save(state: PersistedExportState) {
        runCatching {
            file.parentFile?.mkdirs()
            val payload = toJson(
                linkedMapOf<String, Any?>(
                    "export_id" to state.exportId,
                    "download_path" to state.downloadPath,
                    "filename" to state.filename,
                    "size_bytes" to state.sizeBytes,
                    "sha256" to state.sha256,
                    "etag" to state.etag,
                    "mode" to state.mode,
                    "uri" to state.uriString,
                    "display_name" to state.displayName,
                    "bytes_written" to state.bytesWritten,
                    "initial_transport" to state.initialTransport,
                    "pause_reason" to state.pauseReason,
                ),
            )
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(payload, Charsets.UTF_8)
            if (!tmp.renameTo(file)) {
                file.writeText(payload, Charsets.UTF_8)
                tmp.delete()
            }
        }
    }

    fun load(): PersistedExportState? = runCatching {
        if (!file.isFile) return null
        val map = parseJson(file.readText(Charsets.UTF_8)) as? Map<*, *> ?: return null
        val exportId = map["export_id"] as? String ?: return null
        val downloadPath = map["download_path"] as? String ?: return null
        val filename = map["filename"] as? String ?: return null
        val sizeBytes = (map["size_bytes"] as? BigDecimal)?.longValueExact() ?: return null
        val sha256 = map["sha256"] as? String ?: return null
        val etag = map["etag"] as? String ?: return null
        val uriString = map["uri"] as? String ?: return null
        val displayName = map["display_name"] as? String ?: filename
        val bytesWritten = (map["bytes_written"] as? BigDecimal)?.longValueExact() ?: 0L
        val mode = map["mode"] as? String
        val initialTransport = map["initial_transport"] as? String
        val pauseReason = (map["pause_reason"] as? String) ?: "paused"
        PersistedExportState(
            exportId = exportId,
            downloadPath = downloadPath,
            filename = filename,
            sizeBytes = sizeBytes,
            sha256 = sha256,
            etag = etag,
            mode = mode,
            uriString = uriString,
            displayName = displayName,
            bytesWritten = bytesWritten.coerceIn(0L, sizeBytes),
            initialTransport = initialTransport,
            pauseReason = pauseReason,
        )
    }.getOrNull()

    fun clear() {
        runCatching { file.delete() }
    }
}

/** Android SAF [ExportDestination] streaming directly into a document created via `ACTION_CREATE_DOCUMENT`. */
internal class SafExportDestination(
    private val context: Context,
    private val uri: Uri,
    override val displayName: String,
) : ExportDestination {
    override val uriString: String = uri.toString()

    @Volatile
    private var randomWriteSupported: Boolean = true

    override val supportsRandomWrite: Boolean
        get() = randomWriteSupported

    override fun currentSizeBytes(): Long = runCatching {
        context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            val stat = pfd.statSize
            if (stat >= 0L) stat else 0L
        } ?: 0L
    }.getOrDefault(0L)

    override fun openWriterAt(offset: Long, truncate: Boolean): OutputStream {
        val resolver = context.contentResolver
        if (offset > 0L && !truncate) {
            val rwPfd = runCatching { resolver.openFileDescriptor(uri, "rw") }.getOrNull()
            if (rwPfd != null) {
                return try {
                    val fos = ParcelFileDescriptor.AutoCloseOutputStream(rwPfd)
                    fos.channel.position(offset)
                    fos
                } catch (e: Throwable) {
                    runCatching { rwPfd.close() }
                    randomWriteSupported = false
                    throw IOException("SAF provider does not support seekable write", e)
                }
            }
            randomWriteSupported = false
            throw IOException("SAF provider rejected rw mode for resume")
        }
        val mode = if (truncate) "wt" else "w"
        val pfd = runCatching { resolver.openFileDescriptor(uri, mode) }.getOrNull()
            ?: resolver.openFileDescriptor(uri, "w")
            ?: throw IOException("Could not open SAF document for writing")
        return ParcelFileDescriptor.AutoCloseOutputStream(pfd)
    }

    override fun computeSha256OrNull(): String? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val digest = MessageDigest.getInstance("SHA-256")
            val buf = ByteArray(1024 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                if (n > 0) digest.update(buf, 0, n)
            }
            digest.digest().joinToString("") { "%02x".format(Locale.US, it) }
        }
    }.getOrNull()

    override fun deleteCreatedDocument() {
        runCatching {
            DocumentsContract.deleteDocument(context.contentResolver, uri)
        }
    }

    companion object {
        fun queryDisplayName(context: Context, uri: Uri, fallback: String): String {
            return runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        cursor.getString(0)?.takeIf { it.isNotBlank() }
                    } else {
                        null
                    }
                }
            }.getOrNull() ?: fallback
        }
    }
}

/**
 * App-scoped manager owning native Android journal export downloads.
 *
 * - Runs completely independent of `JournalSheet` lifecycle.
 * - Streams directly into the owner's chosen SAF document (`ACTION_CREATE_DOCUMENT`) with zero app-sandbox double copy.
 * - Resumes interrupted transfers over `Range` + `If-Range` when the SAF descriptor supports `"rw"` seeking.
 * - Verifies SHA-256 on disk (or via resumable SHA-256 midstate on write-only providers) before declaring saved.
 * - Uses API 34+ UIDT [ExportDownloadJobService] on Android 14+ and an ordinary in-process background executor
 *   with persisted resume state on API 26–33 (zero `dataSync` foreground service anywhere).
 */
internal class JournalExportTransferManager(
    private val upstreamOpener: ExportUpstreamOpener,
    private val spaceProbe: () -> Long?,
    private val destinationReopener: (uriString: String, displayName: String) -> ExportDestination?,
    private val stateStore: ExportStateStore,
    private val drainClosed: () -> Boolean = { SyncDrainGate.closed() },
    private val schedulerFactory: (JournalExportTransferManager) -> ExportExecutionScheduler,
) {
    private class ActiveTransfer(
        val request: JournalExportStartRequest,
        val destination: ExportDestination,
        var initialTransport: String?,
        var bytesWritten: Long,
        var hasher: ResumableSha256,
        var userPaused: Boolean = false,
        var userCancelled: Boolean = false,
        var activeUpstream: JournalBrowserUpstream? = null,
    )

    private val lock = ReentrantLock()
    private val listeners = CopyOnWriteArrayList<(JournalExportStatusSnapshot) -> Unit>()
    private val loopRunning = AtomicBoolean(false)
    private var active: ActiveTransfer? = null
    private var snapshot: JournalExportStatusSnapshot = JournalExportStatusSnapshot.IDLE
    private val scheduler: ExportExecutionScheduler = schedulerFactory(this)

    init {
        restorePersistedPausedIfAny()
    }

    fun addStatusListener(listener: (JournalExportStatusSnapshot) -> Unit): () -> Unit {
        listeners.add(listener)
        return { listeners.remove(listener) }
    }

    fun currentSnapshot(): JournalExportStatusSnapshot = lock.withLock { snapshot }

    fun activeFilename(): String? = lock.withLock { active?.request?.filename }

    fun deviceSpaceSnapshot(): Map<String, Any?> = buildMap {
        put("platform", "android")
        spaceProbe()?.let { put("free_bytes", it) }
    }

    fun start(
        request: JournalExportStartRequest,
        pickDestination: (suggestedFilename: String, onPicked: (ExportDestination?) -> Unit) -> Unit,
        onStarted: (JournalExportStatusSnapshot) -> Unit,
    ) {
        lock.withLock {
            val cur = active
            if (cur != null && cur.request.exportId == request.exportId &&
                (snapshot.phase == "transferring" || snapshot.phase == "verifying")
            ) {
                onStarted(snapshot)
                return
            }
            if (cur != null) {
                cancelActiveLocked(cur, deleteDocument = true)
                active = null
            }
            stateStore.clear()
            publishLocked(
                JournalExportStatusSnapshot(
                    exportId = request.exportId,
                    phase = "picking",
                    bytesWritten = 0L,
                    totalBytes = request.sizeBytes,
                    mode = request.mode,
                ),
            )
        }

        pickDestination(request.filename) { destination ->
            if (destination == null) {
                val idle = lock.withLock {
                    publishLocked(JournalExportStatusSnapshot.IDLE)
                    snapshot
                }
                onStarted(idle)
                return@pickDestination
            }

            val required = requiredDeviceBytes(request.sizeBytes)
            val free = spaceProbe()
            if (free != null && free < required) {
                destination.deleteCreatedDocument()
                val failed = lock.withLock {
                    publishLocked(
                        JournalExportStatusSnapshot(
                            exportId = request.exportId,
                            phase = "failed",
                            bytesWritten = 0L,
                            totalBytes = request.sizeBytes,
                            destinationDisplay = destination.displayName,
                            errorCode = "insufficient_device_space",
                            requiredBytes = required,
                            availableBytes = free,
                            mode = request.mode,
                        ),
                    )
                    snapshot
                }
                onStarted(failed)
                return@pickDestination
            }

            val next = lock.withLock {
                val transfer = ActiveTransfer(
                    request = request,
                    destination = destination,
                    initialTransport = null,
                    bytesWritten = 0L,
                    hasher = ResumableSha256(),
                )
                active = transfer
                val transferring = JournalExportStatusSnapshot(
                    exportId = request.exportId,
                    phase = "transferring",
                    bytesWritten = 0L,
                    totalBytes = request.sizeBytes,
                    destinationDisplay = destination.displayName,
                    mode = request.mode,
                )
                publishLocked(transferring)
                persistPausedStateLocked(transfer, "paused")
                transferring
            }
            scheduler.scheduleRun(request.sizeBytes)
            onStarted(next)
        }
    }

    fun pause(exportId: String?, reason: String = "paused"): JournalExportStatusSnapshot {
        val paused = lock.withLock {
            val cur = active ?: return snapshot
            if (!exportId.isNullOrEmpty() && cur.request.exportId != exportId) return snapshot
            if (snapshot.phase != "transferring") return snapshot
            cur.userPaused = true
            runCatching { cur.activeUpstream?.close() }
            cur.activeUpstream = null
            persistPausedStateLocked(cur, reason)
            publishLocked(
                JournalExportStatusSnapshot(
                    exportId = cur.request.exportId,
                    phase = "paused",
                    bytesWritten = cur.bytesWritten,
                    totalBytes = cur.request.sizeBytes,
                    transport = snapshot.transport,
                    destinationDisplay = cur.destination.displayName,
                    pauseReason = reason,
                    mode = cur.request.mode,
                ),
            )
            snapshot
        }
        scheduler.onProgressUpdated(paused, activeFilename() ?: "")
        return paused
    }

    fun pauseForOsStop(): JournalExportStatusSnapshot = pause(exportId = null, reason = "paused")

    fun resume(exportId: String?): JournalExportStatusSnapshot {
        val remaining: Long
        val resumed = lock.withLock {
            val cur = active ?: return snapshot
            if (!exportId.isNullOrEmpty() && cur.request.exportId != exportId) return snapshot
            if (snapshot.phase != "paused") return snapshot
            cur.userPaused = false
            cur.userCancelled = false
            // Allow relay continuation once the owner explicitly taps Resume after `relay_switched`.
            if (snapshot.pauseReason == "relay_switched") {
                cur.initialTransport = "relay"
            }
            remaining = (cur.request.sizeBytes - cur.bytesWritten).coerceAtLeast(1L)
            publishLocked(
                JournalExportStatusSnapshot(
                    exportId = cur.request.exportId,
                    phase = "transferring",
                    bytesWritten = cur.bytesWritten,
                    totalBytes = cur.request.sizeBytes,
                    transport = snapshot.transport,
                    destinationDisplay = cur.destination.displayName,
                    mode = cur.request.mode,
                ),
            )
            snapshot
        }
        scheduler.scheduleRun(remaining)
        return resumed
    }

    fun cancel(exportId: String?): JournalExportStatusSnapshot {
        val next = lock.withLock {
            val cur = active
            if (cur != null) {
                if (!exportId.isNullOrEmpty() && cur.request.exportId != exportId) return snapshot
                cancelActiveLocked(cur, deleteDocument = true)
                active = null
            }
            stateStore.clear()
            publishLocked(JournalExportStatusSnapshot.IDLE)
            snapshot
        }
        scheduler.onEnded(next)
        return next
    }

    fun cancelForUnpair() {
        cancel(exportId = null)
    }

    /**
     * Runs one streaming transfer attempt synchronously on the calling worker thread (either
     * [ExportDownloadJobService] on API 34+ or the in-process daemon executor on API 26–33).
     * Returns `true` only if the system should reschedule later (transient disconnect while not
     * explicitly paused/cancelled); returns `false` on save, user pause, or terminal error.
     */
    fun runTransferAttemptBlocking(): Boolean {
        if (!loopRunning.compareAndSet(false, true)) return false
        try {
            val transfer = lock.withLock {
                val cur = active ?: return false
                if (cur.userPaused || cur.userCancelled) return false
                if (snapshot.phase != "transferring") return false
                cur
            }
            if (drainClosed()) {
                pause(transfer.request.exportId, reason = "paused")
                return false
            }

            val opened = try {
                upstreamOpener.open()
            } catch (_: JournalBrowserIdentityException) {
                finishFailure(transfer.request.exportId, "export_requester_revoked")
                return false
            } catch (_: Throwable) {
                markDisconnectedPause(transfer.request.exportId, transfer.bytesWritten)
                return false
            }

            val upstream = opened.upstream
            val transport = opened.transport

            var abortedBeforeStream = false
            var shouldPauseForRelay = false
            lock.withLock {
                val cur = active
                if (cur !== transfer || cur.userPaused || cur.userCancelled) {
                    runCatching { upstream.close() }
                    abortedBeforeStream = true
                } else if (cur.initialTransport == null) {
                    cur.initialTransport = transport
                } else if (cur.initialTransport == "local" && transport == "relay") {
                    runCatching { upstream.close() }
                    shouldPauseForRelay = true
                }
            }
            if (abortedBeforeStream) {
                return false
            }
            if (shouldPauseForRelay) {
                pause(transfer.request.exportId, reason = "relay_switched")
                return false
            }

            val diskOffset = transfer.destination.currentSizeBytes()
            val canResume = transfer.destination.supportsRandomWrite &&
                diskOffset > 0L &&
                diskOffset < transfer.request.sizeBytes
            var resumeOffset = if (canResume) diskOffset else 0L

            lock.withLock {
                val cur = active
                if (cur !== transfer || cur.userPaused || cur.userCancelled) {
                    runCatching { upstream.close() }
                    return false
                }
                cur.activeUpstream = upstream
                cur.bytesWritten = resumeOffset
                publishLocked(
                    JournalExportStatusSnapshot(
                        exportId = cur.request.exportId,
                        phase = "transferring",
                        bytesWritten = resumeOffset,
                        totalBytes = cur.request.sizeBytes,
                        transport = transport,
                        destinationDisplay = cur.destination.displayName,
                        mode = cur.request.mode,
                    ),
                )
            }

            val headers = mutableListOf<Pair<String, String>>()
            if (resumeOffset > 0L) {
                headers.add("Range" to "bytes=$resumeOffset-")
                headers.add("If-Range" to transfer.request.etag)
            }

            var writer: OutputStream? = null
            var responseStatus = 0
            var bytesWritten = resumeOffset
            var errorBody: ByteArrayOutputStream? = null
            var sinkFailureCode: String? = null
            var streamCompleted = false
            val startNs = System.nanoTime()
            var sessionBytes = 0L
            var lastEmitMs = 0L
            var lastPersistBytes = resumeOffset

            try {
                upstream.requestStreaming(
                    method = "GET",
                    path = transfer.request.downloadPath,
                    headers = headers,
                    bodySource = null,
                    responseSink = object : BrowserResponseSink {
                        override fun onStatusAndHeaders(
                            status: Int,
                            reason: String,
                            headers: List<Pair<String, String>>,
                        ) {
                            responseStatus = status
                            when (status) {
                                200 -> {
                                    // Server returned full archive (first attempt or ETag changed under If-Range).
                                    bytesWritten = 0L
                                    transfer.hasher = ResumableSha256()
                                    writer = transfer.destination.openWriterAt(0L, truncate = true)
                                }
                                206 -> {
                                    val contentRange = headers.firstOrNull {
                                        it.first.equals("content-range", ignoreCase = true)
                                    }?.second
                                    if (!matchesContentRange(contentRange, resumeOffset, transfer.request.sizeBytes)) {
                                        throw IOException("Unexpected Content-Range: $contentRange")
                                    }
                                    writer = transfer.destination.openWriterAt(resumeOffset, truncate = false)
                                }
                                416 -> {
                                    // Offset out of range; fail softly by restarting cleanly next time.
                                    bytesWritten = 0L
                                    sinkFailureCode = "save_failed"
                                }
                                401, 403 -> {
                                    sinkFailureCode = "export_requester_revoked"
                                }
                                404 -> {
                                    sinkFailureCode = "export_expired"
                                }
                                410 -> {
                                    errorBody = ByteArrayOutputStream()
                                }
                                else -> {
                                    sinkFailureCode = "save_failed"
                                }
                            }
                        }

                        override fun onBodyChunk(chunk: ByteArray, offset: Int, length: Int) {
                            if (length <= 0) return
                            if (transfer.userPaused || transfer.userCancelled || drainClosed()) {
                                throw ExportInterruptedException()
                            }
                            errorBody?.let { buf ->
                                if (buf.size() < 4096) {
                                    buf.write(chunk, offset, minOf(length, 4096 - buf.size()))
                                }
                                return
                            }
                            if (sinkFailureCode != null) return
                            val out = writer ?: return
                            out.write(chunk, offset, length)
                            transfer.hasher.update(chunk, offset, length)
                            bytesWritten += length.toLong()
                            sessionBytes += length.toLong()
                            val nowMs = System.currentTimeMillis()
                            if (nowMs - lastEmitMs >= 200L || bytesWritten >= transfer.request.sizeBytes) {
                                lastEmitMs = nowMs
                                val elapsedSec = ((System.nanoTime() - startNs).coerceAtLeast(1L)) / 1_000_000_000.0
                                val rate = if (elapsedSec > 0.05) sessionBytes.toDouble() / elapsedSec else null
                                val snap = lock.withLock {
                                    if (active !== transfer) return@withLock null
                                    transfer.bytesWritten = bytesWritten
                                    if (bytesWritten - lastPersistBytes >= 4L * 1024L * 1024L) {
                                        lastPersistBytes = bytesWritten
                                        persistPausedStateLocked(transfer, "paused")
                                    }
                                    publishLocked(
                                        JournalExportStatusSnapshot(
                                            exportId = transfer.request.exportId,
                                            phase = "transferring",
                                            bytesWritten = bytesWritten,
                                            totalBytes = transfer.request.sizeBytes,
                                            rateBytesPerSec = rate,
                                            transport = transport,
                                            destinationDisplay = transfer.destination.displayName,
                                            mode = transfer.request.mode,
                                        ),
                                    )
                                    snapshot
                                }
                                if (snap != null) {
                                    scheduler.onProgressUpdated(snap, transfer.request.filename)
                                }
                            }
                        }

                        override fun onComplete() {
                            streamCompleted = true
                        }
                    },
                )
            } catch (_: ExportInterruptedException) {
                runCatching { writer?.flush() }
                runCatching { writer?.close() }
                writer = null
                lock.withLock {
                    if (active === transfer) {
                        transfer.bytesWritten = bytesWritten
                        transfer.activeUpstream = null
                        if (!transfer.userCancelled) {
                            persistPausedStateLocked(transfer, "paused")
                        }
                    }
                }
                if (drainClosed() && !transfer.userCancelled) {
                    pause(transfer.request.exportId, reason = "paused")
                }
                return false
            } catch (e: Throwable) {
                runCatching { writer?.flush() }
                runCatching { writer?.close() }
                writer = null
                lock.withLock {
                    if (active === transfer) {
                        transfer.bytesWritten = bytesWritten
                        transfer.activeUpstream = null
                    }
                }
                if (transfer.userPaused || transfer.userCancelled) return false
                if (e is JournalBrowserIdentityException) {
                    finishFailure(transfer.request.exportId, "export_requester_revoked")
                    return false
                }
                if (isDiskSpaceError(e)) {
                    finishFailure(transfer.request.exportId, "insufficient_device_space")
                    return false
                }
                markDisconnectedPause(transfer.request.exportId, bytesWritten)
                return false
            } finally {
                runCatching { writer?.flush() }
                runCatching { writer?.close() }
                runCatching { upstream.close() }
                lock.withLock {
                    if (active === transfer) {
                        transfer.activeUpstream = null
                    }
                }
            }

            if (transfer.userPaused || transfer.userCancelled) return false

            if (responseStatus == 410) {
                val parsed = errorBody?.toByteArray()?.let(::extractErrorCode)
                finishFailure(transfer.request.exportId, parsed ?: "export_invalidated_by_erasure")
                return false
            }
            if (sinkFailureCode != null) {
                finishFailure(transfer.request.exportId, sinkFailureCode!!)
                return false
            }
            if (!streamCompleted || bytesWritten != transfer.request.sizeBytes) {
                markDisconnectedPause(transfer.request.exportId, bytesWritten)
                return false
            }

            // Transition to verifying phase and check SHA-256 against the expected digest.
            val verifyingSnap = lock.withLock {
                if (active !== transfer || transfer.userCancelled) return false
                transfer.bytesWritten = bytesWritten
                publishLocked(
                    JournalExportStatusSnapshot(
                        exportId = transfer.request.exportId,
                        phase = "verifying",
                        bytesWritten = bytesWritten,
                        totalBytes = transfer.request.sizeBytes,
                        transport = transport,
                        destinationDisplay = transfer.destination.displayName,
                        mode = transfer.request.mode,
                    ),
                )
                snapshot
            }
            scheduler.onProgressUpdated(verifyingSnap, transfer.request.filename)

            val actualSha = transfer.destination.computeSha256OrNull()
                ?: if (resumeOffset == 0L) transfer.hasher.digestHex() else null
            if (actualSha == null || !actualSha.equals(transfer.request.sha256, ignoreCase = true)) {
                finishFailure(transfer.request.exportId, "checksum_mismatch")
                return false
            }

            val savedSnap = lock.withLock {
                if (active !== transfer || transfer.userCancelled) return false
                active = null
                stateStore.clear()
                publishLocked(
                    JournalExportStatusSnapshot(
                        exportId = transfer.request.exportId,
                        phase = "saved",
                        bytesWritten = bytesWritten,
                        totalBytes = transfer.request.sizeBytes,
                        transport = transport,
                        destinationDisplay = transfer.destination.displayName,
                        canReveal = false,
                        verified = true,
                        mode = transfer.request.mode,
                    ),
                )
                snapshot
            }
            scheduler.onEnded(savedSnap)
            return false
        } finally {
            loopRunning.set(false)
        }
    }

    private fun markDisconnectedPause(exportId: String, bytesWritten: Long) {
        val snap = lock.withLock {
            val cur = active ?: return
            if (cur.request.exportId != exportId || cur.userCancelled || cur.userPaused) return
            cur.bytesWritten = bytesWritten
            persistPausedStateLocked(cur, "disconnected")
            publishLocked(
                JournalExportStatusSnapshot(
                    exportId = exportId,
                    phase = "paused",
                    bytesWritten = bytesWritten,
                    totalBytes = cur.request.sizeBytes,
                    transport = snapshot.transport,
                    destinationDisplay = cur.destination.displayName,
                    pauseReason = "disconnected",
                    mode = cur.request.mode,
                ),
            )
            snapshot
        }
        scheduler.onProgressUpdated(snap, activeFilename() ?: "")
    }

    private fun finishFailure(exportId: String, errorCode: String) {
        val snap = lock.withLock {
            val cur = active ?: return
            if (cur.request.exportId != exportId || cur.userCancelled) return
            cur.destination.deleteCreatedDocument()
            active = null
            stateStore.clear()
            publishLocked(
                JournalExportStatusSnapshot(
                    exportId = exportId,
                    phase = "failed",
                    bytesWritten = 0L,
                    totalBytes = cur.request.sizeBytes,
                    transport = snapshot.transport,
                    destinationDisplay = cur.destination.displayName,
                    errorCode = errorCode,
                    mode = cur.request.mode,
                ),
            )
            snapshot
        }
        scheduler.onEnded(snap)
    }

    private fun cancelActiveLocked(transfer: ActiveTransfer, deleteDocument: Boolean) {
        transfer.userCancelled = true
        runCatching { transfer.activeUpstream?.close() }
        transfer.activeUpstream = null
        if (deleteDocument) {
            transfer.destination.deleteCreatedDocument()
        }
    }

    private fun persistPausedStateLocked(transfer: ActiveTransfer, pauseReason: String) {
        stateStore.save(
            PersistedExportState(
                exportId = transfer.request.exportId,
                downloadPath = transfer.request.downloadPath,
                filename = transfer.request.filename,
                sizeBytes = transfer.request.sizeBytes,
                sha256 = transfer.request.sha256,
                etag = transfer.request.etag,
                mode = transfer.request.mode,
                uriString = transfer.destination.uriString,
                displayName = transfer.destination.displayName,
                bytesWritten = transfer.bytesWritten,
                initialTransport = transfer.initialTransport,
                pauseReason = pauseReason,
            ),
        )
    }

    private fun restorePersistedPausedIfAny() {
        val persisted = stateStore.load() ?: return
        val dest = destinationReopener(persisted.uriString, persisted.displayName)
        if (dest == null) {
            stateStore.clear()
            return
        }
        val actualDiskSize = dest.currentSizeBytes().coerceIn(0L, persisted.sizeBytes)
        if (actualDiskSize >= persisted.sizeBytes) {
            // Do not claim saved without re-verifying; leave clean if finished or stale.
            val sha = dest.computeSha256OrNull()
            if (sha != null && sha.equals(persisted.sha256, ignoreCase = true)) {
                stateStore.clear()
                return
            }
        }
        val transfer = ActiveTransfer(
            request = JournalExportStartRequest(
                exportId = persisted.exportId,
                downloadPath = persisted.downloadPath,
                filename = persisted.filename,
                sizeBytes = persisted.sizeBytes,
                sha256 = persisted.sha256,
                etag = persisted.etag,
                mode = persisted.mode,
            ),
            destination = dest,
            initialTransport = persisted.initialTransport,
            bytesWritten = actualDiskSize,
            hasher = ResumableSha256(),
            userPaused = true,
        )
        lock.withLock {
            active = transfer
            snapshot = JournalExportStatusSnapshot(
                exportId = persisted.exportId,
                phase = "paused",
                bytesWritten = actualDiskSize,
                totalBytes = persisted.sizeBytes,
                destinationDisplay = persisted.displayName,
                pauseReason = persisted.pauseReason,
                mode = persisted.mode,
            )
        }
    }

    private fun publishLocked(next: JournalExportStatusSnapshot) {
        snapshot = next
        for (listener in listeners) {
            runCatching { listener(next) }
        }
    }

    private class ExportInterruptedException : RuntimeException()

    companion object {
        private val singleton = AtomicReference<JournalExportTransferManager?>(null)

        fun get(context: Context): JournalExportTransferManager {
            val existing = singleton.get()
            if (existing != null) return existing
            val app = context.applicationContext
            val created = JournalExportTransferManager(
                upstreamOpener = ExportUpstreamOpener {
                    val stores = syncStores(app)
                    val lastIsRelay = AtomicBoolean(false)
                    val adapter = JournalBrowserUpstreamAdapter(
                        publisher = stores.publisher,
                        guard = stores.handshakeGuard,
                        onLegOpened = { relay -> lastIsRelay.set(relay) },
                    )
                    val upstream = adapter.open()
                    OpenedExportUpstream(
                        upstream = upstream,
                        transport = if (lastIsRelay.get()) "relay" else "local",
                    )
                },
                spaceProbe = {
                    runCatching { StatFs(app.filesDir.path).availableBytes }.getOrNull()
                },
                destinationReopener = { uriString, displayName ->
                    runCatching {
                        val uri = Uri.parse(uriString)
                        SafExportDestination(app, uri, displayName)
                    }.getOrNull()
                },
                stateStore = ExportStateStore(File(app.filesDir, "export-download/active.json")),
                schedulerFactory = { manager ->
                    AndroidExportExecutionScheduler(app, manager)
                },
            )
            if (singleton.compareAndSet(null, created)) {
                runCatching {
                    val publisher = syncStores(app).publisher
                    var lastPairing = (publisher.currentSnapshot() as? app.solstone.core.identity.PairingGraphSnapshot.Committed)?.pairing
                    publisher.subscribe { snap ->
                        when (snap) {
                            is app.solstone.core.identity.PairingGraphSnapshot.Absent,
                            is app.solstone.core.identity.PairingGraphSnapshot.Uncertain,
                            -> {
                                lastPairing = null
                                created.cancelForUnpair()
                            }
                            is app.solstone.core.identity.PairingGraphSnapshot.Committed -> {
                                if (lastPairing != null && snap.pairing != lastPairing) {
                                    created.cancelForUnpair()
                                }
                                lastPairing = snap.pairing
                            }
                        }
                    }
                }
                return created
            }
            return singleton.get()!!
        }

        internal fun requiredDeviceBytes(exportSizeBytes: Long): Long {
            val headroom = minOf(64L * 1024L * 1024L, maxOf(1L * 1024L * 1024L, exportSizeBytes / 100L))
            return if (exportSizeBytes > Long.MAX_VALUE - headroom) Long.MAX_VALUE else exportSizeBytes + headroom
        }

        internal fun matchesContentRange(header: String?, expectedStart: Long, expectedTotal: Long): Boolean {
            if (header == null || !header.startsWith("bytes ")) return false
            val rest = header.removePrefix("bytes ")
            val slash = rest.indexOf('/')
            if (slash < 0) return false
            val total = rest.substring(slash + 1).trim().toLongOrNull() ?: return false
            if (total != expectedTotal) return false
            val rangePart = rest.substring(0, slash).trim()
            val dash = rangePart.indexOf('-')
            if (dash < 0) return false
            val start = rangePart.substring(0, dash).toLongOrNull() ?: return false
            val end = rangePart.substring(dash + 1).toLongOrNull() ?: return false
            return start == expectedStart && end >= start && end < expectedTotal
        }

        private fun extractErrorCode(bytes: ByteArray): String? = runCatching {
            val obj = parseJson(bytes.toString(Charsets.UTF_8)) as? Map<*, *>
            obj?.get("code") as? String
        }.getOrNull()

        private fun isDiskSpaceError(t: Throwable): Boolean {
            var cur: Throwable? = t
            while (cur != null) {
                val msg = cur.message?.lowercase(Locale.US) ?: ""
                if ("enospc" in msg || "no space left" in msg || "edquot" in msg) return true
                cur = cur.cause
            }
            return false
        }
    }
}

/**
 * Platform execution scheduler separating Android 14+ (API 34+) UIDT [ExportDownloadJobService]
 * from Android 8–13 (API 26–33) in-process background daemon execution — zero `dataSync` FGS.
 */
internal class AndroidExportExecutionScheduler(
    private val app: Context,
    private val manager: JournalExportTransferManager,
    private val sdkIntProvider: () -> Int = { Build.VERSION.SDK_INT },
    private val scheduleUidt: (Context, Long) -> Boolean = ExportDownloadScheduler::scheduleUidtJob,
    private val cancelUidt: (Context) -> Unit = ExportDownloadScheduler::cancelJob,
    private val postFallbackNotification: (Context, JournalExportStatusSnapshot, String) -> Unit = ExportDownloadNotification::postStandalone,
    private val cancelFallbackNotification: (Context) -> Unit = ExportDownloadNotification::cancelStandalone,
) : ExportExecutionScheduler {
    override fun scheduleRun(estimatedRemainingBytes: Long) {
        if (sdkIntProvider() >= 34 && scheduleUidt(app, estimatedRemainingBytes)) {
            cancelFallbackNotification(app)
            return
        }
        // API 26–33 (or UIDT unavailable): stream on the app's background daemon executor with an
        // ordinary progress notification (never a dataSync FGS) and persisted resume state.
        fallbackExecutor.execute {
            val snap = manager.currentSnapshot()
            postFallbackNotification(app, snap, manager.activeFilename() ?: "")
            manager.runTransferAttemptBlocking()
        }
    }

    override fun onProgressUpdated(snapshot: JournalExportStatusSnapshot, filename: String) {
        if (sdkIntProvider() >= 34 && ExportDownloadJobService.isActive()) {
            ExportDownloadJobService.updateNotificationIfActive(snapshot, filename)
        } else if (snapshot.phase == "transferring" || snapshot.phase == "verifying" || snapshot.phase == "paused") {
            postFallbackNotification(app, snapshot, filename)
        }
    }

    override fun onEnded(snapshot: JournalExportStatusSnapshot) {
        cancelUidt(app)
        cancelFallbackNotification(app)
    }

    companion object {
        private val fallbackExecutor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "export-download-bg").apply { isDaemon = true }
        }
    }
}
