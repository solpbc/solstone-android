// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import app.solstone.observer.harness.LoadState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class PhoneStatusCaptureTest {
    @Test
    fun decodesEveryCaptureValue() {
        assertEquals(PhoneStatusCapture.LOADING, decodePhoneStatusCapture("loading"))
        assertEquals(PhoneStatusCapture.FAILED, decodePhoneStatusCapture("failed"))
        assertEquals(PhoneStatusCapture.UNPAIRED, decodePhoneStatusCapture("unpaired"))
        assertEquals(PhoneStatusCapture.PAIRED_OFFLINE, decodePhoneStatusCapture("paired-offline"))
        assertEquals(PhoneStatusCapture.PAIRED_CAUGHT_UP, decodePhoneStatusCapture("paired-caught-up"))
    }

    @Test
    fun invalidAndAbsentValuesDoNotResolve() {
        assertNull(decodePhoneStatusCapture(null))
        assertNull(decodePhoneStatusCapture("unknown"))
        assertNull(resolvePhoneStatusCapture(null, debuggable = true))
        assertNull(resolvePhoneStatusCapture("unknown", debuggable = true))
    }

    @Test
    fun validCapturesResolveToTheirExpectedStates() {
        assertIs<LoadState.Loading>(resolvePhoneStatusCapture("loading", debuggable = true))
        assertIs<LoadState.Failed>(resolvePhoneStatusCapture("failed", debuggable = true))

        val unpaired = loadedCapture("unpaired")
        assertEquals(false, unpaired.status.paired)
        assertEquals(false, unpaired.status.online)

        val offline = loadedCapture("paired-offline")
        assertEquals(true, offline.status.paired)
        assertEquals(false, offline.status.online)
        assertEquals(1, offline.status.pendingCount)
        assertEquals(true, offline.status.hasContentPending)
        assertEquals(listOf("audio"), offline.waiting.map { it.sourceId })

        val caughtUp = loadedCapture("paired-caught-up")
        assertEquals(true, caughtUp.status.paired)
        assertEquals(true, caughtUp.status.online)
        assertEquals(0, caughtUp.status.pendingCount)
    }

    @Test
    fun nonDebuggableRejectsEveryCaptureValue() {
        listOf("loading", "failed", "unpaired", "paired-offline", "paired-caught-up").forEach { raw ->
            assertNull(resolvePhoneStatusCapture(raw, debuggable = false))
        }
    }

    private fun loadedCapture(raw: String): PhoneStatusSnapshot =
        assertIs<LoadState.Loaded<PhoneStatusSnapshot>>(resolvePhoneStatusCapture(raw, debuggable = true)).value
}
