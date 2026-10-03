// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.spool

import java.io.FileOutputStream
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

interface UnresolvedInterruption {
    fun markUnresolved(): Boolean
    fun isUnresolved(): Boolean
}

class UnresolvedInterruptionStore(
    private val file: Path,
    private val syncDirectory: (Path) -> Unit = ::forceDirectory,
) : UnresolvedInterruption {
    override fun markUnresolved(): Boolean {
        return try {
            val parent = file.parent ?: return false
            Files.createDirectories(parent)
            if (isUnresolved()) {
                FileOutputStream(file.toFile(), true).use { it.fd.sync() }
                syncDirectory(parent)
                return true
            }
            val temp = Files.createTempFile(parent, "interruption-", ".tmp")
            try {
                FileOutputStream(temp.toFile()).use { fos ->
                    fos.write(UNRESOLVED_BYTES)
                    fos.fd.sync()
                }
                try {
                    Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE)
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING)
                }
                syncDirectory(parent)
                true
            } finally {
                Files.deleteIfExists(temp)
            }
        } catch (_: Exception) {
            false
        }
    }

    override fun isUnresolved(): Boolean {
        return try {
            !Files.notExists(file)
        } catch (_: Exception) {
            true
        }
    }

    private companion object {
        const val UNRESOLVED_TOKEN = "unresolved"
        val UNRESOLVED_BYTES = UNRESOLVED_TOKEN.toByteArray(StandardCharsets.UTF_8)

        fun forceDirectory(dir: Path) {
            val interrupted = Thread.interrupted()
            try {
                FileChannel.open(dir, StandardOpenOption.READ).use { it.force(true) }
            } finally {
                if (interrupted) Thread.currentThread().interrupt()
            }
        }
    }
}
