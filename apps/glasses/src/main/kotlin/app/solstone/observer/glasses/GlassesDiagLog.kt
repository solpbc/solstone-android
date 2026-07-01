// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.glasses

import app.solstone.core.diagnostics.DiagEvent
import app.solstone.core.diagnostics.DiagnosticEventSink
import java.io.File

object GlassesDiagLog {
    @Volatile private var sink: DiagnosticEventSink? = null

    fun install(filesDir: File): DiagnosticEventSink {
        val installed = DiagnosticEventSink(
            dir = filesDir.toPath().resolve(DIAG_DIR),
            capBytes = CAP_BYTES,
            nowProvider = System::currentTimeMillis,
        )
        sink = installed
        return installed
    }

    fun emit(event: DiagEvent) {
        runCatching { sink?.log(event) }
    }

    fun appendRaw(line: String) {
        runCatching { sink?.append(line) }
    }

    fun installedSink(): DiagnosticEventSink? = sink

    private const val DIAG_DIR = "diag"
    private const val CAP_BYTES = 256L * 1024L
}
