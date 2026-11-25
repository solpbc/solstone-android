// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.os.Build
import app.solstone.core.observer.HeicExif
import app.solstone.core.observer.INTAKE_MAX_BYTES
import app.solstone.core.observer.INTAKE_MIN_FREE_BYTES
import app.solstone.core.observer.IntakeDecision
import app.solstone.core.observer.IntakeRefusal
import app.solstone.core.observer.intakeDecision
import app.solstone.core.observer.intakeMayCopy
import app.solstone.core.observer.intakeMayStart
import app.solstone.core.observer.intakeName
import app.solstone.core.observer.intakeTextName
import app.solstone.platform.persistence.room.IMPORT_AREA_FILE
import app.solstone.platform.persistence.room.IMPORT_CONTEXT_FILE
import app.solstone.platform.persistence.room.ImportCustodyStore
import app.solstone.platform.persistence.room.openSolstonePersistenceDatabase
import app.solstone.platform.work.SyncScheduler
import app.solstone.platform.work.importStoreRoot
import app.solstone.core.sources.MAIN_STREAM
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One item handed to intake. Its stream is opened by the caller at once, while its read grant holds. */
internal sealed interface IntakeItem {
    val label: String

    data class Stream(
        val displayName: String?,
        val mime: String?,
        val scheme: String?,
        val reportedSize: Long?,
        /** Null when the stream could not be opened. */
        val input: InputStream?,
        /** Optional owner-written text shared alongside an image. */
        val caption: String? = null,
        /** The journal page's card and choices, when the page handed this pick over. */
        val handoff: IntakeHandoff? = null,
    ) : IntakeItem {
        override val label: String get() = displayName ?: "item"
    }

    data class Text(val text: String) : IntakeItem {
        override val label: String get() = intakeTextName(text)
    }
}

/** The journal import page's card ([area]) and the owner's choices for it ([context], JSON object text). */
internal data class IntakeHandoff(val area: String, val context: String)

internal data class IntakeRefused(val label: String, val reason: IntakeRefusal)

/** One item saved on this device: its queue id and the name the owner knows it by. */
internal data class IntakeSaved(val id: String, val label: String)

/** What one intake did: each item saved on this device, and each refusal with why. */
internal data class IntakeOutcome(val saved: List<IntakeSaved>, val refused: List<IntakeRefused>) {
    val accepted: Int get() = saved.size
}

internal sealed interface IntakeState {
    data object Idle : IntakeState
    data class Copying(val done: Int, val total: Int) : IntakeState
    data class Finished(val outcome: IntakeOutcome) : IntakeState
}

/**
 * Copies owner-shared and owner-picked items into the import store, one intake at a time and
 * application-wide, so leaving the screen that started it doesn't stop it. A copy is checked
 * against the journal's size limit and against the free space capture needs as it goes. While an
 * intake runs, a marker records it, so one cut short by the process ending is reported afterwards
 * rather than lost silently.
 */
