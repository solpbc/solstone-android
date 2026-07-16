// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.persistence.room

import app.solstone.core.model.BundleManifest
import app.solstone.core.model.QueueState
import app.solstone.core.model.SegmentKey
import app.solstone.core.model.WireKeys
import app.solstone.core.segment.SealedSegment
import app.solstone.core.spool.serializeManifest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class JournalCacheSpoolPathsTest {
    @Test fun acceptsCollisionSuffixedPhysicalLeafWithDifferentLogicalSegment() {
        val fixture = fixture(row(dirSegment = "120000_300__ws1000"))
        val proof = assertIs<SegmentDirectoryProof.Proven>(proveSegmentDirectory(fixture.spool, fixture.row))
        assertNull(proveManifestIdentity(proof, fixture.row))
    }

    @Test fun refusesIdMismatch() = assertStructuralRefusal(row().copy(id = "wrong"), JournalCachePathRefusal.ID_MISMATCH)
    @Test fun refusesSpoolRootTargetingThroughEmptyComponents() = assertStructuralRefusal(row(day = ""), JournalCachePathRefusal.MALFORMED_COMPONENT)
    @Test fun refusesOutOfRootSeparatorTraversal() = assertStructuralRefusal(row(day = "../outside"), JournalCachePathRefusal.MALFORMED_COMPONENT)
    @Test fun refusesSeparatorBearingComponent() = assertStructuralRefusal(row(stream = "audio/other"), JournalCachePathRefusal.MALFORMED_COMPONENT)
    @Test fun refusesEmptyComponent() = assertStructuralRefusal(row(stream = ""), JournalCachePathRefusal.MALFORMED_COMPONENT)
    @Test fun refusesDotComponent() = assertStructuralRefusal(row(dirSegment = "."), JournalCachePathRefusal.MALFORMED_COMPONENT)
    @Test fun refusesDotDotComponent() = assertStructuralRefusal(row(dirSegment = ".."), JournalCachePathRefusal.MALFORMED_COMPONENT)

    @Test fun refusesManifestLogicalSegmentMismatch() {
        val fixture = fixture(row())
        assertEquals(JournalCachePathRefusal.MANIFEST_IDENTITY_MISMATCH, proveManifestIdentity(fixture.proof(), fixture.row.copy(segment = "other")))
    }

    @Test fun refusesManifestDayMismatch() {
        val fixture = fixture(row(), manifestRow = row(day = "20260715"))
        assertEquals(JournalCachePathRefusal.MANIFEST_IDENTITY_MISMATCH, proveManifestIdentity(fixture.proof(), fixture.row))
    }

    @Test fun refusesSymlinkedDayAncestor() {
        val root = Files.createTempDirectory("journal-cache-paths")
        val spool = root.resolve("spool")
        val candidate = row()
        val outside = root.resolve("outside")
        Files.createDirectories(outside.resolve(candidate.stream).resolve(candidate.dirSegment))
        Files.createDirectories(spool)
        Files.createSymbolicLink(spool.resolve(candidate.day), outside)
        assertEquals(JournalCachePathRefusal.SYMLINK, refusal(proveSegmentDirectory(spool, candidate)))
    }

    @Test fun refusesSymlinkedTargetItself() {
        val root = Files.createTempDirectory("journal-cache-paths")
        val spool = root.resolve("spool")
        val candidate = row()
        val parent = spool.resolve(candidate.day).resolve(candidate.stream)
        val outside = root.resolve("real-segment")
        Files.createDirectories(parent)
        Files.createDirectories(outside)
        Files.createSymbolicLink(parent.resolve(candidate.dirSegment), outside)
        assertEquals(JournalCachePathRefusal.SYMLINK, refusal(proveSegmentDirectory(spool, candidate)))
    }

    @Test fun refusesSymlinkedManifest() {
        val fixture = fixture(row(), writeManifest = false)
        val outside = fixture.spool.parent.resolve("outside-manifest")
        Files.write(outside, byteArrayOf(1))
        Files.createSymbolicLink(fixture.directory.resolve("manifest"), outside)
        assertEquals(JournalCachePathRefusal.MISSING_MANIFEST, proveManifestIdentity(fixture.proof(), fixture.row))
    }

    @Test fun refusesMissingDirectory() {
        val root = Files.createTempDirectory("journal-cache-paths")
        val candidate = row()
        assertEquals(JournalCachePathRefusal.MISSING_DIRECTORY, refusal(proveSegmentDirectory(root.resolve("spool"), candidate)))
    }

    @Test fun refusesMissingManifest() {
        val fixture = fixture(row(), writeManifest = false)
        assertEquals(JournalCachePathRefusal.MISSING_MANIFEST, proveManifestIdentity(fixture.proof(), fixture.row))
    }

    private fun assertStructuralRefusal(candidate: SegmentRow, reason: JournalCachePathRefusal) {
        val spool = Files.createTempDirectory("journal-cache-paths").resolve("spool")
        assertEquals(reason, refusal(proveSegmentDirectory(spool, candidate)))
    }

    private fun refusal(proof: SegmentDirectoryProof) = assertIs<SegmentDirectoryProof.Refused>(proof).reason

    private fun fixture(candidate: SegmentRow, manifestRow: SegmentRow = candidate, writeManifest: Boolean = true): Fixture {
        val spool = Files.createTempDirectory("journal-cache-paths").resolve("spool")
        val directory = spool.resolve(candidate.day).resolve(candidate.stream).resolve(candidate.dirSegment)
        Files.createDirectories(directory)
        Files.write(directory.resolve("payload.bin"), byteArrayOf(1, 2, 3))
        if (writeManifest) {
            val sealed = sealed(manifestRow)
            Files.write(directory.resolve("manifest"), serializeManifest(sealed, BundleManifest(sealed.key, emptyList(), emptyList())).toByteArray())
        }
        return Fixture(spool, candidate, directory.toAbsolutePath().normalize())
    }

    private fun sealed(row: SegmentRow) = SealedSegment(
        row.stream, SegmentKey(row.day, row.segment), WireKeys(row.day, row.segment, 1_000, 2_000, "UTC", 0), emptyList(), emptyList(),
    )

    private fun row(
        day: String = "20260716",
        stream: String = "audio",
        dirSegment: String = "120000_300",
        segment: String = "120000_300",
    ) = SegmentRow(
        id = "$day/$stream/$dirSegment", day = day, stream = stream, segment = segment, dirSegment = dirSegment,
        state = QueueState.UPLOADED, byteSize = 999, sealedAt = 1_000, homeInstanceId = null, observerHandle = null,
    )

    private data class Fixture(val spool: Path, val row: SegmentRow, val directory: Path) {
        fun proof() = assertIs<SegmentDirectoryProof.Proven>(proveSegmentDirectory(spool, row))
    }
}
