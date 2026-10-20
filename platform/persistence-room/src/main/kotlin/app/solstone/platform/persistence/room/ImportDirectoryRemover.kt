// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.persistence.room

import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

fun interface ImportDirectoryRemover {
    fun remove(directory: Path): Boolean
}

internal class NioImportDirectoryRemover : ImportDirectoryRemover {
    override fun remove(directory: Path): Boolean {
        if (!Files.exists(directory, NOFOLLOW_LINKS)) return true
        return try {
            // A symlink is visited as a file; deleting it removes the link and never follows it into target.
            Files.walkFileTree(directory, object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.delete(file)
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = throw exc

                override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                    if (exc != null) throw exc
                    Files.delete(dir)
                    return FileVisitResult.CONTINUE
                }
            })
            !Files.exists(directory, NOFOLLOW_LINKS)
        } catch (_: Exception) {
            false
        }
    }
}
