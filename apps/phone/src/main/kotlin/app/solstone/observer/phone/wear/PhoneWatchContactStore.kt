// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc
package app.solstone.observer.phone.wear

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.Properties

enum class WatchPlatform(val wireValue: String) {
    WEAR("wear"),
    GARMIN("garmin");

    companion object {
        fun fromWire(raw: String?): WatchPlatform =
            if (raw.equals(GARMIN.wireValue, ignoreCase = true)) GARMIN else WEAR
    }
}

class PhoneWatchContactStore(private val rootDir: File) {
    constructor(context: Context) : this(context.noBackupFilesDir)

    data class Contact(
        val at: Long,
        val version: Int?,
        val sealedWaiting: Int?,
        val platform: WatchPlatform = WatchPlatform.WEAR,
        val deviceId: Long? = null,
        val deviceName: String? = null,
        val storageFull: Boolean = false,
        val lastEndReason: Int? = null,
    ) {
        val isGarmin: Boolean get() = platform == WatchPlatform.GARMIN
        val hasWatchContact: Boolean get() = at > 0L
    }

    private val contactFile get() = File(rootDir, "phone_watch_contact.txt")

    fun read(): Contact? = synchronized(lock) {
        if (!contactFile.isFile) return@synchronized null
        val text = runCatching { contactFile.readText() }.getOrNull() ?: return@synchronized null
        text.trim().toLongOrNull()?.let { return@synchronized Contact(it, null, null) }
        val p = runCatching { Properties().apply { text.reader().use(::load) } }.getOrNull() ?: return@synchronized null
        val platform = WatchPlatform.fromWire(p.getProperty("platform"))
        val activated = p.getProperty("activated")?.toBooleanStrictOrNull() ?: false
        val at = p.getProperty("at")?.toLongOrNull()
        if (at == null && !(platform == WatchPlatform.GARMIN && activated)) return@synchronized null
        Contact(
            at = at ?: 0L,
            version = p.getProperty("version")?.toIntOrNull(),
            sealedWaiting = p.getProperty("waiting")?.toIntOrNull()?.takeIf { it >= 0 },
            platform = platform,
            deviceId = p.getProperty("device_id")?.toLongOrNull(),
            deviceName = p.getProperty("device_name")?.takeIf { it.isNotBlank() },
            storageFull = p.getProperty("storage_full")?.toBooleanStrictOrNull() ?: false,
            lastEndReason = p.getProperty("last_end_reason")?.toIntOrNull(),
        )
    }

    fun hasContact(): Boolean = read() != null
    fun isGarminActivated(): Boolean = read()?.isGarmin == true
    fun lastContactEpochMs(): Long? = read()?.takeIf { it.at > 0L }?.at

    fun recordContact(epochMs: Long, version: Int? = null, sealedWaiting: Int? = null) = synchronized(lock) {
        val previous = read()?.takeIf { it.platform == WatchPlatform.WEAR }
        writeLocked(
            Contact(
                at = epochMs,
                version = version ?: previous?.version,
                // A bundle receipt alone cannot establish the remaining watch queue size.
                sealedWaiting = sealedWaiting?.takeIf { it >= 0 },
                platform = WatchPlatform.WEAR,
            ),
            activated = true,
        )
    }

    fun recordGarminActivated() = synchronized(lock) {
        val previous = read()?.takeIf { it.isGarmin }
        writeLocked(
            Contact(
                at = previous?.at ?: 0L,
                version = previous?.version,
                sealedWaiting = previous?.sealedWaiting,
                platform = WatchPlatform.GARMIN,
                deviceId = previous?.deviceId,
                deviceName = previous?.deviceName,
                storageFull = previous?.storageFull ?: false,
                lastEndReason = previous?.lastEndReason,
            ),
            activated = true,
        )
    }

    fun recordGarminContact(
        epochMs: Long,
        version: Int? = null,
        waitingSlabs: Int? = null,
        deviceId: Long? = null,
        deviceName: String? = null,
        storageFull: Boolean? = null,
        lastEndReason: Int? = null,
    ) = synchronized(lock) {
        val previous = read()?.takeIf { it.isGarmin }
        writeLocked(
            Contact(
                at = epochMs,
                version = version ?: previous?.version,
                sealedWaiting = waitingSlabs?.takeIf { it >= 0 } ?: previous?.sealedWaiting,
                platform = WatchPlatform.GARMIN,
                deviceId = deviceId ?: previous?.deviceId,
                deviceName = deviceName?.takeIf { it.isNotBlank() } ?: previous?.deviceName,
                storageFull = storageFull ?: previous?.storageFull ?: false,
                lastEndReason = lastEndReason ?: previous?.lastEndReason,
            ),
            activated = true,
        )
    }

    fun clearContact() = synchronized(lock) {
        check(!contactFile.exists() || contactFile.delete())
    }

    private fun writeLocked(contact: Contact, activated: Boolean) {
        check(rootDir.isDirectory || rootDir.mkdirs())
        val p = Properties().apply {
            setProperty("platform", contact.platform.wireValue)
            if (contact.isGarmin) {
                setProperty("activated", activated.toString())
            }
            if (contact.at > 0L || !contact.isGarmin) {
                setProperty("at", contact.at.toString())
            }
            contact.version?.let { setProperty("version", it.toString()) }
            contact.sealedWaiting?.takeIf { it >= 0 }?.let { setProperty("waiting", it.toString()) }
            contact.deviceId?.let { setProperty("device_id", it.toString()) }
            contact.deviceName?.let { setProperty("device_name", it) }
            if (contact.isGarmin) {
                setProperty("storage_full", contact.storageFull.toString())
                contact.lastEndReason?.let { setProperty("last_end_reason", it.toString()) }
            }
        }
        val staging = File(rootDir, "phone_watch_contact.tmp")
        FileOutputStream(staging).use { p.store(it, null); it.fd.sync() }
        check(staging.renameTo(contactFile))
        FileChannel.open(rootDir.toPath(), StandardOpenOption.READ).use { it.force(true) }
    }

    companion object { private val lock = Any() }
}
