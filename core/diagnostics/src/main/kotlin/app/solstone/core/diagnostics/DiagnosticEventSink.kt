// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.diagnostics

import app.solstone.core.model.ReasonCode
import app.solstone.core.model.SourceState
import java.io.BufferedOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.APPEND
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE

class DiagnosticEventSink(
    private val dir: Path,
    private val capBytes: Long,
    private val nowProvider: () -> Long,
) {
    private val lock = Any()
    private var dropped: Int = 0
    private val active: Path = dir.resolve(ACTIVE_NAME)
    private val previous: Path = dir.resolve(PREVIOUS_NAME)

    fun append(line: String) {
        synchronized(lock) {
            try {
                Files.createDirectories(dir)
                if (dropped > 0) {
                    val droppedLine = record("kind=diag-dropped count=$dropped")
                    writeRecord(droppedLine)
                    dropped = 0
                }
                writeRecord(record(line))
            } catch (_: Exception) {
                dropped += 1
            }
        }
    }

    fun log(event: DiagEvent) {
        append(formatDiagEvent(event))
    }

    fun readAll(): String =
        synchronized(lock) {
            buildString {
                append(readIfPresent(previous))
                append(readIfPresent(active))
            }
        }

    private fun record(line: String): ByteArray = truncateToCap("ts=${nowProvider()} $line".encodeToByteArray())

    private fun writeRecord(bytes: ByteArray) {
        val bytesWithNewline = bytes.size + 1L
        if (Files.exists(active) && Files.size(active) + bytesWithNewline > capBytes) {
            Files.move(active, previous, REPLACE_EXISTING, ATOMIC_MOVE)
            Files.newBufferedWriter(active, CREATE, WRITE).use { it.write("") }
        }
        BufferedOutputStream(Files.newOutputStream(active, CREATE, APPEND, WRITE)).use { output ->
            output.write(bytes)
            output.write('\n'.code)
            output.flush()
        }
    }

    private fun truncateToCap(bytes: ByteArray): ByteArray {
        val maxLineBytes = (capBytes - 1).coerceAtLeast(0).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        if (bytes.size <= maxLineBytes) return bytes
        return bytes.copyOf(maxLineBytes)
    }

    private fun readIfPresent(path: Path): String =
        try {
            if (Files.isRegularFile(path)) Files.readString(path) else ""
        } catch (_: Exception) {
            ""
        }

    private companion object {
        const val ACTIVE_NAME = "diag.log"
        const val PREVIOUS_NAME = "diag.log.1"
    }
}

sealed interface DiagEvent {
    data class FgsLifecycle(
        val phase: FgsPhase,
        val startId: Int? = null,
        val flags: Int? = null,
    ) : DiagEvent

    enum class FgsPhase { CREATE, START, DESTROY, TASK_REMOVED }

    sealed interface MemoryPressure : DiagEvent {
        data class Trim(val level: Int) : MemoryPressure
        data object Low : MemoryPressure
    }

    data class Swipe(val keyCode: Int, val action: String) : DiagEvent

    data class StateTransition(
        val from: SourceState,
        val to: SourceState,
        val reason: ReasonCode,
    ) : DiagEvent

    data class CaughtException(
        val site: String,
        val type: String,
        val code: Int? = null,
    ) : DiagEvent

    data class CaptureOwner(val transition: CaptureOwnerTransition) : DiagEvent

    enum class CaptureOwnerTransition {
        RESUMED_ACQUIRED,
        STOPPED_RELEASED,
        SCREEN_ON_SET,
        SCREEN_ON_CLEARED,
        START_ACCEPTED,
    }

    data class CaptureRefused(
        val source: CaptureRefusalSource,
        val reason: CaptureRefusalReason,
    ) : DiagEvent

    enum class CaptureRefusalSource { RUNTIME_COMMAND, FGS_REHYDRATE, POLL, SWIPE, OTHER }

    enum class CaptureRefusalReason { NO_VISIBLE_OWNER, CAMERA_PERMISSION_MISSING, MIC_PERMISSION_MISSING }
}

fun formatDiagEvent(event: DiagEvent): String =
    when (event) {
        is DiagEvent.FgsLifecycle -> buildString {
            append("kind=fgs phase=${event.phase.key()}")
            event.startId?.let { append(" startId=$it") }
            event.flags?.let { append(" flags=$it") }
        }
        is DiagEvent.MemoryPressure.Trim -> "kind=mem trim=${event.level}"
        DiagEvent.MemoryPressure.Low -> "kind=mem low"
        is DiagEvent.Swipe -> "kind=swipe key=${event.keyCode} action=${event.action}"
        is DiagEvent.StateTransition -> "kind=state from=${event.from.name} to=${event.to.name} reason=${event.reason.name}"
        is DiagEvent.CaughtException -> buildString {
            append("kind=caught site=${event.site} type=${event.type}")
            event.code?.let { append(" code=$it") }
        }
        is DiagEvent.CaptureOwner -> "kind=capture-owner transition=${event.transition.key()}"
        is DiagEvent.CaptureRefused -> "kind=capture-refused source=${event.source.key()} reason=${event.reason.key()}"
    }

private fun DiagEvent.FgsPhase.key(): String =
    when (this) {
        DiagEvent.FgsPhase.CREATE -> "create"
        DiagEvent.FgsPhase.START -> "start"
        DiagEvent.FgsPhase.DESTROY -> "destroy"
        DiagEvent.FgsPhase.TASK_REMOVED -> "task-removed"
    }

private fun DiagEvent.CaptureOwnerTransition.key(): String = name.lowercase().replace('_', '-')

private fun DiagEvent.CaptureRefusalSource.key(): String = name.lowercase().replace('_', '-')

private fun DiagEvent.CaptureRefusalReason.key(): String = name.lowercase().replace('_', '-')
