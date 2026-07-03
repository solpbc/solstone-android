// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.identity

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.attribute.PosixFilePermission.OWNER_READ
import java.nio.file.attribute.PosixFilePermission.OWNER_WRITE
import java.nio.file.attribute.PosixFilePermissions

fun atomicWriteOwnerOnly(target: File, bytes: ByteArray) {
    val parent = target.parentFile ?: requireNotNull(target.absoluteFile.parentFile)
    parent.mkdirs()
    var temp: Path? = null
    try {
        temp = Files.createTempFile(
            parent.toPath(),
            "sec",
            ".tmp",
            PosixFilePermissions.asFileAttribute(setOf(OWNER_READ, OWNER_WRITE)),
        )
        Files.write(temp, bytes)
        try {
            Files.move(temp, target.toPath(), REPLACE_EXISTING, ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temp, target.toPath(), REPLACE_EXISTING)
        }
    } catch (e: Exception) {
        temp?.let { Files.deleteIfExists(it) }
        throw e
    }
}
