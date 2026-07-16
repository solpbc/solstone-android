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

data class SpoolUsageMeasurement(
    val totalBytes: Long,
    val sealedDirectoryBytes: Map<Path, Long>,
)

fun interface SpoolUsageMeasurer {
    fun measure(spoolRoot: Path): SpoolUsageMeasurement
}

fun interface SpoolFreeSpaceProvider {
    fun usableBytes(spoolRoot: Path): Long
}

sealed interface DirectoryRemovalResult {
    data object ConfirmedAbsent : DirectoryRemovalResult
    data object Incomplete : DirectoryRemovalResult
}

fun interface SpoolDirectoryRemover {
    fun remove(directory: Path): DirectoryRemovalResult
}

internal class NioSpoolUsageMeasurer : SpoolUsageMeasurer {
    override fun measure(spoolRoot: Path): SpoolUsageMeasurement {
        val root = spoolRoot.toAbsolutePath().normalize()
        if (!Files.exists(root, NOFOLLOW_LINKS)) return SpoolUsageMeasurement(0L, emptyMap())
        require(!Files.isSymbolicLink(root)) { "spool root must not be a symbolic link" }
        var total = 0L
        val directoryBytes = mutableMapOf<Path, Long>()
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                require(!attrs.isSymbolicLink) { "spool traversal encountered a symbolic link" }
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                require(!attrs.isSymbolicLink && !Files.isSymbolicLink(file)) {
                    "spool traversal encountered a symbolic link"
                }
                if (attrs.isRegularFile) {
                    // Cache usage is logical regular-file bytes, matching the repository's byteSize semantics.
                    val size = Files.readAttributes(file, BasicFileAttributes::class.java, NOFOLLOW_LINKS).size()
                    require(size >= 0L) { "regular file reported a negative size" }
                    total = Math.addExact(total, size)
                    val relative = root.relativize(file)
                    if (relative.nameCount >= 4 && relative.getName(0).toString() != ".draft") {
                        val segmentDir = root.resolve(relative.subpath(0, 3)).normalize()
                        directoryBytes[segmentDir] = Math.addExact(directoryBytes[segmentDir] ?: 0L, size)
                    }
                }
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: IOException): FileVisitResult = throw exc
        })
        return SpoolUsageMeasurement(total, directoryBytes)
    }
}

internal class FileStoreFreeSpaceProvider : SpoolFreeSpaceProvider {
    override fun usableBytes(spoolRoot: Path): Long {
        val existing = generateSequence(spoolRoot.toAbsolutePath().normalize()) { it.parent }
            .firstOrNull { Files.exists(it, NOFOLLOW_LINKS) }
            ?: throw IOException("no existing filesystem ancestor for spool")
        return Files.getFileStore(existing).usableSpace
    }
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
