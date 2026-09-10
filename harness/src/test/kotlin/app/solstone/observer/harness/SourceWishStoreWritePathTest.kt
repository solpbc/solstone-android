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
}
