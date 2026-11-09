// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc
package app.solstone.observer.wear.service

import app.solstone.core.watch.courier.WatchSessionState
import app.solstone.core.watch.courier.WindowPumpStopReason
import java.io.File
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.Properties

/** Owner intent and transitions stay in private, non-backed-up device storage. */
internal class WatchCaptureStatusStore(private val root: File) {
    data class Status(val state: WatchSessionState, val reason: WindowPumpStopReason?, val startedAt: Long?)
    private val statusFile get() = File(root, "capture.properties")

    fun read(): Status? = synchronized(lock) {
        if (!statusFile.isFile) return@synchronized null
        val p = Properties().apply { statusFile.inputStream().use(::load) }
        Status(
            WatchSessionState.valueOf(p.getProperty("state")),
            p.getProperty("reason")?.takeIf(String::isNotEmpty)?.let(WindowPumpStopReason::valueOf),
            p.getProperty("startedAt")?.toLongOrNull(),
        )
    }

    fun write(state: WatchSessionState, reason: WindowPumpStopReason?, startedAt: Long?) = synchronized(lock) {
        check(root.isDirectory || root.mkdirs())
        val p = Properties().apply {
            setProperty("state", state.name)
            setProperty("reason", reason?.name.orEmpty())
            setProperty("startedAt", startedAt?.toString().orEmpty())
        }
        val staging = File(root, "capture.properties.tmp")
        FileOutputStream(staging).use { p.store(it, null); it.fd.sync() }
        check(staging.renameTo(statusFile))
        FileChannel.open(root.toPath(), StandardOpenOption.READ).use { it.force(true) }
        FileOutputStream(File(root, "capture-transitions.tsv"), true).use {
            it.write("${System.currentTimeMillis()}\t${state.name}\t${reason?.name.orEmpty()}\n".toByteArray())
            it.fd.sync()
        }
    }

    companion object { private val lock = Any() }
}
