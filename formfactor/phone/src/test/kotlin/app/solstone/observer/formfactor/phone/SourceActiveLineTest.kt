// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import app.solstone.core.model.ReasonCode
import app.solstone.core.model.SourceState
import app.solstone.observer.harness.SourceStatus
import app.solstone.observer.harness.SourceWish
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SourceActiveLineTest {
    private fun on(sourceId: String) = SourceStatus(sourceId, SourceWish.On, SourceState.ON, ReasonCode.NONE)

    @Test
    fun onlyCameraHasAnActiveLine() {
        assertNotNull(sourceActiveLine("camera"))
        assertNull(sourceActiveLine("audio"))
        assertNull(sourceActiveLine("location"))
    }

    @Test
    fun anOnSourceSubLineIsItsOwnActiveLine() {
        for (paired in listOf(false, true)) {
            assertEquals(sourceActiveLine("camera"), sourceSubLine(on("camera"), paired))
            assertNull(sourceSubLine(on("audio"), paired))
            assertNull(sourceSubLine(on("location"), paired))
        }
    }

    @Test
    fun theActiveLineDoesNotReplaceAnotherStatesLine() {
        val off = SourceStatus("camera", SourceWish.Off, SourceState.OFF, ReasonCode.NONE)
        assertNotNull(sourceSubLine(off, paired = true))
        assertNotEquals(sourceActiveLine("camera"), sourceSubLine(off, paired = true))
    }
}
