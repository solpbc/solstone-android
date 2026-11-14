// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.screenshots

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * A small text record replaced whole through a temporary file, and only when its text changes:
 * a check that finds nothing new leaves the file as it was.
 */
class SavedTextFile(private val file: File) {
    private var onDisk: String? = null

    fun read(): String? {
        if (!file.isFile) return null
        return runCatching { file.readText() }.getOrNull().also { onDisk = it }
    }

    /** True when the file was replaced, false when it already held [text]. */
    fun replace(text: String): Boolean {
        if (text == onDisk) return false
        val dir = file.parentFile ?: return false
        dir.mkdirs()
        val tmp = dir.resolve("${file.name}.tmp")
        tmp.writeText(text)
        try {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        onDisk = text
        return true
    }
}
