// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.folders

import android.content.Context
import android.net.Uri
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import app.solstone.core.folders.FileExistence
import app.solstone.core.folders.FolderFileSystem
import app.solstone.core.folders.PlatformMetadata
import app.solstone.core.folders.WalkOutcome
import app.solstone.core.folders.WalkedFile
import app.solstone.core.folders.extensionLower
import app.solstone.core.folders.isJunkEntry
import app.solstone.core.folders.isPartialDownload
import app.solstone.core.folders.isValidRelativePath
import app.solstone.core.screenshots.SCREENSHOT_HEAD_BYTES
import app.solstone.core.screenshots.SCREENSHOT_MAX_BYTES
import app.solstone.core.screenshots.ShotRead
import app.solstone.core.screenshots.classifyScreenshot
import app.solstone.core.screenshots.headRulesOutScreenshot
import app.solstone.platform.foldersync.FolderDocument
import app.solstone.platform.foldersync.FolderRead
import app.solstone.platform.foldersync.IncompleteFolderListing
import app.solstone.platform.foldersync.SafFolder
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

data class TreeDescriptor(
    val treeUri: String,
    val displayName: String,
    val label: String,
    val canonicalPath: String?,
)

private val IMAGE_EXTENSIONS = setOf(
    "png", "jpg", "jpeg", "webp", "heic", "heif", "tif", "tiff", "gif", "bmp",
)

/**
 * SAF-backed [FolderFileSystem] built directly on `:platform:folder-sync`'s [SafFolder].
 * Honors complete-or-refused directory walks, skips virtual cloud placeholders,
 * checks screenshot proof with `:core:screenshots`, and streams large files without
 * buffering 64 MiB into heap memory.
 */
