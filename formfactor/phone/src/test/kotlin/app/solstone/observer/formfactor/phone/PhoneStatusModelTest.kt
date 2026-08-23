// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class PhoneStatusModelTest {
    @Test
    fun fourPillStatesRenderQuotedCopy() {
        assertEquals(
            "connected",
            statusPillText(PhoneStatusModel(paired = true, online = true, pendingCount = 0, hasContentPending = false)),
        )
        assertEquals(
            "3 syncing",
            statusPillText(PhoneStatusModel(paired = true, online = true, pendingCount = 3, hasContentPending = true)),
        )
        assertEquals(
            "offline · 2 waiting",
            statusPillText(PhoneStatusModel(paired = true, online = false, pendingCount = 2, hasContentPending = true)),
        )
        assertEquals(
            "not paired",
            statusPillText(PhoneStatusModel(paired = false, online = true, pendingCount = 4, hasContentPending = true)),
        )
    }

    @Test
    fun pendingFlagIsNotSummedIntoCount() {
        val model = PhoneStatusModel(
            paired = true,
            online = true,
            pendingCount = 3,
            hasContentPending = true,
        )
        assertEquals("3 syncing", statusPillText(model))
        assertFalse(statusPillText(model).contains("4"))
    }

    @Test
    fun retiredOfflineFormDoesNotAppear() {
        val text = statusPillText(
            PhoneStatusModel(paired = true, online = false, pendingCount = 38, hasContentPending = true),
        )
        assertEquals("offline · 38 waiting", text)
        assertFalse(text.contains("38 offline"))
    }
}
