// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc
package app.solstone.observer.wear.worker

import android.content.Context
import android.util.AtomicFile
import java.io.File
import java.util.Properties

internal class WatchPeerStatusStore(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "courier-peer.properties"))
    data class Peer(val contactAt: Long?, val version: Int?, val lastAttempt: Long?)
    fun read(): Peer = synchronized(lock) {
        val p = Properties()
        if (file.baseFile.exists()) file.openRead().use(p::load)
        Peer(p.getProperty("contact")?.toLongOrNull(), p.getProperty("version")?.toIntOrNull(), p.getProperty("attempt")?.toLongOrNull())
    }
    fun attempted() = update(null)
    fun heard(version: Int) = update(version)
    private fun update(version: Int?) = synchronized(lock) {
        val old = read()
        val p = Properties().apply {
            setProperty("attempt", (if (version == null) System.currentTimeMillis() else old.lastAttempt)?.toString().orEmpty())
            setProperty("contact", (if (version != null) System.currentTimeMillis() else old.contactAt)?.toString().orEmpty())
            setProperty("version", (version ?: old.version)?.toString().orEmpty())
        }
        val out = file.startWrite()
        try { p.store(out, null); file.finishWrite(out) } catch (e: Exception) { file.failWrite(out); throw e }
    }
    companion object { private val lock = Any() }
}
