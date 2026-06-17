// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.harness

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

class AsyncLoadTest {
    private val loader = AsyncLoad(
        background = BackgroundRunner { it() },
        main = MainPoster { it() },
    )

    @Test
    fun nonEmptyResultLoads() {
        val value = listOf("seg-1")
        val states = mutableListOf<LoadState<List<String>>>()

        loader.load({ value }) { states += it }

        assertEquals(listOf(LoadState.Loading, LoadState.Loaded(value)), states)
    }

    @Test
    fun emptyResultLoadsAsEmptyNotFailed() {
        val states = mutableListOf<LoadState<List<String>>>()

        loader.load({ emptyList<String>() }) { states += it }

        assertEquals(listOf(LoadState.Loading, LoadState.Loaded(emptyList())), states)
    }

    @Test
    fun thrownSupplierLoadsAsFailed() {
        val boom = RuntimeException("boom")
        val states = mutableListOf<LoadState<List<String>>>()

        loader.load<List<String>>({ throw boom }) { states += it }

        assertSame(LoadState.Loading, states[0])
        val failed = assertIs<LoadState.Failed>(states[1])
        assertSame(boom, failed.error)
    }
}
