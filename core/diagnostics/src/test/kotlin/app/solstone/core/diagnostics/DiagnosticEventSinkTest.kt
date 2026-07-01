// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.diagnostics

import app.solstone.core.model.ReasonCode
import app.solstone.core.model.SourceState
import java.nio.file.Files
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiagnosticEventSinkTest {
    @Test
    fun appendFlushesEachWriteForFreshReader() {
        val dir = Files.createTempDirectory("diag-flush")
        val sink = DiagnosticEventSink(dir, capBytes = 4_096, nowProvider = countingClock())

        sink.append("kind=test id=one")
        sink.append("kind=test id=two")

        val fresh = DiagnosticEventSink(dir, capBytes = 4_096, nowProvider = countingClock(10))
        assertEquals(
            listOf(
                "ts=1 kind=test id=one",
                "ts=2 kind=test id=two",
            ),
            fresh.readAll().trim().lines(),
        )
    }

    @Test
    fun appendNeverThrowsAndReportsDroppedGapOnNextSuccessfulWrite() {
        val dir = Files.createTempDirectory("diag-dropped")
        Files.createDirectory(dir.resolve("diag.log"))
        val sink = DiagnosticEventSink(dir, capBytes = 4_096, nowProvider = countingClock())

        sink.append("kind=test id=failed")
        Files.delete(dir.resolve("diag.log"))
        sink.append("kind=test id=recovered")

        val lines = sink.readAll().trim().lines()
        assertEquals("ts=2 kind=diag-dropped count=1", lines[0])
        assertEquals("ts=3 kind=test id=recovered", lines[1])
    }

    @Test
    fun appendIsThreadSafeAndKeepsLinesIntact() {
        val dir = Files.createTempDirectory("diag-threads")
        val sink = DiagnosticEventSink(dir, capBytes = 32_768, nowProvider = countingClock())
        val ids = (1..50).map { "id=$it" }

        val threads = ids.map { id ->
            thread {
                sink.append("kind=test $id")
            }
        }
        threads.forEach { it.join() }

        val payloads = sink.readAll().trim().lines().map { line ->
            line.substringAfter("kind=test ")
        }
        assertEquals(ids.toSet(), payloads.toSet())
        assertEquals(ids.size, payloads.size)
        assertTrue(payloads.all { it.startsWith("id=") })
    }

    @Test
    fun formatDiagEventsAreTightAndRedacted() {
        assertEquals("kind=fgs phase=create", formatDiagEvent(DiagEvent.FgsLifecycle(DiagEvent.FgsPhase.CREATE)))
        assertEquals(
            "kind=fgs phase=start startId=1 flags=0",
            formatDiagEvent(DiagEvent.FgsLifecycle(DiagEvent.FgsPhase.START, startId = 1, flags = 0)),
        )
        assertEquals(
            "kind=mem trim=15",
            formatDiagEvent(DiagEvent.MemoryPressure.Trim(level = 15)),
        )
        assertEquals("kind=mem low", formatDiagEvent(DiagEvent.MemoryPressure.Low))
        assertEquals(
            "kind=swipe key=24 action=EnsureObserving",
            formatDiagEvent(DiagEvent.Swipe(keyCode = 24, action = "EnsureObserving")),
        )
        assertEquals(
            "kind=state from=OFF to=ON reason=NONE",
            formatDiagEvent(
                DiagEvent.StateTransition(
                    from = SourceState.OFF,
                    to = SourceState.ON,
                    reason = ReasonCode.NONE,
                ),
            ),
        )
        assertEquals(
            "kind=caught site=poll type=IllegalStateException",
            formatDiagEvent(DiagEvent.CaughtException(site = "poll", type = "IllegalStateException")),
        )
        assertEquals(
            "kind=caught site=poll type=IllegalStateException code=7",
            formatDiagEvent(DiagEvent.CaughtException(site = "poll", type = "IllegalStateException", code = 7)),
        )

        val exception = RuntimeException("https://go.solstone.app/p#ticket-token-cert-----BEGIN PRIVATE KEY-----")
        val formatted = formatDiagEvent(
            DiagEvent.CaughtException(
                site = "poll",
                type = exception.javaClass.simpleName,
            ),
        )
        assertEquals("kind=caught site=poll type=RuntimeException", formatted)
        listOf("go.solstone.app/p#", "token", "ticket", "cert", "BEGIN", "PRIVATE KEY").forEach {
            assertFalse(formatted.contains(it), "formatter leaked $it in $formatted")
        }
    }

    @Test
    fun rotationKeepsTwoFilesNewestLinesAndTruncatesOversizeEvents() {
        val dir = Files.createTempDirectory("diag-rotation")
        val sink = DiagnosticEventSink(dir, capBytes = 90, nowProvider = countingClock())

        repeat(10) { index ->
            sink.append("kind=test id=$index padding=abcdefghijklmnop")
        }

        val files = Files.list(dir).use { stream -> stream.map { it.fileName.toString() }.toList() }
        assertTrue(files.all { it in setOf("diag.log", "diag.log.1") })
        val totalBytes = files.sumOf { Files.size(dir.resolve(it)) }
        assertTrue(totalBytes <= 180, "totalBytes=$totalBytes")
        assertTrue(Files.readString(dir.resolve("diag.log")).contains("id=9"))
        assertTrue(sink.readAll().lines().filter { it.isNotBlank() }.all { it.startsWith("ts=") })

        val smallDir = Files.createTempDirectory("diag-oversize")
        val small = DiagnosticEventSink(smallDir, capBytes = 64, nowProvider = { 1L })
        small.append("kind=test payload=${"x".repeat(200)}")
        assertTrue(Files.size(smallDir.resolve("diag.log")) <= 64)
        assertTrue(small.readAll().endsWith("\n"))
    }

    @Test
    fun readAllHandlesMovedPreviousWithoutActiveFile() {
        val dir = Files.createTempDirectory("diag-moved")
        Files.writeString(dir.resolve("diag.log.1"), "ts=1 kind=test id=previous\n")
        val sink = DiagnosticEventSink(dir, capBytes = 4_096, nowProvider = { 2L })

        assertEquals("ts=1 kind=test id=previous\n", sink.readAll())
    }

    private fun countingClock(start: Long = 1): () -> Long {
        var current = start
        return { current++ }
    }
}
