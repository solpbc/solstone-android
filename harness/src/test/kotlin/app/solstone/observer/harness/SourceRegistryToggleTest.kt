// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.harness

import app.solstone.core.sources.EmissionSink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

class SourceRegistryToggleTest {
    @Test
    fun setWishReturnsEachTypedResult() {
        val engine = FakeSourceEngine()
        val boom = IllegalStateException("start failed")
        val registry = sourceRegistry(
            registrations = listOf(SourceRegistration("audio", engine)),
        )

        assertIs<SourceToggleResult.AwaitingObserver>(registry.setWish("audio", SourceWish.On))
        assertIs<SourceToggleResult.UnknownSource>(registry.setWish("missing", SourceWish.On))
        assertIs<SourceToggleResult.Applied>(registry.setWish("audio", SourceWish.Off))

        registry.engines.single().start(EmissionSink { })
        engine.throwOnStart = boom
        val failed = registry.setWish("audio", SourceWish.On)
        val engineFailed = assertIs<SourceToggleResult.EngineFailed>(failed)
        assertSame(boom, engineFailed.error)
    }

    @Test
    fun setWishDoesNotTouchGlobalStartStopOrDesiredOn() {
        val f = fixture()
        val engine = FakeSourceEngine()
        val registry = sourceRegistry(
            f = f,
            registrations = listOf(SourceRegistration("audio", engine)),
        )

        assertEquals(false, f.controller.desiredOn)
        assertEquals(0, f.lifecycle.starts)
        assertEquals(0, f.lifecycle.stops)

        registry.setWish("audio", SourceWish.Off)
        registry.setWish("audio", SourceWish.On)
        registry.setWish("missing", SourceWish.Off)
        registry.engines.single().start(EmissionSink { })
        registry.setWish("audio", SourceWish.Off)
        registry.setWish("audio", SourceWish.On)

        assertEquals(false, f.controller.desiredOn)
        assertEquals(0, f.lifecycle.starts)
        assertEquals(0, f.lifecycle.stops)
    }
}
