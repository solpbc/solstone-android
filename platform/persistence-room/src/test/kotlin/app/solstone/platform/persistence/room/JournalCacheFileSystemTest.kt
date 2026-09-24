// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.persistence.room

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class JournalCacheFileSystemTest {
    @Test
    fun realSymlinkRefusesRemovalAndNeverFollowsIt() {
        val root = Files.createTempDirectory("journal-cache-fs")
        val spool = root.resolve("spool")
        val segment = spool.resolve("20260716/audio/one")
        val outside = root.resolve("outside")
        Files.createDirectories(segment)
        Files.createDirectories(outside)
        Files.write(outside.resolve("owner"), byteArrayOf(1))
        Files.createSymbolicLink(segment.resolve("link"), outside)

        assertEquals(DirectoryRemovalResult.Incomplete, NioSpoolDirectoryRemover().remove(segment))
        assertFalse(Files.notExists(outside.resolve("owner")))
    }
}
