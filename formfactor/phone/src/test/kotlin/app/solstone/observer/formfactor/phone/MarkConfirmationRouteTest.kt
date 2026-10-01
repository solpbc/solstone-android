// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MarkConfirmationRouteTest {
    @Test
    fun routesToConfirmWhenAwaitingMarkConfirmation() {
        assertEquals(
            MarkConfirmationRoute.CONFIRM,
            manualMarkConfirmationRoute(
                awaitingMarkConfirmation = true,
                promptedConfirmationThisProcess = false,
            ),
        )
        assertEquals(
            MarkConfirmationRoute.CONFIRM,
            manualMarkConfirmationRoute(
                awaitingMarkConfirmation = true,
                promptedConfirmationThisProcess = true,
            ),
        )
    }

    @Test
    fun routesToNullWhenNotAwaitingMarkConfirmation() {
        assertNull(
            manualMarkConfirmationRoute(
                awaitingMarkConfirmation = false,
                promptedConfirmationThisProcess = false,
            ),
        )
        assertNull(
            manualMarkConfirmationRoute(
                awaitingMarkConfirmation = false,
                promptedConfirmationThisProcess = true,
            ),
        )
    }
}
