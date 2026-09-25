// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.harness

import app.solstone.core.sources.EmissionSink
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import app.solstone.core.model.SourceState
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SourceWishStoreWritePathTest {
    @Test
    fun setWishWritesOnlyThatSourceAndTheRebuiltRegistryStartsNeither() {
        val dir = Files.createTempDirectory("source-wishes-write").toFile()
        val file = dir.resolve("source-wishes")
        val audio = FakeSourceEngine()
        val location = FakeSourceEngine()
        val first = sourceRegistry(
            registrations = listOf(
                SourceRegistration("audio", audio),
                SourceRegistration("location", location),
            ),
            wishStore = FileSourceWishStore(file),
        )
        assertIs<SourceToggleResult.Applied>(first.setWish("audio", SourceWish.Off))

        val rebuiltAudio = FakeSourceEngine()
        val rebuiltLocation = FakeSourceEngine()
        val rebuilt = sourceRegistry(
            registrations = listOf(
                SourceRegistration("audio", rebuiltAudio),
                SourceRegistration("location", rebuiltLocation),
            ),
            wishStore = FileSourceWishStore(file),
        )
        // 🔴 The file holds ONE line. This test asserted `location == On` and that its engine
        // started — both of which the old whole-map write satisfied by writing an entry for a source
        // the owner never touched. Asserting the persisted bytes is what makes the leak visible.
        assertEquals("audio\tOff\n", file.readText())

        val snapshot = rebuilt.snapshot()
        val audioRow = snapshot.sources.single { it.sourceId == "audio" }
        val locationRow = snapshot.sources.single { it.sourceId == "location" }
        assertEquals(SourceWish.Off, audioRow.wish)
        assertTrue(audioRow.wishExpressed, "the owner chose this one")
        assertEquals(SourceState.OFF, audioRow.state)
        // ⛔ Not `On`, and ⛔ not `off` either: location was never chosen, so it is neither running
        // nor a choice the owner made.
        assertEquals(SourceWish.Off, locationRow.wish)
        assertFalse(locationRow.wishExpressed, "the owner never touched location")
        assertEquals(SourceState.READY_TO_SET_UP, locationRow.state)
        rebuilt.engines.forEach { it.start(EmissionSink { }) }
        assertEquals(0, rebuiltAudio.startCalls)
        assertEquals(0, rebuiltLocation.startCalls)
    }

    @Test
    fun saveAllClassifiesByteOutcomesCorrectly() {
        val dir = Files.createTempDirectory("source-wishes-classify").toFile()
        val file = dir.resolve("source-wishes")
        file.writeText("audio\tOn\n")

        // 1. Success -> Committed
        val store = FileSourceWishStore(file)
        val res1 = store.saveAll(mapOf("audio" to SourceWish.Off))
        assertEquals(WishSaveOutcome.Committed, res1)
        assertEquals("audio\tOff\n", file.readText())

        // 2. Failure during write/force before move -> OriginalIntact, original file bytes unchanged
        val failingWriteStore = FileSourceWishStore(
            file = file,
            nio = object : FileSourceWishStore.NioOps by FileSourceWishStore.RealNioOps {
                override fun write(path: java.nio.file.Path, bytes: ByteArray) {
                    throw RuntimeException("write failed")
                }
            },
        )
        val res2 = failingWriteStore.saveAll(mapOf("audio" to SourceWish.On))
        assertEquals(WishSaveOutcome.OriginalIntact, res2)
        assertEquals("audio\tOff\n", file.readText())

        val failingForceStore = FileSourceWishStore(
            file = file,
            nio = object : FileSourceWishStore.NioOps by FileSourceWishStore.RealNioOps {
                override fun force(path: java.nio.file.Path) {
                    throw RuntimeException("force failed")
                }
            },
        )
        val res3 = failingForceStore.saveAll(mapOf("audio" to SourceWish.On))
        assertEquals(WishSaveOutcome.OriginalIntact, res3)
        assertEquals("audio\tOff\n", file.readText())

        // 3. moveAtomic throws AtomicMoveNotSupportedException, then moveReplace throws without changing target -> OriginalIntact
        val failingFallbackMoveStore = FileSourceWishStore(
            file = file,
            nio = object : FileSourceWishStore.NioOps by FileSourceWishStore.RealNioOps {
                override fun moveAtomic(source: java.nio.file.Path, target: java.nio.file.Path) {
                    throw java.nio.file.AtomicMoveNotSupportedException("source", "target", "atomic move unsupported")
                }
                override fun moveReplace(source: java.nio.file.Path, target: java.nio.file.Path) {
                    throw RuntimeException("replace move failed")
                }
            },
        )
        val res4 = failingFallbackMoveStore.saveAll(mapOf("audio" to SourceWish.On))
        assertEquals(WishSaveOutcome.OriginalIntact, res4)
        assertEquals("audio\tOff\n", file.readText())

        // 4. Failure during/after move where target file was altered -> Uncertain
        val uncertainStore = FileSourceWishStore(
            file = file,
            nio = object : FileSourceWishStore.NioOps by FileSourceWishStore.RealNioOps {
                override fun moveAtomic(source: java.nio.file.Path, target: java.nio.file.Path) {
                    Files.write(target, byteArrayOf(1, 2, 3))
                    throw RuntimeException("corrupted during move")
                }
                override fun moveReplace(source: java.nio.file.Path, target: java.nio.file.Path) {
                    Files.write(target, byteArrayOf(1, 2, 3))
                    throw RuntimeException("corrupted during move")
                }
            },
        )
        val res5 = uncertainStore.saveAll(mapOf("audio" to SourceWish.On))
        assertEquals(WishSaveOutcome.Uncertain, res5)
    }

    @Test
    fun deletedTargetIsUncertainAfterFailedMove() {
        val dir = Files.createTempDirectory("source-wishes-deleted-target").toFile()
        val file = dir.resolve("source-wishes")
        file.writeText("audio\tOn\n")

        val store = FileSourceWishStore(
            file = file,
            nio = object : FileSourceWishStore.NioOps by FileSourceWishStore.RealNioOps {
                override fun moveAtomic(source: java.nio.file.Path, target: java.nio.file.Path) {
                    Files.delete(target)
                    throw RuntimeException("target disappeared during move")
                }
            },
        )

        assertEquals(WishSaveOutcome.Uncertain, store.saveAll(mapOf("audio" to SourceWish.Off)))
        assertFalse(file.exists())
    }

    @Test
    fun failedSaveRollsBackInMemoryStateAndReturnsNotSaved() {
        val dir = Files.createTempDirectory("source-wishes-rollback").toFile()
        val file = dir.resolve("source-wishes")
        file.writeText("audio\tOn\n")

        val failingStore = FileSourceWishStore(
            file = file,
            nio = object : FileSourceWishStore.NioOps by FileSourceWishStore.RealNioOps {
                override fun moveAtomic(source: java.nio.file.Path, target: java.nio.file.Path) {
                    throw RuntimeException("simulated write failure")
                }
                override fun moveReplace(source: java.nio.file.Path, target: java.nio.file.Path) {
                    throw RuntimeException("simulated write failure")
                }
            },
        )

        val audio = FakeSourceEngine()
        val registry = sourceRegistry(
            registrations = listOf(SourceRegistration("audio", audio)),
            wishStore = failingStore,
        )

        val result = registry.setWish("audio", SourceWish.Off)
        assertIs<SourceToggleResult.NotSaved>(result)
        assertEquals(WishSaveOutcome.OriginalIntact, (result as SourceToggleResult.NotSaved).outcome)

        // Verify in-memory wish was rolled back to On
        val audioRow = registry.snapshot().sources.single { it.sourceId == "audio" }
        assertEquals(SourceWish.On, audioRow.wish)
    }
}
