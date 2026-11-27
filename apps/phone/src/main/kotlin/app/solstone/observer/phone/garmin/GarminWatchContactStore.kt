// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone.garmin

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.Properties

/**
 * Durable no-backup store tracking whether this phone has ever activated or heard from a
 * solstone Garmin watch app.
 *
 * Stored in `noBackupFilesDir` so no state backs up to Google Drive. Until `isActivated()` is true,
 * the phone app makes zero Garmin Connect IQ SDK calls and surfaces no Garmin UI.
 */
class GarminWatchContactStore(private val rootDir: File) {
    constructor(context: Context) : this(context.noBackupFilesDir)

    data class Contact(
        val activated: Boolean,
        val at: Long?,
        val version: Int?,
        val waitingSlabs: Int?,
        val deviceId: Long?,
        val deviceName: String?,
        val storageFull: Boolean,
        val lastEndReason: Int?,
    )

    private val contactFile get() = File(rootDir, "phone_garmin_contact.txt")

    fun read(): Contact? = synchronized(lock) {
        if (!contactFile.isFile) return@synchronized null
        val text = runCatching { contactFile.readText() }.getOrNull() ?: return@synchronized null
        val p = runCatching { Properties().apply { text.reader().use(::load) } }.getOrNull()
            ?: return@synchronized null
        val activated = p.getProperty("activated")?.toBooleanStrictOrNull() ?: false
        val at = p.getProperty("at")?.toLongOrNull()
        if (!activated && at == null) return@synchronized null
        Contact(
            activated = activated || at != null,
            at = at,
            version = p.getProperty("version")?.toIntOrNull(),
            waitingSlabs = p.getProperty("waiting")?.toIntOrNull()?.takeIf { it >= 0 },
            deviceId = p.getProperty("device_id")?.toLongOrNull(),
            deviceName = p.getProperty("device_name")?.takeIf { it.isNotBlank() },
            storageFull = p.getProperty("storage_full")?.toBooleanStrictOrNull() ?: false,
            lastEndReason = p.getProperty("last_end_reason")?.toIntOrNull(),
        )
    }

    fun isActivated(): Boolean = read()?.activated == true
    fun hasContact(): Boolean = read()?.at != null
    fun lastContactEpochMs(): Long? = read()?.at

    fun markActivated() = synchronized(lock) {
        val prev = read()
        writeLocked(
            Contact(
                activated = true,
                at = prev?.at,
                version = prev?.version,
                waitingSlabs = prev?.waitingSlabs,
                deviceId = prev?.deviceId,
                deviceName = prev?.deviceName,
                storageFull = prev?.storageFull ?: false,
                lastEndReason = prev?.lastEndReason,
            ),
        )
    }

    fun recordContact(
        epochMs: Long,
        version: Int? = null,
        waitingSlabs: Int? = null,
        deviceId: Long? = null,
        deviceName: String? = null,
        storageFull: Boolean? = null,
        lastEndReason: Int? = null,
    ) = synchronized(lock) {
        val prev = read()
        writeLocked(
            Contact(
                activated = true,
                at = epochMs,
                version = version ?: prev?.version,
                waitingSlabs = waitingSlabs?.takeIf { it >= 0 } ?: prev?.waitingSlabs,
                deviceId = deviceId ?: prev?.deviceId,
                deviceName = deviceName?.takeIf { it.isNotBlank() } ?: prev?.deviceName,
                storageFull = storageFull ?: prev?.storageFull ?: false,
                lastEndReason = lastEndReason ?: prev?.lastEndReason,
            ),
        )
    }

    fun clearContact() = synchronized(lock) {
        check(!contactFile.exists() || contactFile.delete())
    }

    private fun writeLocked(contact: Contact) {
        check(rootDir.isDirectory || rootDir.mkdirs())
        val p = Properties().apply {
            setProperty("activated", contact.activated.toString())
            contact.at?.let { setProperty("at", it.toString()) }
            contact.version?.let { setProperty("version", it.toString()) }
            contact.waitingSlabs?.let { setProperty("waiting", it.toString()) }
            contact.deviceId?.let { setProperty("device_id", it.toString()) }
            contact.deviceName?.let { setProperty("device_name", it) }
            setProperty("storage_full", contact.storageFull.toString())
            contact.lastEndReason?.let { setProperty("last_end_reason", it.toString()) }
        }
        val staging = File(rootDir, "phone_garmin_contact.tmp")
        FileOutputStream(staging).use { p.store(it, null); it.fd.sync() }
        check(staging.renameTo(contactFile))
        runCatching {
            FileChannel.open(rootDir.toPath(), StandardOpenOption.READ).use { it.force(true) }
        }
    }

    companion object {
        private val lock = Any()
    }
}
