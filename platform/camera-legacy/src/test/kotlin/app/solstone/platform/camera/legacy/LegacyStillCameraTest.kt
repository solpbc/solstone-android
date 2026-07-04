// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.camera.legacy

import app.solstone.platform.camera.still.StillCaptureResult
import kotlin.test.Test
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LegacyStillCameraTest {
    @Test
    fun interruptedStillFailureRestoresInterruptFlagAndCarriesCause() {
        Thread.interrupted()
        val cause = InterruptedException("sleep")

        val result = interruptedStillFailure(cause)

        assertTrue(Thread.currentThread().isInterrupted)
        assertSame(cause, (result as StillCaptureResult.Failure).cause)
        Thread.interrupted()
    }
}
