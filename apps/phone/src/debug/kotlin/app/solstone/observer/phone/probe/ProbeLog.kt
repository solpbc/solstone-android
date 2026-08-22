// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone.probe

import app.solstone.core.diagnostics.DiagnosticEventSink
import app.solstone.platform.fgs.ObserverForegroundService
import java.io.File

object ProbeLog {
    @Volatile private var sink: DiagnosticEventSink? = null
    private val lock = Any()
    private var previousLifecycle: ((String) -> Unit)? = null
    private var lifecycleRefs = 0

    fun install(filesDir: File): DiagnosticEventSink {
        synchronized(lock) {
            sink?.let { return it }
            val installed = DiagnosticEventSink(
                dir = filesDir.toPath().resolve(DIR),
                capBytes = CAP_BYTES,
                nowProvider = System::currentTimeMillis,
            )
            sink = installed
            return installed
        }
    }

    fun appendRaw(line: String) {
        runCatching { sink?.append(line) }
    }

    fun readAll(): String = sink?.readAll().orEmpty()

    fun acquireLifecycleDiag() {
        synchronized(lock) {
            if (lifecycleRefs == 0) {
                previousLifecycle = ObserverForegroundService.lifecycleDiag
                ObserverForegroundService.lifecycleDiag = { line ->
                    previousLifecycle?.invoke(line)
                    appendRaw(line)
                }
            }
            lifecycleRefs += 1
        }
    }

    fun releaseLifecycleDiag() {
        synchronized(lock) {
            if (lifecycleRefs == 0) return
            lifecycleRefs -= 1
            if (lifecycleRefs == 0) {
                ObserverForegroundService.lifecycleDiag = previousLifecycle
                previousLifecycle = null
            }
        }
    }

    private const val DIR = "probe"
    private const val CAP_BYTES = 256L * 1024L
}
