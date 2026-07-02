// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.harness

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VisibleCaptureOwnerRegistryTest {
    @Test
    fun acquireReturnsIncreasingGenerations() {
        val registry = VisibleCaptureOwnerRegistry()

        val first = registry.acquire()
        val second = registry.acquire()

        assertTrue(second > first)
        assertFalse(registry.isCurrent(first))
        assertTrue(registry.isCurrent(second))
    }

    @Test
    fun releaseCurrentClearsOwner() {
        val registry = VisibleCaptureOwnerRegistry()
        val token = registry.acquire()

        registry.release(token)

        assertFalse(registry.isVisibleOwnerPresent())
        assertFalse(registry.isCurrent(token))
    }

    @Test
    fun staleReleaseDoesNotClearNewerOwner() {
        val registry = VisibleCaptureOwnerRegistry()
        val stale = registry.acquire()
        val current = registry.acquire()

        registry.release(stale)

        assertTrue(registry.isVisibleOwnerPresent())
        assertTrue(registry.isCurrent(current))
        assertFalse(registry.isCurrent(stale))
    }
}
