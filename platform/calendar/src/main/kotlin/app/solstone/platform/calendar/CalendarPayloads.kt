// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc
package app.solstone.platform.calendar

import app.solstone.core.segment.sha256
import java.io.ByteArrayInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import android.system.Os
import android.system.OsConstants

/** Content-addressed private storage keeps calendar bodies out of SQLite cursor windows. */
class CalendarPayloads(private val directory: Path) {
    fun put(bytes: ByteArray): String {
        val id = sha256(ByteArrayInputStream(bytes))
        Files.createDirectories(directory)
        val parent = Os.open(directory.parent.toString(), OsConstants.O_RDONLY, 0)
        try { Os.fsync(parent) } finally { Os.close(parent) }
        val destination = directory.resolve(id)
        if (!Files.exists(destination)) {
            val temporary = directory.resolve(".$id-${UUID.randomUUID()}")
            try {
                FileOutputStream(temporary.toFile()).use { stream -> stream.write(bytes); stream.fd.sync() }
                Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE)
            } finally { Files.deleteIfExists(temporary) }
        }
        val fd = Os.open(directory.toString(), OsConstants.O_RDONLY, 0)
        try { Os.fsync(fd) } finally { Os.close(fd) }
        return id
    }

    fun size(id: String): Long {
        require(id.matches(Regex("[a-f0-9]{64}")))
        return Files.size(directory.resolve(id))
    }

    fun read(id: String): ByteArray {
        require(id.matches(Regex("[a-f0-9]{64}")))
        val bytes = Files.readAllBytes(directory.resolve(id))
        check(sha256(ByteArrayInputStream(bytes)) == id) { "calendar payload checksum" }
        return bytes
    }

    fun prune(referenced: Set<String>) {
        if (!Files.isDirectory(directory)) return
        Files.newDirectoryStream(directory).use { entries ->
            entries.forEach { file -> if (file.fileName.toString() !in referenced) Files.deleteIfExists(file) }
        }
    }
}
