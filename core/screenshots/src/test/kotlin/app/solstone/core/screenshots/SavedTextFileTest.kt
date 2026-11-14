// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.screenshots

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SavedTextFileTest {
    @Test
    fun unchangedTextIsNotWrittenAgainButAChangeIs() {
        val dir = Files.createTempDirectory("saved-text-file").toFile()
        val target = File(dir, "screenshots/book.txt")
        val saved = SavedTextFile(target)
        assertEquals(null, saved.read())

        assertTrue(saved.replace("one\n"))
        val key = Files.readAttributes(target.toPath(), BasicFileAttributes::class.java).fileKey()
        val writes = (1..100).count { saved.replace("one\n") }
        assertEquals(0, writes)
        assertEquals(key, Files.readAttributes(target.toPath(), BasicFileAttributes::class.java).fileKey())

        assertTrue(saved.replace("two\n"))
        assertEquals("two\n", target.readText())

        // A fresh reader starts from what is on disk, so reopening does not force a rewrite.
        val reopened = SavedTextFile(target)
        assertEquals("two\n", reopened.read())
        assertFalse(reopened.replace("two\n"))
        assertFalse(File(dir, "screenshots/book.txt.tmp").exists())
    }
}
