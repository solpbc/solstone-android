// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc
package app.solstone.platform.obsidian

import android.content.ContentResolver
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.CancellationSignal
import android.provider.DocumentsContract
import android.system.Os
import app.solstone.core.obsidian.*
import java.io.ByteArrayOutputStream
import android.os.ParcelFileDescriptor
import java.io.IOException
import java.util.Locale

class VaultAccessException(message: String) : IOException(message)

data class ChosenObsidianVault(val root: String, val uri: Uri, val name: String)

/** All vault operations here are queries or reads. Never resolve a tree grant to a raw path. */
class SafObsidianVault(private val resolver: ContentResolver) {
    private val columns = arrayOf("document_id", "_display_name", "mime_type", "last_modified", "_size", "flags")
    fun hasReadGrant(uri: Uri): Boolean = resolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
    fun validate(uri: Uri, takeGrant: Boolean = false): ChosenObsidianVault {
        // This version supports on-device folders. Cloud providers expose no reliable local-only
        // inventory, so an unavailable placeholder cannot safely become a deletion.
        if (!DocumentsContract.isTreeUri(uri) || uri.authority != "com.android.externalstorage.documents") {
            throw VaultAccessException("device_storage")
        }
        if (takeGrant) resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (!hasReadGrant(uri)) throw VaultAccessException("access_lost")
        val id = DocumentsContract.getTreeDocumentId(uri)
        val rootUri = DocumentsContract.buildDocumentUriUsingTree(uri, id)
        val name = resolver.query(rootUri, columns, null, null, null)?.use { c ->
            complete(c)
            if (!c.moveToFirst() || c.getString(2) != DocumentsContract.Document.MIME_TYPE_DIR) throw VaultAccessException("access_lost")
            c.getString(1)
        } ?: throw VaultAccessException("access_lost")
        if (name.toByteArray(Charsets.UTF_8).size !in 1..128 || name.any { Character.isISOControl(it) }) throw VaultAccessException("invalid_name")
        val children = list(uri, id, null)
        val config = children.firstOrNull { it.name == ".obsidian" && it.directory } ?: throw VaultAccessException("not_vault")
        // Listing the config directory verifies access, without using any of its contents.
        list(uri, config.id, null)
        return ChosenObsidianVault(obsidianHash(uri.toString().toByteArray(Charsets.UTF_8)), uri, name)
    }
    fun walk(vault: ChosenObsidianVault, signal: CancellationSignal): List<ObsidianEntry> {
        validate(vault.uri)
        val result = mutableListOf<ObsidianEntry>()
        val visited = mutableSetOf<String>()
        fun visit(id: String, prefix: String) {
            signal.throwIfCanceled()
            if (!visited.add(id)) throw VaultAccessException("incomplete_listing")
            for (document in list(vault.uri, id, signal)) {
                val name = document.name
                if (name.startsWith('.') || name.lowercase(Locale.ROOT) in setOf("templates", "_templates")) continue
                // Provider display names are components, never paths supplied by the provider.
                if (name.isEmpty() || '/' in name || '\\' in name || name.any { Character.isISOControl(it) }) continue
                val path = prefix + name
                if (document.directory) visit(document.id, "$path/")
                else if (reportableObsidianPath(path)) result += ObsidianEntry(path, document.modified, document.size, document.id)
            }
        }
        visit(DocumentsContract.getTreeDocumentId(vault.uri), "")
        return result
    }
    fun read(vault: ChosenObsidianVault, entry: ObsidianEntry, signal: CancellationSignal): ObsidianRead {
        return try {
            signal.throwIfCanceled()
            val uri = DocumentsContract.buildDocumentUriUsingTree(vault.uri, entry.documentId)
            val before = metadata(uri, signal)
            if (before.flags and DocumentsContract.Document.FLAG_VIRTUAL_DOCUMENT != 0) return ObsidianRead.Unavailable
            if (before.modified != entry.modifiedMs || before.size != entry.size) return ObsidianRead.Unavailable
            if (before.size != null && before.size > OBSIDIAN_MAX_TEXT_BYTES) return ObsidianRead.TooLarge
            val descriptor = resolver.openFileDescriptor(uri, "r", signal) ?: return ObsidianRead.Unavailable
            val first = Os.fstat(descriptor.fileDescriptor)
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                val out = ByteArrayOutputStream()
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        signal.throwIfCanceled()
                        val n = input.read(buffer)
                        if (n < 0) break
                        if (out.size() + n > OBSIDIAN_MAX_TEXT_BYTES) return ObsidianRead.TooLarge
                        out.write(buffer, 0, n)
                    }
                    val last = Os.fstat(descriptor.fileDescriptor)
                    val after = metadata(uri, signal)
                    if (before != after || first.st_size != last.st_size || first.st_mtime != last.st_mtime ||
                        first.st_ino != last.st_ino || (after.size != null && after.size != out.size().toLong()) ||
                        first.st_size != out.size().toLong()) return ObsidianRead.Unavailable
                val text = out.toByteArray().toString(Charsets.UTF_8)
                if (text.toByteArray(Charsets.UTF_8).size > OBSIDIAN_MAX_TEXT_BYTES) return ObsidianRead.TooLarge
                // ext4/emulated external storage supplies a stable local inode on this device;
                // virtual/pipe descriptors and providers without a meaningful inode supply none.
                ObsidianRead.Stable(text, if (first.st_ino > 0) "${first.st_dev}:${first.st_ino}" else null)
            }
        } catch (cancelled: android.os.OperationCanceledException) { throw cancelled }
        catch (_: java.io.FileNotFoundException) { ObsidianRead.Unavailable }
        catch (_: SecurityException) { ObsidianRead.Unavailable }
        catch (_: java.io.IOException) { ObsidianRead.Unavailable }
        catch (_: android.system.ErrnoException) { ObsidianRead.Unavailable }
    }
    private data class Document(val id: String, val name: String, val directory: Boolean, val modified: Long?, val size: Long?, val flags: Int)
    private fun row(c: Cursor) = Document(c.getString(0), c.getString(1), c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR,
        if (c.isNull(3)) null else c.getLong(3), if (c.isNull(4)) null else c.getLong(4), c.getInt(5))
    private fun complete(cursor: Cursor) {
        if (cursor.extras.getBoolean(DocumentsContract.EXTRA_LOADING, false) || cursor.extras.containsKey(DocumentsContract.EXTRA_ERROR)) {
            throw VaultAccessException("incomplete_listing")
        }
    }
    private fun list(tree: Uri, id: String, signal: CancellationSignal?): List<Document> = resolver.query(
        DocumentsContract.buildChildDocumentsUriUsingTree(tree, id), columns, null, null, null, signal,
    )?.use { c -> complete(c); buildList { while (c.moveToNext()) { signal?.throwIfCanceled(); add(row(c)) }; complete(c) } }
        ?: throw VaultAccessException("incomplete_listing")
    private fun metadata(uri: Uri, signal: CancellationSignal): Document = resolver.query(uri, columns, null, null, null, signal)?.use { c ->
        complete(c); if (!c.moveToFirst()) throw VaultAccessException("access_lost"); row(c)
    } ?: throw VaultAccessException("access_lost")
}