internal class ImportIntake(
    val store: ImportCustodyStore,
    private val root: File,
    private val requestRun: () -> Unit,
    private val usableSpace: () -> Long,
    private val canConvertHeic: Boolean = Companion.canConvertHeic,
    private val maxBytes: Long = INTAKE_MAX_BYTES,
    private val minFree: Long = INTAKE_MIN_FREE_BYTES,
    private val convertHeic: (File, File) -> Unit = ::heicToJpeg,
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { Thread(it, "import-intake").apply { isDaemon = true } },
) {
    private val running = File(root, ".intake-running")
    private val interruptedMarker = File(root, ".intake-interrupted")
    private val _state = MutableStateFlow<IntakeState>(IntakeState.Idle)
    val state: StateFlow<IntakeState> = _state.asStateFlow()

    /** Whether an earlier intake was cut short and its owner hasn't been told yet. */
    val interrupted: Boolean get() = interruptedMarker.exists()

    init {
        // An intake marker left from an earlier process means that intake never finished: its
        // staging copies are abandoned now, and the owner is told it didn't finish.
        if (running.exists()) {
            root.mkdirs()
            runCatching { interruptedMarker.createNewFile() }
            File(root, ".staging").listFiles()?.forEach { store.abandon(it.name) }
            running.delete()
        }
    }

    fun dismissInterrupted() {
        interruptedMarker.delete()
    }

    /** Copy [items] on the intake thread; [onDone] receives the outcome there. */
    fun submit(items: List<IntakeItem>, onDone: (IntakeOutcome) -> Unit = {}) {
        if (items.isEmpty()) return
        executor.execute {
            val outcome = run(items)
            _state.value = IntakeState.Finished(outcome)
            runCatching { onDone(outcome) }
        }
    }

    private fun run(items: List<IntakeItem>): IntakeOutcome {
        root.mkdirs()
        runCatching { running.createNewFile() }
        val saved = mutableListOf<IntakeSaved>()
        val refused = mutableListOf<IntakeRefused>()
        try {
            items.forEachIndexed { index, item ->
                _state.value = IntakeState.Copying(index, items.size)
                val taken = try {
                    take(item)
                } catch (_: IOException) {
                    Taken.No(IntakeRefusal.UNREADABLE)
                } catch (_: RuntimeException) {
                    Taken.No(IntakeRefusal.UNREADABLE)
                }
                when (taken) {
                    is Taken.Yes -> saved += IntakeSaved(taken.id, item.label)
                    is Taken.No -> refused += IntakeRefused(item.label, taken.reason)
                }
            }
        } finally {
            items.forEach { (it as? IntakeItem.Stream)?.input?.let { input -> runCatching { input.close() } } }
            running.delete()
        }
        if (saved.isNotEmpty()) runCatching(requestRun)
        return IntakeOutcome(saved, refused)
    }

    private sealed interface Taken {
        data class Yes(val id: String) : Taken
        data class No(val reason: IntakeRefusal) : Taken
    }

    /** Take one item into the store: its queue id when it was saved, otherwise why it wasn't. */
    private fun take(item: IntakeItem): Taken {
        var id: String? = null
        val refusal = when (item) {
            is IntakeItem.Text -> takeText(item.text) { id = it }
            is IntakeItem.Stream -> takeStream(item) { id = it }
        }
        return if (refusal == null) Taken.Yes(checkNotNull(id)) else Taken.No(refusal)
    }

    private fun takeText(text: String, began: (String) -> Unit): IntakeRefusal? {
        val bytes = text.toByteArray(Charsets.UTF_8)
        intakeMayStart(bytes.size.toLong(), usableSpace(), maxBytes, minFree)?.let { return it }
        val id = store.begin("text")?.also(began) ?: return IntakeRefusal.NO_SPACE
        return try {
            Files.write(staged(id).toPath(), bytes)
            if (store.publish(id, intakeName(intakeTextName(text), "text/plain"), "text/plain", bytes.size.toLong())) null else abandon(id, IntakeRefusal.NO_SPACE)
        } catch (e: IOException) {
            abandon(id, IntakeRefusal.NO_SPACE)
        }
    }

    private fun takeStream(item: IntakeItem.Stream, began: (String) -> Unit): IntakeRefusal? {
        val decision = intakeDecision(item.scheme, item.mime, canConvertHeic)
        if (decision is IntakeDecision.Refuse) return decision.reason
        val input = item.input ?: return IntakeRefusal.UNREADABLE
        intakeMayStart(item.reportedSize, usableSpace(), maxBytes, minFree)?.let { return it }
        val id = store.begin("file")?.also(began) ?: return IntakeRefusal.NO_SPACE
        val payload = staged(id)
        item.handoff?.let { writeHandoff(id, it) }
        return try {
            when (decision) {
                is IntakeDecision.Accept -> {
                    copy(input, payload)?.let { return abandon(id, it) }
                    writeCaption(id, item.caption)
                    finish(id, intakeName(item.displayName, decision.mime), decision.mime, payload)
                }
                IntakeDecision.ConvertToJpeg -> {
                    val raw = File(payload.parentFile, "original")
                    copy(input, raw)?.let { return abandon(id, it) }
                    // A photo this device can't decode or re-encode fails the same way every
                    // time, so it is a conversion refusal, not a read that adding again could fix.
                    try {
                        convertHeic(raw, payload)
                    } catch (_: Exception) {
                        return abandon(id, IntakeRefusal.CANNOT_CONVERT)
                    }
                    raw.delete()
                    writeCaption(id, item.caption)
                    finish(id, intakeName(item.displayName, item.mime.orEmpty(), convertedToJpeg = true), "image/jpeg", payload)
                }
                is IntakeDecision.Refuse -> abandon(id, decision.reason)
            }
        } catch (e: IOException) {
            abandon(id, IntakeRefusal.UNREADABLE)
        }
    }

    private fun finish(id: String, name: String, mime: String, payload: File): IntakeRefusal? =
        if (store.publish(id, name, mime, payload.length())) null else abandon(id, IntakeRefusal.NO_SPACE)

    /** Copy [input] to [target], stopping at the size limit or the free-space floor. */
    private fun copy(input: InputStream, target: File): IntakeRefusal? {
        target.outputStream().use { out ->
            val buffer = ByteArray(COPY_CHUNK)
            var copied = 0L
            var sinceCheck = SPACE_CHECK_EVERY
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (copied + read > maxBytes) return IntakeRefusal.TOO_LARGE
                // The size limit is exact (above); this checks only that the next stretch of
                // the copy leaves capture its room.
                if (sinceCheck >= SPACE_CHECK_EVERY) {
                    intakeMayCopy(0, SPACE_CHECK_EVERY, usableSpace(), Long.MAX_VALUE, minFree)?.let { return it }
                    sinceCheck = 0
                }
                out.write(buffer, 0, read)
                copied += read
                sinceCheck += read
            }
        }
        return null
    }

    private fun staged(id: String): File = File(File(File(root, ".staging"), id), "payload")

    private fun writeHandoff(id: String, handoff: IntakeHandoff) {
        val dir = File(File(root, ".staging"), id)
        Files.write(File(dir, IMPORT_AREA_FILE).toPath(), handoff.area.toByteArray(Charsets.UTF_8))
        Files.write(File(dir, IMPORT_CONTEXT_FILE).toPath(), handoff.context.toByteArray(Charsets.UTF_8))
    }

    private fun writeCaption(id: String, caption: String?) {
        val trimmed = caption?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val file = File(File(File(root, ".staging"), id), "caption.txt")
        Files.write(file.toPath(), trimmed.toByteArray(Charsets.UTF_8))
    }

    private fun abandon(id: String, reason: IntakeRefusal): IntakeRefusal {
        store.abandon(id)
        return reason
    }

    /** The finished intake's result has been seen; the pane stops showing it. */
    fun consumeFinished() {
        val finished = _state.value as? IntakeState.Finished ?: return
        _state.compareAndSet(finished, IntakeState.Idle)
    }

    companion object {
        /** This device can convert HEIC/HEIF photos to JPEG (Android 9 and later). */
        val canConvertHeic: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P

        private const val COPY_CHUNK = 64 * 1024
        private const val SPACE_CHECK_EVERY = 4L * 1024 * 1024

        @Volatile
        private var instance: ImportIntake? = null

        /** The one intake for this process. */
        fun get(context: Context): ImportIntake =
            instance ?: synchronized(this) {
                instance ?: run {
                    val app = context.applicationContext
                    val root = importStoreRoot(app)
                    ImportIntake(
                        store = ImportCustodyStore(root.toPath(), openSolstonePersistenceDatabase(app).importDao()),
                        root = root,
                        requestRun = { ImportUploadScheduler.scheduleForImports(app) },
                        usableSpace = { app.filesDir.usableSpace },
                    ).also { instance = it }
                }
            }
    }
}

/** Decode a HEIC/HEIF photo and write it as JPEG carrying its EXIF metadata (Android 9+). */
private fun heicToJpeg(source: File, target: File) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) throw IOException("HEIC conversion needs Android 9")
    val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(source))
    val encoded = try {
        val buffer = java.io.ByteArrayOutputStream()
        if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 92, buffer)) throw IOException("JPEG encode failed")
        buffer.toByteArray()
    } finally {
        bitmap.recycle()
    }
    val withExif = runCatching {
        HeicExif.carryToUprightJpeg(source.readBytes(), encoded)
    }.getOrDefault(encoded)
    target.writeBytes(withExif)
}
