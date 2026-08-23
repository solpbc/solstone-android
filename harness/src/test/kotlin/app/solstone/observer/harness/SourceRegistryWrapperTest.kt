// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.harness

import app.solstone.core.sources.EmissionSink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class SourceRegistryWrapperTest {
    @Test
    fun wrapperStartForwardsOnlyWhenWishIsOnAndStopStopsStartedInner() {
        val engine = FakeSourceEngine()
        val registry = sourceRegistry(
            registrations = listOf(SourceRegistration("audio", engine)),
        )
        val wrapper = registry.engines.single()
        val sink = EmissionSink { }

        wrapper.start(sink)
        assertEquals(1, engine.startCalls)
        assertSame(sink, engine.lastSink)

        wrapper.stop()
        assertEquals(1, engine.stopCalls)

        registry.setWish("audio", SourceWish.Off)
        engine.startCalls = 0
        engine.stopCalls = 0
        wrapper.start(sink)
        assertEquals(0, engine.startCalls)

        registry.setWish("audio", SourceWish.On)
        assertEquals(1, engine.startCalls)
    }
}
