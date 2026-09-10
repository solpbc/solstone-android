// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.harness

import app.solstone.core.sources.EmissionSink
import java.nio.file.Files
import app.solstone.core.model.SourceState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SourceWishStoreFirstRunTest {
    @Test
    fun emptyStoreIsReadyToSetUpAndStartsNothing() {
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
        // 🔴 INVERTED, deliberately. This asserted that an empty store resolves every source on
        // and starts its engine — the behaviour that made a fresh install read `3 need attention`
        // and capture before the owner had asked for anything. An empty store now means the owner
        // has expressed nothing: `ready to set up`, and nothing actuated.
        assertTrue(snapshot.sources.all { it.wish == SourceWish.Off })
        assertTrue(snapshot.sources.none { it.wishExpressed })
        assertTrue(snapshot.sources.all { it.state == SourceState.READY_TO_SET_UP })
        registry.engines.forEach { it.start(EmissionSink { }) }
        // ⛔ Zero. "Reads ready to set up" and "is not running" are one state, never a label over a
        // source that is quietly on.
        assertEquals(0, audio.startCalls)
        assertEquals(0, location.startCalls)
    }
}
