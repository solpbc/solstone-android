// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.harness

import app.solstone.core.model.ReasonCode
import app.solstone.core.model.SourceState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class HarnessDesiredOnTest {
    @Test
    fun stoppedWithRealHealthyFactsIsOff() {
        val f = fixture()
        assertFalse(f.controller.desiredOn)
        val diagnostics = f.controller.diagnostics()
        assertEquals(SourceState.OFF, diagnostics.state)
        assertEquals(ReasonCode.NONE, diagnostics.reason)

        f.controller.start()
        f.controller.stop()
        assertFalse(f.controller.desiredOn)
        assertEquals(SourceState.OFF, f.controller.diagnostics().state)
    }

    @Test
    fun startSetsDesiredOnStopSetsDesiredOff() {
        val f = fixture()

        f.controller.start()
        assertEquals(true, f.desiredStore.current)
        assertEquals(true, f.controller.desiredOn)

        f.controller.stop()
        assertEquals(false, f.desiredStore.current)
        assertEquals(false, f.controller.desiredOn)
    }

    @Test
    fun sharedStoreSurvivesControllerRecreation() {
        val store = FakeDesiredObservingStore()
        val first = fixture(desiredStore = store)

        first.controller.start()
        val second = fixture(desiredStore = store)

        assertEquals(true, second.controller.desiredOn)
    }
}
