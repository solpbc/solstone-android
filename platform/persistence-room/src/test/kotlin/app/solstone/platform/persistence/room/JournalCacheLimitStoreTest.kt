// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.persistence.room

import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class JournalCacheLimitStoreTest {
    @Test
    fun absentAndCorruptValuesFallBackAtConstruction() {
        val root = Files.createTempDirectory("journal-cache-limit")
        val file = root.resolve("limit").toFile()
        assertEquals(JournalCacheSnapshot(DEFAULT_JOURNAL_CACHE_LIMIT_BYTES, JournalCacheLimitFallback.ABSENT), JournalCacheLimitStore(file).snapshot())

        file.writeText("broken")
        assertEquals(JournalCacheSnapshot(DEFAULT_JOURNAL_CACHE_LIMIT_BYTES, JournalCacheLimitFallback.CORRUPT), JournalCacheLimitStore(file).snapshot())
    }

    @Test
    fun acceptsOnlyFixedDecimalGbValuesAndReopensSavedValue() {
        val file = Files.createTempDirectory("journal-cache-limit").resolve("limit").toFile()
        val store = JournalCacheLimitStore(file)
        listOf(1L, 2L, 4L, 8L, 16L, 32L).forEach { gb ->
            assertIs<JournalCacheLimitSaveResult.Saved>(store.save(gb * 1_000_000_000L))
        }
        assertEquals(32_000_000_000L, JournalCacheLimitStore(file).snapshot().configuredLimitBytes)

        assertIs<JournalCacheLimitSaveResult.Rejected>(store.save(3_000_000_000L))
        assertEquals(32_000_000_000L, store.snapshot().configuredLimitBytes)
        assertEquals(32_000_000_000L, JournalCacheLimitStore(file).snapshot().configuredLimitBytes)
    }

    @Test
    fun realDurableWriteFailurePreservesPriorValue() {
        val root = Files.createTempDirectory("journal-cache-limit")
        val file = root.resolve("limit").toFile()
        val store = JournalCacheLimitStore(file)
        assertIs<JournalCacheLimitSaveResult.Saved>(store.save(8_000_000_000L))

        val original = Files.getPosixFilePermissions(root)
        Files.setPosixFilePermissions(root, setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE))
        val injectionWorked = runCatching { Files.write(root.resolve("must-fail"), byteArrayOf(1)) }.isFailure
        assertTrue(injectionWorked, "chmod did not make the parent unwritable; durable failure was not injected")
        try {
            assertIs<JournalCacheLimitSaveResult.Failed>(store.save(16_000_000_000L))
            assertEquals(8_000_000_000L, store.snapshot().configuredLimitBytes)
        } finally {
            Files.setPosixFilePermissions(root, original)
        }
        assertEquals(8_000_000_000L, JournalCacheLimitStore(file).snapshot().configuredLimitBytes)
        assertTrue(file.isFile)
    }
}
