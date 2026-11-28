// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.foldersync

import android.content.ContentResolver
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.ByteArrayOutputStream
import java.io.IOException

class IncompleteFolderListing : IOException("incomplete_listing")

data class FolderFile(val documentId: String, val size: Long?, val modifiedMs: Long?)
data class FolderDocument(
    val file: FolderFile,
    val name: String,
    val directory: Boolean,
    val flags: Int,
    val mimeType: String? = null,
)

sealed interface FolderRead {
    data class Stable(val bytes: ByteArray, val fileId: String?) : FolderRead
    data class TooLarge(val head: ByteArray) : FolderRead
    data object Unavailable : FolderRead
}

/** Access stays within the owner-granted SAF tree. A failed listing never returns a partial inventory. */
class SafFolder(
    private val resolver: ContentResolver,
    private val hasGrant: (Uri) -> Boolean = { uri ->
        resolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
    },
) {
    fun hasReadGrant(uri: Uri): Boolean = hasGrant(uri)

    fun persist(uri: Uri) {
        resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    fun release(uri: Uri) {
        resolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    fun root(tree: Uri, signal: CancellationSignal? = null): FolderDocument? =
        metadata(tree, DocumentsContract.getTreeDocumentId(tree), signal)

    fun documentMetadata(tree: Uri, id: String, signal: CancellationSignal? = null): FolderDocument? =
        metadata(tree, id, signal)

    /**
     * Streams a document's bytes to [sink] with the same pre/post metadata and fstat stability
     * verification as [read], avoiding loading large files (up to 64 MiB) into JVM heap memory.
     */
    fun streamBounded(
        tree: Uri,
        documentId: String,
        signal: CancellationSignal?,
        maxBytes: Long,
        sink: (ByteArray, Int) -> Unit,
    ): Boolean {
        try {
            signal?.throwIfCanceled()
            val before = metadata(tree, documentId, signal) ?: return false
            if (before.directory || before.flags and Document.FLAG_VIRTUAL_DOCUMENT != 0) return false
            if (before.file.size != null && before.file.size > maxBytes) return false

            val document = DocumentsContract.buildDocumentUriUsingTree(tree, documentId)
            val descriptor = resolver.openFileDescriptor(document, "r", signal) ?: return false
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                val first = Os.fstat(descriptor.fileDescriptor).takeIf { OsConstants.S_ISREG(it.st_mode) }
                val buffer = ByteArray(32 * 1024)
                var total = 0L
                while (true) {
                    signal?.throwIfCanceled()
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count.toLong()
                    if (total > maxBytes) return false
                    if (count > 0) sink(buffer, count)
                }
                val last = first?.let { Os.fstat(descriptor.fileDescriptor) }
                val after = metadata(tree, documentId, signal)
                if (before != after || first?.st_size != last?.st_size || first?.st_mtime != last?.st_mtime ||
                    first?.st_ino != last?.st_ino || first?.st_dev != last?.st_dev ||
                    (before.file.size != null && before.file.size != total) ||
                    (first != null && first.st_size != total)
                ) return false
                return true
            }
        } catch (_: IOException) {
            return false
        } catch (_: ErrnoException) {
            return false
        } catch (_: SecurityException) {
            return false
        }
    }

    fun children(tree: Uri, id: String, signal: CancellationSignal? = null): List<FolderDocument> =
        query(DocumentsContract.buildChildDocumentsUriUsingTree(tree, id), signal) { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    signal?.throwIfCanceled()
                    add(row(cursor))
                }
            }
        }

    fun <T> walk(
        tree: Uri,
        signal: CancellationSignal,
        include: (FolderDocument) -> Boolean = { true },
        file: (FolderDocument, List<String>) -> T?,
    ): List<T> {
        val root = DocumentsContract.getTreeDocumentId(tree)
        val seen = mutableSetOf(root)
        val out = mutableListOf<T>()

        // Keep one iterator and path component per open directory. A deep tree needs no recursion.
        val stack = ArrayDeque<Iterator<FolderDocument>>()
        val path = mutableListOf<String>()
        stack.addLast(children(tree, root, signal).iterator())
        while (stack.isNotEmpty()) {
            signal.throwIfCanceled()
            val entries = stack.last()
            if (!entries.hasNext()) {
                stack.removeLast()
                if (path.isNotEmpty()) path.removeAt(path.lastIndex)
                continue
            }
            val entry = entries.next()
            if (!include(entry)) continue
            if (entry.directory) {
                if (!seen.add(entry.file.documentId)) throw IncompleteFolderListing()
                stack.addLast(children(tree, entry.file.documentId, signal).iterator())
                path.add(entry.name)
            } else {
                file(entry, path)?.let(out::add)
            }
        }
        return out
    }

    /**
     * Compare the listed and current metadata, then make sure neither the provider nor a regular
     * descriptor reports a change during the read. Only local-file sources require a regular file.
     * The optional head lets a source classify an oversize file without keeping its whole body.
     */
    fun read(
        tree: Uri,
        file: FolderFile,
        signal: CancellationSignal,
        cap: Int,
        head: Int = 0,
        requireRegularFile: Boolean = false,
    ): FolderRead {
        try {
            signal.throwIfCanceled()
            val before = metadata(tree, file.documentId, signal) ?: return FolderRead.Unavailable
            if (before.file != file || before.directory ||
                before.flags and Document.FLAG_VIRTUAL_DOCUMENT != 0
            ) return FolderRead.Unavailable
            if (head == 0 && file.size != null && file.size > cap) return FolderRead.TooLarge(byteArrayOf())

            val document = DocumentsContract.buildDocumentUriUsingTree(tree, file.documentId)
            val descriptor = resolver.openFileDescriptor(document, "r", signal) ?: return FolderRead.Unavailable
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                val first = Os.fstat(descriptor.fileDescriptor).takeIf { OsConstants.S_ISREG(it.st_mode) }
                if (requireRegularFile && first == null) return FolderRead.Unavailable
                val bytes = ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                var oversize = false
                while (true) {
                    signal.throwIfCanceled()
                    // Read one byte past the bound to distinguish an exact-length file from a head.
                    val remaining = cap - bytes.size()
                    val count = input.read(buffer, 0, if (remaining < buffer.size) remaining + 1 else buffer.size)
                    if (count < 0) break
                    bytes.write(buffer, 0, minOf(count, remaining))
                    if (count > remaining) {
                        oversize = true
                        break
                    }
                }

                val last = first?.let { Os.fstat(descriptor.fileDescriptor) }
                val after = metadata(tree, file.documentId, signal)
                if (before != after || first?.st_size != last?.st_size || first?.st_mtime != last?.st_mtime ||
                    first?.st_ino != last?.st_ino || first?.st_dev != last?.st_dev ||
                    (file.size != null && file.size.exceedsOrDiffersFrom(bytes.size(), oversize)) ||
                    (first != null && first.st_size.exceedsOrDiffersFrom(bytes.size(), oversize))
                ) return FolderRead.Unavailable

                val content = bytes.toByteArray()
                if (oversize) return FolderRead.TooLarge(if (content.size <= head) content else content.copyOf(head))
                val identity = first?.takeIf { it.st_ino > 0 }?.let { "${it.st_dev}:${it.st_ino}" }
                return FolderRead.Stable(content, identity)
            }
        } catch (_: IOException) {
            return FolderRead.Unavailable
        } catch (_: ErrnoException) {
            return FolderRead.Unavailable
        }
    }

    private fun Long.exceedsOrDiffersFrom(bytesRead: Int, oversize: Boolean): Boolean =
        if (oversize) this <= bytesRead else this != bytesRead.toLong()

    private fun metadata(tree: Uri, id: String, signal: CancellationSignal?): FolderDocument? =
        query(DocumentsContract.buildDocumentUriUsingTree(tree, id), signal) { cursor ->
            if (!cursor.moveToFirst()) return@query null
            val document = row(cursor)
            if (document.file.documentId != id || cursor.moveToNext()) throw IncompleteFolderListing()
            document
        }

    private fun <T> query(uri: Uri, signal: CancellationSignal?, read: (Cursor) -> T): T =
        (resolver.query(uri, columns, null, null, null, signal) ?: throw IncompleteFolderListing()).use { cursor ->
            requireComplete(cursor)
            val result = read(cursor)
            requireComplete(cursor)
            signal?.throwIfCanceled()
            result
        }

    private fun requireComplete(cursor: Cursor) {
        if (cursor.extras.getBoolean(DocumentsContract.EXTRA_LOADING, false) ||
            cursor.extras.containsKey(DocumentsContract.EXTRA_ERROR)
        ) throw IncompleteFolderListing()
    }

    private fun row(cursor: Cursor): FolderDocument {
        val mime = cursor.getString(2)
        return FolderDocument(
            file = FolderFile(
                documentId = cursor.getString(0) ?: throw IncompleteFolderListing(),
                size = if (cursor.isNull(4)) null else cursor.getLong(4),
                modifiedMs = if (cursor.isNull(3)) null else cursor.getLong(3),
            ),
            name = cursor.getString(1).orEmpty(),
            directory = mime == Document.MIME_TYPE_DIR,
            flags = cursor.getInt(5),
            mimeType = mime,
        )
    }

    private companion object {
        val columns = arrayOf(
            Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_LAST_MODIFIED, Document.COLUMN_SIZE, Document.COLUMN_FLAGS,
        )
    }
}
