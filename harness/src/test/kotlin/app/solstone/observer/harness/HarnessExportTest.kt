// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.harness

import app.solstone.core.sources.MAIN_STREAM
import java.nio.file.Files
import kotlin.io.path.exists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HarnessExportTest {
    @Test
    fun exportCopiesBundleToRetrievableDestination() {
        val root = Files.createTempDirectory("harness-export")
        val spool = root.resolve("spool")
        val external = root.resolve("external")
        val segment = evidenceSegment("main", MAIN_STREAM)
        val source = spool.resolve(segment.day).resolve(segment.stream).resolve(segment.segment)
        Files.createDirectories(source)
        Files.write(source.resolve("manifest"), "manifest".encodeToByteArray())
        Files.write(source.resolve("audio.m4a"), "audio".encodeToByteArray())

        val result = RealBundleExport(spool, external).export(segment)

        assertEquals(2, result.copiedFileCount)
        assertTrue(external.resolve("exports").resolve(segment.day).resolve(segment.stream).resolve(segment.segment).resolve("manifest").exists())
    }
}
