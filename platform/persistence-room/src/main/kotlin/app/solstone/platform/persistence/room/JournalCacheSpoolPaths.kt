// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.persistence.room

import app.solstone.core.spool.ParsedManifest
import app.solstone.core.spool.parseManifest
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.nio.charset.StandardCharsets

enum class JournalCachePathRefusal {
    MALFORMED_COMPONENT,
    ID_MISMATCH,
    SYMLINK,
    MISSING_DIRECTORY,
    MISSING_MANIFEST,
    INVALID_MANIFEST,
    MANIFEST_IDENTITY_MISMATCH,
}

sealed interface SegmentDirectoryProof {
    data class Proven(val path: Path) : SegmentDirectoryProof
    data class Refused(val reason: JournalCachePathRefusal) : SegmentDirectoryProof
}

internal fun proveSegmentDirectory(spoolRoot: Path, row: SegmentRow): SegmentDirectoryProof {
    if (!safeComponent(row.day) || !safeComponent(row.stream) || !safeComponent(row.dirSegment)) {
        return SegmentDirectoryProof.Refused(JournalCachePathRefusal.MALFORMED_COMPONENT)
    }
    if (row.id != "${row.day}/${row.stream}/${row.dirSegment}") {
        return SegmentDirectoryProof.Refused(JournalCachePathRefusal.ID_MISMATCH)
    }

    val root = spoolRoot.toAbsolutePath().normalize()
    if (Files.exists(root, NOFOLLOW_LINKS) && Files.isSymbolicLink(root)) {
        return SegmentDirectoryProof.Refused(JournalCachePathRefusal.SYMLINK)
    }
    val target = root.resolve(row.day).resolve(row.stream).resolve(row.dirSegment).normalize()

    var current: Path? = root
    val relative = root.relativize(target)
    for (component in relative) {
        current = requireNotNull(current).resolve(component)
        if (Files.exists(current, NOFOLLOW_LINKS) && Files.isSymbolicLink(current)) {
            return SegmentDirectoryProof.Refused(JournalCachePathRefusal.SYMLINK)
        }
    }
    if (!Files.exists(target, NOFOLLOW_LINKS) || !Files.isDirectory(target, NOFOLLOW_LINKS)) {
        return SegmentDirectoryProof.Refused(JournalCachePathRefusal.MISSING_DIRECTORY)
    }
    return SegmentDirectoryProof.Proven(target)
}

internal fun proveManifestIdentity(proof: SegmentDirectoryProof.Proven, row: SegmentRow): JournalCachePathRefusal? {
    val manifest = proof.path.resolve("manifest")
    if (!Files.exists(manifest, NOFOLLOW_LINKS) || Files.isSymbolicLink(manifest)) {
        return JournalCachePathRefusal.MISSING_MANIFEST
    }
    val attributes = runCatching {
        Files.readAttributes(manifest, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
    }.getOrNull() ?: return JournalCachePathRefusal.INVALID_MANIFEST
    if (!attributes.isRegularFile) return JournalCachePathRefusal.INVALID_MANIFEST
    val parsed: ParsedManifest = runCatching {
        parseManifest(String(Files.readAllBytes(manifest), StandardCharsets.UTF_8))
    }.getOrNull()
        ?: return JournalCachePathRefusal.INVALID_MANIFEST
    return if (parsed.manifest.key.day == row.day && parsed.manifest.key.segment == row.segment) {
        null
    } else {
        JournalCachePathRefusal.MANIFEST_IDENTITY_MISMATCH
    }
}

// This makes traversal and root escape unexpressible under root.resolve(day).resolve(stream).resolve(dirSegment).
private fun safeComponent(value: String): Boolean =
    value.isNotEmpty() && value != "." && value != ".." && '/' !in value && '\\' !in value
