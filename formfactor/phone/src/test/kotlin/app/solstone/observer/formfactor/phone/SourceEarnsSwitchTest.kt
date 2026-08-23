// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SourceEarnsSwitchTest {
    @Test
    fun predicateMatchesApprovedDesign() {
        assertTrue(sourceEarnsSwitch("audio"))
        assertTrue(sourceEarnsSwitch("location"))
        assertFalse(sourceEarnsSwitch("camera"))
        assertFalse(sourceEarnsSwitch("watch"))
        assertFalse(sourceEarnsSwitch("pendant"))
    }
}
