// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.camera.camera2

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class CameraOpenCoordinatorTest {
    @Test
    fun timedOutCoordinatorClosesLateOpenAndFreshCoordinatorCanOpen() {
        val closed = mutableListOf<FakeDevice>()
        val timedOut = CameraOpenCoordinator<FakeDevice> { closed += it }

        assertEquals(null, timedOut.awaitOpen(timeoutMs = 1L))
        val late = FakeDevice("late")
        timedOut.onOpened(late)

        assertEquals(listOf(late), closed)

        val fresh = CameraOpenCoordinator<FakeDevice> { closed += it }
        val next = FakeDevice("next")
        fresh.onOpened(next)

        assertSame(next, fresh.awaitOpen(timeoutMs = 1L))
        assertEquals(listOf(late), closed)
    }

    private data class FakeDevice(val id: String)
}
