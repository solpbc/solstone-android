// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import kotlin.test.Test
import kotlin.test.assertEquals

class PhonePaneTest {
    @Test
    fun shelfRequiresDrawerStateOverload() {
        assertEquals(
            PaneBackDismissRequirement.DRAWER_STATE_OVERLOAD,
            PhonePane.SHELF.backDismissRequirement,
        )
    }

    @Test
    fun journalRequiresPartialExpandCollapsesFirst() {
        assertEquals(
            PaneBackDismissRequirement.PARTIAL_EXPAND_COLLAPSES_FIRST,
            PhonePane.JOURNAL.backDismissRequirement,
        )
    }

    @Test
    fun statusRequiresNoLibraryBack() {
        assertEquals(
            PaneBackDismissRequirement.NO_LIBRARY_BACK,
            PhonePane.STATUS.backDismissRequirement,
        )
    }
}
