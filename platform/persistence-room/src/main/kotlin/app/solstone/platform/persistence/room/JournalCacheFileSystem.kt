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

sealed interface DirectoryRemovalResult {
    data object ConfirmedAbsent : DirectoryRemovalResult
    data object Incomplete : DirectoryRemovalResult
}

fun interface SpoolDirectoryRemover {
    fun remove(directory: Path): DirectoryRemovalResult
}

internal class NioSpoolDirectoryRemover : SpoolDirectoryRemover {
    override fun remove(directory: Path): DirectoryRemovalResult {
        if (!Files.exists(directory, NOFOLLOW_LINKS)) return DirectoryRemovalResult.ConfirmedAbsent
        return try {
            Files.walkFileTree(directory, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    require(!attrs.isSymbolicLink && !Files.isSymbolicLink(dir)) { "refusing symbolic link" }
                    return FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    require(!attrs.isSymbolicLink && !Files.isSymbolicLink(file)) { "refusing symbolic link" }
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
            if (Files.exists(directory, NOFOLLOW_LINKS)) DirectoryRemovalResult.Incomplete else DirectoryRemovalResult.ConfirmedAbsent
        } catch (_: Exception) {
            DirectoryRemovalResult.Incomplete
        }
    }
}
