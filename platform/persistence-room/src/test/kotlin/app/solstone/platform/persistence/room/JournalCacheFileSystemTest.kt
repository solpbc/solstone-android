// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.persistence.room

import app.solstone.core.model.BundleManifest
import app.solstone.core.model.SegmentKey
import app.solstone.core.model.WireKeys
import app.solstone.core.segment.SealedSegment
import app.solstone.core.spool.serializeManifest
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse

class JournalCacheFileSystemTest {
    @Test
    fun measuresEveryRegularFileIncludingDraftAndDirectoryBytes() {
        val spool = Files.createTempDirectory("journal-cache-fs").resolve("spool")
        val segment = spool.resolve("20260716/audio/one")
        Files.createDirectories(segment)
        val sealed = SealedSegment("audio", SegmentKey("20260716", "one"), WireKeys("20260716", "one", 1, 2, "UTC", 0), emptyList(), emptyList())
        val manifest = serializeManifest(sealed, BundleManifest(sealed.key, emptyList(), emptyList())).toByteArray()
        Files.write(segment.resolve("manifest"), manifest)
        Files.write(segment.resolve("payload"), ByteArray(11))
        val draft = spool.resolve(".draft/20260716/audio/two")
        Files.createDirectories(draft)
        Files.write(draft.resolve("partial"), ByteArray(13))

        val measured = NioSpoolUsageMeasurer().measure(spool)
        assertEquals(manifest.size.toLong() + 24L, measured.totalBytes)
        assertEquals(manifest.size.toLong() + 11L, measured.sealedDirectoryBytes[segment.toAbsolutePath().normalize()])
    }

    @Test
    fun realSymlinkFailsMeasurementAndRemovalNeverFollowsIt() {
        val root = Files.createTempDirectory("journal-cache-fs")
        val spool = root.resolve("spool")
        val segment = spool.resolve("20260716/audio/one")
        val outside = root.resolve("outside")
        Files.createDirectories(segment)
        Files.createDirectories(outside)
        Files.write(outside.resolve("owner"), byteArrayOf(1))
        Files.createSymbolicLink(segment.resolve("link"), outside)

        assertFails { NioSpoolUsageMeasurer().measure(spool) }
        assertEquals(DirectoryRemovalResult.Incomplete, NioSpoolDirectoryRemover().remove(segment))
        assertFalse(Files.notExists(outside.resolve("owner")))
    }

    @Test
    fun unreadableDirectoryFailsTraversalAfterVerifyingPermissionInjection() {
        val spool = Files.createTempDirectory("journal-cache-fs").resolve("spool")
        val blocked = spool.resolve("20260716/audio/blocked")
        Files.createDirectories(blocked)
        Files.write(blocked.resolve("payload"), byteArrayOf(1))
        val original = Files.getPosixFilePermissions(blocked)
        Files.setPosixFilePermissions(blocked, emptySet<PosixFilePermission>())
        val injectionWorked = runCatching { Files.newDirectoryStream(blocked).use { it.iterator().hasNext() } }.isFailure
        try {
            kotlin.test.assertTrue(injectionWorked, "chmod did not block traversal; failure was not injected")
            assertFails { NioSpoolUsageMeasurer().measure(spool) }
        } finally {
            Files.setPosixFilePermissions(blocked, original)
        }
    }

    @Test
    fun injectedPerFileStatFailureIsObservableAtMeasurementSeam() {
        val statFailure = SpoolUsageMeasurer { throw java.io.IOException("injected per-file stat failure") }
        val failure = assertFails { statFailure.measure(Files.createTempDirectory("journal-cache-stat")) }
        assertEquals("injected per-file stat failure", failure.message)
    }
}
