// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.audio

import app.solstone.core.model.SilencedFact
import kotlin.test.Test
import kotlin.test.assertEquals

class MediaRecorderFactoryTest {
    @Test
    fun silencedFactOfGatesAndClassifiesRecorderConfiguration() {
        assertEquals(
            SilencedFact.UNKNOWN,
            silencedFactOf(sdkInt = 28, hasActiveConfiguration = true, clientSilenced = true),
        )
        assertEquals(
            SilencedFact.UNKNOWN,
            silencedFactOf(sdkInt = 29, hasActiveConfiguration = false, clientSilenced = true),
        )
        assertEquals(
            SilencedFact.SILENCED,
            silencedFactOf(sdkInt = 29, hasActiveConfiguration = true, clientSilenced = true),
        )
        assertEquals(
            SilencedFact.NOT_SILENCED,
            silencedFactOf(sdkInt = 29, hasActiveConfiguration = true, clientSilenced = false),
        )
    }
}
