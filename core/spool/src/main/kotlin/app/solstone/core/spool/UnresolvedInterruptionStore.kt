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

class UnresolvedInterruptionStore(private val file: Path) : UnresolvedInterruption {
    override fun markUnresolved(): Boolean {
        return try {
            val parent = file.parent ?: return false
            Files.createDirectories(parent)
            if (isUnresolved()) return true
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
                forceDirectory(parent)
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
            if (!Files.isRegularFile(file)) return false
            val content = String(Files.readAllBytes(file), StandardCharsets.UTF_8).trim()
            content == UNRESOLVED_TOKEN
        } catch (_: Exception) {
            false
        }
    }

    private companion object {
        const val UNRESOLVED_TOKEN = "unresolved"
        val UNRESOLVED_BYTES = UNRESOLVED_TOKEN.toByteArray(StandardCharsets.UTF_8)

        fun forceDirectory(dir: Path) {
            try {
                FileChannel.open(dir, StandardOpenOption.READ).use { channel ->
                    channel.force(true)
                }
            } catch (_: Exception) {
            }
        }
    }
}
