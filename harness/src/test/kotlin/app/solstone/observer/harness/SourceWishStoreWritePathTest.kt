// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.harness

import app.solstone.core.sources.EmissionSink
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SourceWishStoreWritePathTest {
    @Test
    fun setWishOffPersistsAndRebuiltRegistryDoesNotStartThatEngine() {
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
        val snapshot = rebuilt.snapshot()
        assertEquals(SourceWish.Off, snapshot.sources.single { it.sourceId == "audio" }.wish)
        assertEquals(SourceWish.On, snapshot.sources.single { it.sourceId == "location" }.wish)
        rebuilt.engines.forEach { it.start(EmissionSink { }) }
        assertEquals(0, rebuiltAudio.startCalls)
        assertEquals(1, rebuiltLocation.startCalls)
    }
}
