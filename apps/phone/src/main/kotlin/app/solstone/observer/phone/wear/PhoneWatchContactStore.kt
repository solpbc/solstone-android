// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc
package app.solstone.observer.phone.wear

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.Properties

class PhoneWatchContactStore(private val rootDir: File) {
    constructor(context: Context) : this(context.noBackupFilesDir)
    data class Contact(val at: Long, val version: Int?, val sealedWaiting: Int?)
    private val contactFile get() = File(rootDir, "phone_watch_contact.txt")

    fun read(): Contact? = synchronized(lock) {
        if (!contactFile.isFile) return@synchronized null
        val text = runCatching { contactFile.readText() }.getOrNull() ?: return@synchronized null
        text.trim().toLongOrNull()?.let { return@synchronized Contact(it, null, null) }
        val p = runCatching { Properties().apply { text.reader().use(::load) } }.getOrNull() ?: return@synchronized null
        val at = p.getProperty("at")?.toLongOrNull() ?: return@synchronized null
        Contact(at, p.getProperty("version")?.toIntOrNull(), p.getProperty("waiting")?.toIntOrNull()?.takeIf { it >= 0 })
    }
    fun hasContact(): Boolean = read() != null
    fun lastContactEpochMs(): Long? = read()?.at

    fun recordContact(epochMs: Long, version: Int? = null, sealedWaiting: Int? = null) = synchronized(lock) {
        check(rootDir.isDirectory || rootDir.mkdirs())
        val previous = read()
        val p = Properties().apply {
            setProperty("at", epochMs.toString())
            (version ?: previous?.version)?.let { setProperty("version", it.toString()) }
            // A bundle receipt alone cannot establish the remaining watch queue size.
            sealedWaiting?.takeIf { it >= 0 }?.let { setProperty("waiting", it.toString()) }
        }
        val staging = File(rootDir, "phone_watch_contact.tmp")
        FileOutputStream(staging).use { p.store(it, null); it.fd.sync() }
        check(staging.renameTo(contactFile))
        FileChannel.open(rootDir.toPath(), StandardOpenOption.READ).use { it.force(true) }
    }
    fun clearContact() = synchronized(lock) {
        check(!contactFile.exists() || contactFile.delete())
    }
    companion object { private val lock = Any() }
}