class AndroidFolderFileSystem(
    private val saf: SafFolder,
    private val currentSignal: () -> CancellationSignal? = { null },
) : FolderFileSystem {

    override fun walk(treeUri: String): WalkOutcome {
        val uri = runCatching { Uri.parse(treeUri) }.getOrNull() ?: return WalkOutcome.Missing
        if (!saf.hasReadGrant(uri)) return WalkOutcome.PermissionLost
        val signal = currentSignal() ?: CancellationSignal()
        try {
            saf.root(uri, signal) ?: return WalkOutcome.Missing
        } catch (e: OperationCanceledException) {
            throw e
        } catch (_: SecurityException) {
            return WalkOutcome.PermissionLost
        } catch (_: IncompleteFolderListing) {
            return WalkOutcome.Unreadable
        } catch (_: Exception) {
            return WalkOutcome.Unreadable
        }
        var deferredPartials = 0L
        var unreadable = 0L

        val files = try {
            saf.walk(
                tree = uri,
                signal = signal,
                include = { doc ->
                    if (doc.flags and (Document.FLAG_VIRTUAL_DOCUMENT or Document.FLAG_PARTIAL) != 0) {
                        false
                    } else if (isJunkEntry(doc.name)) {
                        false
                    } else if (!doc.directory && isPartialDownload(doc.name)) {
                        deferredPartials++
                        false
                    } else {
                        true
                    }
                },
                file = { doc, pathParts ->
                    val relPath = (pathParts + doc.name).joinToString("/")
                    if (!isValidRelativePath(relPath)) return@walk null
                    val size = doc.file.size
                    val modified = doc.file.modifiedMs
                    if (size == null || size < 0L || modified == null) {
                        unreadable++
                        return@walk null
                    }
                    val ext = extensionLower(doc.name)
                    val isShot = if (ext != null && ext in IMAGE_EXTENSIONS) {
                        detectScreenshot(uri, doc, signal)
                    } else {
                        false
                    }
                    WalkedFile(
                        relPath = relPath,
                        documentId = doc.file.documentId,
                        sizeBytes = size,
                        modifiedMs = modified,
                        metadata = PlatformMetadata(
                            sizeBytes = size,
                            modifiedMs = modified,
                            extension = ext,
                            mimeType = doc.mimeType,
                        ),
                        isScreenshot = isShot,
                    )
                },
            )
        } catch (e: OperationCanceledException) {
            throw e
        } catch (_: SecurityException) {
            return WalkOutcome.PermissionLost
        } catch (_: IncompleteFolderListing) {
            return WalkOutcome.Unreadable
        } catch (_: Exception) {
            return WalkOutcome.Unreadable
        }

        return WalkOutcome.Ok(
            files = files,
            unreadable = unreadable,
            deferredPartials = deferredPartials,
            complete = unreadable == 0L,
        )
    }

    private fun detectScreenshot(
        tree: Uri,
        doc: FolderDocument,
        signal: CancellationSignal,
    ): Boolean {
        val headRead = saf.read(tree, doc.file, signal, cap = SCREENSHOT_HEAD_BYTES, head = SCREENSHOT_HEAD_BYTES)
        return when (headRead) {
            is FolderRead.Stable -> classifyScreenshot(headRead.bytes) is ShotRead.Shot
            is FolderRead.TooLarge -> {
                if (headRulesOutScreenshot(headRead.head)) return false
                if (classifyScreenshot(headRead.head) is ShotRead.Shot) return true
                val size = doc.file.size ?: return false
                if (size > SCREENSHOT_MAX_BYTES) return false
                // PNG tEXt/eXIf metadata can sit past the first 256 KB; read full file only when head did not rule it out.
                val full = saf.read(tree, doc.file, signal, cap = SCREENSHOT_MAX_BYTES.toInt())
                full is FolderRead.Stable && classifyScreenshot(full.bytes) is ShotRead.Shot
            }
            FolderRead.Unavailable -> false
        }
    }

    override fun readBytes(treeUri: String, documentId: String?, relPath: String, maxBytes: Long): ByteArray? {
        val uri = runCatching { Uri.parse(treeUri) }.getOrNull() ?: return null
        val docId = documentId ?: return null
        val signal = currentSignal() ?: CancellationSignal()
        val meta = runCatching { saf.documentMetadata(uri, docId, signal) }.getOrNull() ?: return null
        val cap = maxBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        return when (val r = saf.read(uri, meta.file, signal, cap = cap)) {
            is FolderRead.Stable -> r.bytes
            else -> null
        }
    }

    override fun sha256Hex(treeUri: String, documentId: String?, relPath: String, maxBytes: Long): String? {
        val uri = runCatching { Uri.parse(treeUri) }.getOrNull() ?: return null
        val docId = documentId ?: return null
        val signal = currentSignal()
        val digest = MessageDigest.getInstance("SHA-256")
        val ok = saf.streamBounded(uri, docId, signal, maxBytes) { buf, len ->
            digest.update(buf, 0, len)
        }
        if (!ok) return null
        return digest.digest().toHex()
    }

    /** Copies one SAF document into [targetFile] with fstat stability verification and returns its SHA-256. */
    fun copyToFileAndHash(
        treeUri: String,
        documentId: String?,
        targetFile: File,
        maxBytes: Long,
    ): String? {
        val uri = runCatching { Uri.parse(treeUri) }.getOrNull() ?: return null
        val docId = documentId ?: return null
        val signal = currentSignal()
        val digest = MessageDigest.getInstance("SHA-256")
        val ok = try {
            FileOutputStream(targetFile).use { out ->
                saf.streamBounded(uri, docId, signal, maxBytes) { buf, len ->
                    out.write(buf, 0, len)
                    digest.update(buf, 0, len)
                }
            }
        } catch (_: Exception) {
            false
        }
        if (!ok) {
            targetFile.delete()
            return null
        }
        return digest.digest().toHex()
    }

    override fun stat(treeUri: String, documentId: String?, relPath: String): PlatformMetadata? {
        val uri = runCatching { Uri.parse(treeUri) }.getOrNull() ?: return null
        val docId = documentId ?: return null
        val doc = runCatching { saf.documentMetadata(uri, docId, currentSignal()) }.getOrNull() ?: return null
        val size = doc.file.size ?: return null
        val modified = doc.file.modifiedMs ?: return null
        return PlatformMetadata(
            sizeBytes = size,
            modifiedMs = modified,
            extension = extensionLower(doc.name),
            mimeType = doc.mimeType,
        )
    }

    override fun checkExists(treeUri: String, documentId: String?, relPath: String): FileExistence {
        val uri = runCatching { Uri.parse(treeUri) }.getOrNull() ?: return FileExistence.Unknown
        val docId = documentId ?: return FileExistence.Unknown
        if (!saf.hasReadGrant(uri)) return FileExistence.Unknown
        val signal = currentSignal()
        val rootOk = runCatching { saf.root(uri, signal) != null }.getOrDefault(false)
        if (!rootOk) return FileExistence.Unknown
        return try {
            val doc = saf.documentMetadata(uri, docId, signal)
            when {
                doc == null || doc.directory -> FileExistence.Missing
                doc.flags and (Document.FLAG_VIRTUAL_DOCUMENT or Document.FLAG_PARTIAL) != 0 -> FileExistence.Unknown
                else -> FileExistence.Present
            }
        } catch (_: Exception) {
            FileExistence.Unknown
        }
    }

    fun describeTree(treeUri: Uri): TreeDescriptor? {
        val signal = currentSignal()
        val rootDoc = runCatching { saf.root(treeUri, signal) }.getOrNull() ?: return null
        val treeDocId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
        val canonicalPath = canonicalPathFor(treeUri, treeDocId)
        val displayName = rootDoc.name.trim().ifBlank {
            treeDocId?.substringAfterLast(':')?.substringAfterLast('/')?.ifBlank { "folder" } ?: "folder"
        }
        val label = friendlyLabelFor(treeUri, treeDocId, displayName)
        return TreeDescriptor(
            treeUri = treeUri.toString(),
            displayName = displayName,
            label = label,
            canonicalPath = canonicalPath,
        )
    }

    companion object {
        fun canonicalPathFor(treeUri: Uri, treeDocId: String? = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()): String? {
            if (treeDocId.isNullOrBlank()) return null
            val authority = treeUri.authority.orEmpty()
            return if (authority == "com.android.externalstorage.documents") {
                treeDocId.trimEnd('/')
            } else {
                "$authority/$treeDocId".trimEnd('/')
            }
        }

        fun friendlyLabelFor(treeUri: Uri, treeDocId: String?, fallbackName: String): String {
            if (treeUri.authority == "com.android.externalstorage.documents" && !treeDocId.isNullOrBlank()) {
                val rel = treeDocId.substringAfter(':', "").trim('/')
                if (rel.isNotEmpty()) {
                    return rel.split('/').filter(String::isNotBlank).joinToString(" › ")
                }
            }
            return fallbackName
        }

        private fun ByteArray.toHex(): String {
            val digits = "0123456789abcdef"
            val out = CharArray(size * 2)
            for (i in indices) {
                val v = this[i].toInt() and 0xFF
                out[i * 2] = digits[v ushr 4]
                out[i * 2 + 1] = digits[v and 0x0F]
            }
            return String(out)
        }
    }
}
