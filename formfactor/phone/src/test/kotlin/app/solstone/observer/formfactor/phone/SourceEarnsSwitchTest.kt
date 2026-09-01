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
        // ⚠ Was asserted FALSE. Camera runs on this device and is registered with the
        // same wish machinery as audio and location, so a switch performs exactly what
        // it names — which is § 2.4's actual rule. The exclusion came from reading the
        // contract's "audio, screen and location" enumeration, which is iOS's source
        // set and never had a chance to name Android's camera.
        assertTrue(sourceEarnsSwitch("camera"))
        // These carry none, anywhere: the phone cannot start a session on them.
        assertFalse(sourceEarnsSwitch("watch"))
        assertFalse(sourceEarnsSwitch("pendant"))
    }
}
