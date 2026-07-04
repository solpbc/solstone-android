// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.glasses

import kotlin.test.Test
import kotlin.test.assertEquals

class RokidReceiverRegistrationTest {
    @Test
    fun sdk33AndNewerUseNotExportedFlag() {
        assertEquals(RokidReceiverRegistrationMode.NotExportedFlag, rokidReceiverRegistrationMode(33))
        assertEquals(RokidReceiverRegistrationMode.NotExportedFlag, rokidReceiverRegistrationMode(34))
    }

    @Test
    fun sdk32AndOlderUseLegacyExportedRegistration() {
        assertEquals(RokidReceiverRegistrationMode.LegacyExportedTwoArg, rokidReceiverRegistrationMode(32))
        assertEquals(RokidReceiverRegistrationMode.LegacyExportedTwoArg, rokidReceiverRegistrationMode(31))
    }
}
