// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.screenshots

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.os.ParcelFileDescriptor
import java.io.ByteArrayOutputStream

data class FolderFile(val documentId: String, val size: Long?, val modifiedMs: Long?)

data class FolderRead(val bytes: ByteArray?, val oversize: Boolean)

/** Reads a folder the owner granted. Never resolves the grant to a raw path, and never filters by name. */
class SafScreenshotFolder(private val resolver: ContentResolver) {
    fun hasReadGrant(uri: Uri): Boolean =
        resolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }

    /**
     * Whether the granted folder is still there. A grant outlives its folder: a moved or renamed
     * folder keeps the grant, and listing it returns nothing, which read as a quiet `on`.
     * A [SecurityException] propagates, so the caller reads it as a lost grant too.
     */
    fun reachable(uri: Uri): Boolean {
        val doc = DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri))
        return try {
            resolver.query(doc, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)
                ?.use { it.moveToFirst() } ?: false
        } catch (e: SecurityException) {
            throw e
        } catch (_: Exception) {
            false
        }
    }

    fun persist(uri: Uri) {
        resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    fun displayName(uri: Uri): String {
        val id = DocumentsContract.getTreeDocumentId(uri)
        val doc = DocumentsContract.buildDocumentUriUsingTree(uri, id)
        return resolver.query(doc, arrayOf("_display_name"), null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) null else cursor.getString(0)
        }?.takeIf { it.isNotBlank() } ?: "Screenshots"
    }

    fun list(uri: Uri): List<FolderFile> {
        val out = mutableListOf<FolderFile>()
        val seen = mutableSetOf<String>()
        fun walk(id: String) {
            if (!seen.add(id)) return
            for (child in children(uri, id)) {
                if (child.directory) walk(child.id) else out += FolderFile(child.id, child.size, child.modified)
            }
        }
        walk(DocumentsContract.getTreeDocumentId(uri))
        return out
    }

    /** [cap] is the most bytes a screenshot upload may carry. A longer file returns its head only. */
    fun read(uri: Uri, documentId: String, cap: Int, head: Int): FolderRead {
        val doc = DocumentsContract.buildDocumentUriUsingTree(uri, documentId)
        val descriptor = resolver.openFileDescriptor(doc, "r") ?: return FolderRead(null, false)
        ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            var oversize = false
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                if (out.size() + n > cap) {
                    oversize = true
                    break
                }
                out.write(buffer, 0, n)
            }
            val bytes = out.toByteArray()
            return if (oversize) FolderRead(bytes.copyOf(minOf(bytes.size, head)), true) else FolderRead(bytes, false)
        }
    }

    private data class Child(val id: String, val directory: Boolean, val size: Long?, val modified: Long?)

    private fun children(tree: Uri, id: String): List<Child> {
        val columns = arrayOf("document_id", "mime_type", "_size", "last_modified")
        val query = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id)
        return resolver.query(query, columns, null, null, null)?.use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        Child(
                            id = cursor.getString(0),
                            directory = cursor.getString(1) == DocumentsContract.Document.MIME_TYPE_DIR,
                            size = if (cursor.isNull(2)) null else cursor.getLong(2),
                            modified = if (cursor.isNull(3)) null else cursor.getLong(3),
                        ),
                    )
                }
            }
        } ?: emptyList()
    }
}
