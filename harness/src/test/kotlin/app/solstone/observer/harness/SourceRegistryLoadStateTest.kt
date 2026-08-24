// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.harness

import app.solstone.core.model.ReasonCode
import app.solstone.core.model.SourceState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SourceRegistryLoadStateTest {
    @Test
    fun throwingConditionDegradesOnlyItsOwnRow() {
        val boom = RuntimeException("condition failed")
        val engine = FakeSourceEngine(throwOnCondition = boom)
        val registry = sourceRegistry(
            registrations = listOf(SourceRegistration("audio", engine)),
        )
        val states = mutableListOf<LoadState<SourcesReadModel>>()
        val loader = AsyncLoad(
            background = BackgroundRunner { it() },
            main = MainPoster { it() },
        )

        loader.load({ registry.snapshot() }) { states += it }

        assertSame(LoadState.Loading, states[0])
        val loaded = assertIs<LoadState.Loaded<SourcesReadModel>>(states.last()).value
        val row = loaded.sources.single()
        assertEquals(SourceState.NEEDS_ATTENTION, row.state)
        assertEquals(ReasonCode.PROVIDER_SILENT, row.reason)
        assertTrue(states.none { it is LoadState.Failed })
    }

    @Test
    fun healthySeamsLoadWithoutFailed() {
        val engine = FakeSourceEngine()
        val registry = sourceRegistry(
            registrations = listOf(SourceRegistration("audio", engine)),
        )
        val states = mutableListOf<LoadState<SourcesReadModel>>()
        val loader = AsyncLoad(
            background = BackgroundRunner { it() },
            main = MainPoster { it() },
        )

        loader.load({ registry.snapshot() }) { states += it }

        assertSame(LoadState.Loading, states[0])
        assertIs<LoadState.Loaded<SourcesReadModel>>(states.last())
        assertTrue(states.none { it is LoadState.Failed })
        assertEquals(2, states.size)
    }
}
