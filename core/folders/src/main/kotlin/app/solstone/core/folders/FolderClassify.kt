// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.folders

import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.util.Locale

private val PARTIAL_SUFFIXES = listOf(
    ".crdownload",
    ".part",
    ".download",
    ".opdownload",
    ".filepart",
    ".tmp",
)

private val JUNK_NAMES = setOf(
    "thumbs.db",
    "desktop.ini",
    "ehthumbs.db",
    ".ds_store",
)

/** Whether a filename looks like an in-progress download or temporary save that must wait. */
fun isPartialDownload(fileName: String): Boolean {
    val lower = fileName.lowercase(Locale.ROOT)
    return PARTIAL_SUFFIXES.any { lower.endsWith(it) }
}

/** Whether a directory entry is a hidden dot entry, OS junk, or temporary office lock file. */
fun isJunkEntry(fileName: String): Boolean {
    if (fileName.startsWith('.') || fileName.startsWith("~$") || fileName.endsWith('~')) {
        return true
    }
    return fileName.lowercase(Locale.ROOT) in JUNK_NAMES
}

fun extensionLower(name: String): String? {
    val base = name.substringAfterLast('/')
    val dot = base.lastIndexOf('.')
    if (dot <= 0 || dot == base.lastIndex) return null
    val ext = base.substring(dot + 1).lowercase(Locale.ROOT)
    if (ext.isEmpty() || ext.length > 64 || !ext.all { it in 'a'..'z' || it in '0'..'9' }) return null
    return ext
}

fun isValidRelativePath(relPath: String): Boolean {
    if (relPath.isEmpty() || relPath.toByteArray(Charsets.UTF_8).size > 1024) return false
    if (relPath.startsWith('/') || relPath.endsWith('/') || '\\' in relPath) return false
    if (relPath.any { it.code < 0x20 || it.code == 0x7F }) return false
    val segments = relPath.split('/')
    return segments.none { it.isEmpty() || it == "." || it == ".." }
}

fun decodeStrictUtf8(bytes: ByteArray): String? {
    if (bytes.any { it == 0.toByte() }) return null
    return try {
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
    } catch (_: Exception) {
        null
    }
}

/**
 * Matches journal triage ruleset v8 text document thresholds:
 * - `long_prose`: >= 5,000 words with >= 70% prose lines
 * - `structured_prose`: >= 1,500 words with >= 70% prose lines and >= 2 distinct heading levels
 * - or size > `maxNoteBytes` (4 MiB)
 */
fun classifyTextFile(bytes: ByteArray, supported: SupportedList): FileKind {
    if (bytes.size.toLong() > supported.maxNoteBytes) return FileKind.DOCUMENT
    val text = decodeStrictUtf8(bytes) ?: return FileKind.OTHER
    var totalWords = 0
    var proseLines = 0
    var nonEmptyLines = 0
    val headingLevels = LinkedHashSet<Int>()
    var inFence = false

    for (rawLine in text.lineSequence()) {
        val line = rawLine.trim()
        if (line.isEmpty()) continue
        if (line.startsWith("```") || line.startsWith("~~~")) {
            inFence = !inFence
            nonEmptyLines++
            continue
        }
        nonEmptyLines++
        if (inFence) continue

        var hashes = 0
        while (hashes < line.length && line[hashes] == '#') hashes++
        if (hashes in 1..6 && hashes < line.length && line[hashes] == ' ') {
            headingLevels.add(hashes)
            val rest = line.substring(hashes + 1).trim()
            totalWords += countWords(rest)
            proseLines++
            continue
        }

        val words = countWords(line)
        totalWords += words
        val isListOrTable = line.startsWith("- ") || line.startsWith("* ") || line.startsWith("|") || line.startsWith("> ")
        if (!isListOrTable || words >= 6) {
            proseLines++
        }
    }

    val proseOk = nonEmptyLines > 0 && proseLines * 100 >= nonEmptyLines * 70
    val longProse = proseOk && totalWords >= supported.noteLongWords
    val structuredProse = proseOk &&
        totalWords >= supported.noteStructuredWords &&
        headingLevels.size >= supported.noteHeadingLevels

    return if (longProse || structuredProse) FileKind.DOCUMENT else FileKind.NOTE
}

private fun countWords(text: String): Int {
    var words = 0
    var inWord = false
    for (ch in text) {
        if (ch.isWhitespace()) {
            inWord = false
        } else if (!inWord) {
            words++
            inWord = true
        }
    }
    return words
}

