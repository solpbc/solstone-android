// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.obsidian

import android.content.ContentResolver
import android.net.Uri
import android.os.CancellationSignal
import android.provider.DocumentsContract
import app.solstone.core.obsidian.OBSIDIAN_MAX_TEXT_BYTES
import app.solstone.core.obsidian.ObsidianEntry
import app.solstone.core.obsidian.ObsidianRead
import app.solstone.core.obsidian.obsidianHash
import app.solstone.core.obsidian.reportableObsidianPath
import app.solstone.platform.foldersync.FolderFile
import app.solstone.platform.foldersync.FolderRead
import app.solstone.platform.foldersync.SafFolder
import java.io.IOException
import java.util.Locale

class VaultAccessException(message: String) : IOException(message)

data class ChosenObsidianVault(val root: String, val uri: Uri, val name: String)

/** Obsidian's vault validation, path policy, and text format over an owner-granted folder. */
class SafObsidianVault(resolver: ContentResolver) {
    private val folder = SafFolder(resolver)

    fun hasReadGrant(uri: Uri): Boolean = folder.hasReadGrant(uri)
    fun release(uri: Uri) = folder.release(uri)

    fun validate(
        uri: Uri,
        takeGrant: Boolean = false,
        signal: CancellationSignal? = null,
    ): ChosenObsidianVault {
        if (!DocumentsContract.isTreeUri(uri) || uri.authority != "com.android.externalstorage.documents") {
            throw VaultAccessException("device_storage")
        }
        if (takeGrant) folder.persist(uri)
        if (!folder.hasReadGrant(uri)) throw VaultAccessException("access_lost")
        val root = folder.root(uri, signal)?.takeIf { it.directory } ?: throw VaultAccessException("access_lost")
        val name = root.name
        if (name.toByteArray(Charsets.UTF_8).size !in 1..128 || name.any { Character.isISOControl(it) }) {
            throw VaultAccessException("invalid_name")
        }
        val config = folder.children(uri, root.file.documentId, signal)
            .firstOrNull { it.name == ".obsidian" && it.directory } ?: throw VaultAccessException("not_vault")
        // Listing the config directory verifies access without using its contents.
        folder.children(uri, config.file.documentId, signal)
        return ChosenObsidianVault(obsidianHash(uri.toString().toByteArray(Charsets.UTF_8)), uri, name)
    }

    fun walk(vault: ChosenObsidianVault, signal: CancellationSignal): List<ObsidianEntry> =
        folder.walk(vault.uri, signal, include = { document ->
            val name = document.name
            name.isNotEmpty() && !name.startsWith('.') && name.lowercase(Locale.ROOT) !in excludedNames &&
                '/' !in name && '\\' !in name && name.none { Character.isISOControl(it) }
        }) { document, parent ->
            val path = if (parent.isEmpty()) document.name else parent.joinToString("/") + "/" + document.name
            val file = document.file
            if (reportableObsidianPath(path)) ObsidianEntry(path, file.modifiedMs, file.size, file.documentId) else null
        }

    fun read(vault: ChosenObsidianVault, entry: ObsidianEntry, signal: CancellationSignal): ObsidianRead {
        val file = FolderFile(entry.documentId, entry.size, entry.modifiedMs)
        val read = try {
            folder.read(vault.uri, file, signal, OBSIDIAN_MAX_TEXT_BYTES, requireRegularFile = true)
        } catch (_: SecurityException) {
            FolderRead.Unavailable
        }
        return when (read) {
            FolderRead.Unavailable -> ObsidianRead.Unavailable
            is FolderRead.TooLarge -> ObsidianRead.TooLarge
            is FolderRead.Stable -> {
                val text = read.bytes.toString(Charsets.UTF_8)
                if (text.toByteArray(Charsets.UTF_8).size > OBSIDIAN_MAX_TEXT_BYTES) ObsidianRead.TooLarge
                else ObsidianRead.Stable(text, read.fileId)
            }
        }
    }

    private companion object {
        val excludedNames = setOf("templates", "_templates")
    }
}
