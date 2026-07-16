// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.persistence.room

import java.io.File
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.WRITE

const val DEFAULT_JOURNAL_CACHE_LIMIT_BYTES = 4_000_000_000L
const val JOURNAL_CACHE_FREE_SPACE_FLOOR_BYTES = 2_000_000_000L

private val validJournalCacheLimits = setOf(1L, 2L, 4L, 8L, 16L, 32L).mapTo(mutableSetOf()) {
    it * 1_000_000_000L
}

enum class JournalCacheLimitFallback { ABSENT, CORRUPT }

data class JournalCacheSnapshot(
    val configuredLimitBytes: Long,
    val limitFallback: JournalCacheLimitFallback?,
)

sealed interface JournalCacheLimitSaveResult {
    data class Saved(val snapshot: JournalCacheSnapshot) : JournalCacheLimitSaveResult
    data class Rejected(val attemptedBytes: Long) : JournalCacheLimitSaveResult
    data class Failed(val attemptedBytes: Long) : JournalCacheLimitSaveResult
}

class JournalCacheLimitStore(private val file: File) {
    private var current = loadSnapshot(file)

    fun snapshot(): JournalCacheSnapshot = current

    fun save(limitBytes: Long): JournalCacheLimitSaveResult {
        if (limitBytes !in validJournalCacheLimits) return JournalCacheLimitSaveResult.Rejected(limitBytes)
        return try {
            atomicWrite(file, "$limitBytes\n".toByteArray(StandardCharsets.UTF_8))
            current = JournalCacheSnapshot(limitBytes, null)
            JournalCacheLimitSaveResult.Saved(current)
        } catch (_: Exception) {
            JournalCacheLimitSaveResult.Failed(limitBytes)
        }
    }

    private companion object {
        fun loadSnapshot(file: File): JournalCacheSnapshot {
            if (!file.exists()) return JournalCacheSnapshot(DEFAULT_JOURNAL_CACHE_LIMIT_BYTES, JournalCacheLimitFallback.ABSENT)
            val value = runCatching { file.readText(Charsets.UTF_8).trim().toLong() }.getOrNull()
            return if (value in validJournalCacheLimits) {
                JournalCacheSnapshot(requireNotNull(value), null)
            } else {
                JournalCacheSnapshot(DEFAULT_JOURNAL_CACHE_LIMIT_BYTES, JournalCacheLimitFallback.CORRUPT)
            }
        }

        fun atomicWrite(target: File, bytes: ByteArray) {
            val parent = target.absoluteFile.parentFile ?: error("cache limit path has no parent")
            Files.createDirectories(parent.toPath())
            var temp: Path? = Files.createTempFile(parent.toPath(), "journal-cache-limit", ".tmp")
            try {
                Files.write(requireNotNull(temp), bytes)
                FileChannel.open(requireNotNull(temp), WRITE).use { it.force(true) }
                try {
                    Files.move(requireNotNull(temp), target.toPath(), REPLACE_EXISTING, ATOMIC_MOVE)
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(requireNotNull(temp), target.toPath(), REPLACE_EXISTING)
                }
                temp = null
            } finally {
                temp?.let { Files.deleteIfExists(it) }
            }
        }
    }
}