/** Deterministic sorting table matching protocol v3 / triage v8. */
fun classifyFile(
    extension: String?,
    sizeBytes: Long,
    isScreenshot: Boolean,
    noteBytes: ByteArray?,
    kinds: FolderKinds,
    supported: SupportedList,
): Pair<FileKind, DeliveryMode> {
    val ext = extension?.lowercase(Locale.ROOT).orEmpty()
    val isImage = ext in supported.acceptedImageExtensions || ext in supported.convertibleImageExtensions

    val fileKind = when {
        isScreenshot && isImage -> FileKind.SCREENSHOT
        ext in supported.transcriptExtensions && transcriptLayout(ext, noteBytes) -> FileKind.TRANSCRIPT
        ext in supported.noteExtensions -> when {
            sizeBytes > supported.maxNoteBytes -> FileKind.DOCUMENT
            noteBytes != null -> classifyTextFile(noteBytes, supported)
            else -> FileKind.NOTE
        }
        ext in supported.documentExtensions -> FileKind.DOCUMENT
        isImage -> FileKind.PHOTO
        else -> FileKind.OTHER
    }

    val withinSize = sizeBytes <= supported.maxContentBytes
    val delivery = when {
        fileKind == FileKind.OTHER -> if (kinds.other) DeliveryMode.METADATA_ONLY else DeliveryMode.IGNORE
        kinds.enabledFor(fileKind) && withinSize -> DeliveryMode.CONTENT
        kinds.other -> DeliveryMode.METADATA_ONLY
        else -> DeliveryMode.IGNORE
    }
    return fileKind to delivery
}

fun needsClassificationBytes(extension: String?, supported: SupportedList): Boolean {
    val ext = extension?.lowercase(Locale.ROOT) ?: return false
    return ext in supported.noteExtensions || (ext in supported.transcriptExtensions && ext != "vtt" && ext != "srt")
}

fun sha256Hex(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    val hex = CharArray(digest.size * 2)
    val digits = "0123456789abcdef"
    for (i in digest.indices) {
        val v = digest[i].toInt() and 0xFF
        hex[i * 2] = digits[v ushr 4]
        hex[i * 2 + 1] = digits[v and 0x0F]
    }
    return String(hex)
}

/** Deterministic client-assigned folder ID matching `^[a-z0-9][a-z0-9_-]{0,63}$`. */
fun makeFolderId(name: String, stableKey: String): String {
    val hash = sha256Hex(stableKey.toByteArray(Charsets.UTF_8)).substring(0, 8)
    val slug = StringBuilder()
    var lastDash = false
    for (ch in name) {
        if (ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9') {
            slug.append(ch.lowercaseChar())
            lastDash = false
        } else if ((ch == '-' || ch == '_' || ch == ' ') && slug.isNotEmpty() && !lastDash) {
            slug.append('-')
            lastDash = true
        }
        if (slug.length >= 40) break
    }
    val cleaned = slug.toString().trim('-')
    return if (cleaned.isEmpty()) "folder-$hash" else "$cleaned-$hash"
}

sealed class OverlapOutcome {
    data object None : OverlapOutcome()
    data class AlreadyAdded(val id: String, val name: String) : OverlapOutcome()
    data class Overlap(val target: OverlapTarget) : OverlapOutcome()
}

data class FolderIdentity(
    val id: String,
    val treeUri: String,
    val canonicalPath: String?,
    val name: String,
)

fun normalizePathForOverlap(path: String?): String? {
    val cleaned = path?.trim()?.trimEnd('/') ?: return null
    return cleaned.takeIf { it.isNotEmpty() }
}

fun pathsOverlap(a: String, b: String): Boolean {
    if (a == b) return true
    return a.startsWith("$b/") || b.startsWith("$a/")
}

fun detectOverlap(
    candidateTreeUri: String,
    candidatePath: String?,
    obsidian: FolderIdentity?,
    screenshots: FolderIdentity?,
    existingFolders: List<FolderIdentity>,
): OverlapOutcome {
    val candPath = normalizePathForOverlap(candidatePath)
    for (existing in existingFolders) {
        val exPath = normalizePathForOverlap(existing.canonicalPath)
        if (candidateTreeUri == existing.treeUri || (candPath != null && candPath == exPath)) {
            return OverlapOutcome.AlreadyAdded(existing.id, existing.name)
        }
        if (candPath != null && exPath != null && pathsOverlap(candPath, exPath)) {
            return OverlapOutcome.Overlap(OverlapTarget.Folder(existing.id, existing.name))
        }
    }
    if (obsidian != null) {
        val obPath = normalizePathForOverlap(obsidian.canonicalPath)
        if (candidateTreeUri == obsidian.treeUri || (candPath != null && obPath != null && pathsOverlap(candPath, obPath))) {
            return OverlapOutcome.Overlap(OverlapTarget.Obsidian(obsidian.name))
        }
    }
    if (screenshots != null) {
        val shPath = normalizePathForOverlap(screenshots.canonicalPath)
        if (candidateTreeUri == screenshots.treeUri || (candPath != null && shPath != null && pathsOverlap(candPath, shPath))) {
            return OverlapOutcome.Overlap(OverlapTarget.Screenshots(screenshots.name))
        }
    }
    return OverlapOutcome.None
}
