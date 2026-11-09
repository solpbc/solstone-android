// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone.wear

import android.content.Context
import java.io.File

class PhoneWatchContactStore(private val rootDir: File) {
    constructor(context: Context) : this(context.noBackupFilesDir)

    private val contactFile: File
        get() = File(rootDir, "phone_watch_contact.txt")

    fun hasContact(): Boolean = contactFile.exists()

    fun lastContactEpochMs(): Long? {
        if (!contactFile.exists()) return null
        return try {
            contactFile.readText().trim().toLongOrNull()
        } catch (_: Exception) {
            null
        }
    }

    fun recordContact(epochMs: Long) {
        try {
            contactFile.writeText(epochMs.toString())
        } catch (_: Exception) {
        }
    }

    fun clearContact() {
        try {
            contactFile.delete()
        } catch (_: Exception) {
        }
    }
}
