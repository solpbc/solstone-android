// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.harness

import app.solstone.core.sources.EmissionSink
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SourceWishStoreFirstRunTest {
    @Test
    fun emptyStoreResolvesOnAndStartsInnerEngines() {
        val dir = Files.createTempDirectory("source-wishes-first-run").toFile()
        val store = FileSourceWishStore(dir.resolve("source-wishes"))
        val audio = FakeSourceEngine()
        val location = FakeSourceEngine()
        val registry = sourceRegistry(
            registrations = listOf(
                SourceRegistration("audio", audio),
                SourceRegistration("location", location),
            ),
            wishStore = store,
        )
        val snapshot = registry.snapshot()
        assertTrue(snapshot.sources.all { it.wish == SourceWish.On })
        registry.engines.forEach { it.start(EmissionSink { }) }
        assertEquals(1, audio.startCalls)
        assertEquals(1, location.startCalls)
    }
}
