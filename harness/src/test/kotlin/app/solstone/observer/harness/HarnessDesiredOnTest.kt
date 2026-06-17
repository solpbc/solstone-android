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
}
