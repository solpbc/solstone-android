// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ScreenshotsTileLineTest {
    private val bringingIn = PhoneScreenshotsState(
        accessGranted = true,
        enabled = true,
        confirmed = true,
        backfillDone = 0,
        backfillTotal = 3,
    )

    @Test
    fun unpairedSaysScreenshotsWaitEvenWhileABackfillIsOpen() {
        assertEquals(ScreenshotsTileLine.S3, screenshotsTileLine(bringingIn, paired = false))
        assertEquals(ScreenshotsTileLine.S3, screenshotsTileLine(bringingIn.copy(capability = false), paired = false))
    }

    @Test
    fun pairedShowsProgressThenTakingIn() {
        assertEquals(ScreenshotsTileLine.S5, screenshotsTileLine(bringingIn, paired = true))
        assertEquals(ScreenshotsTileLine.S2, screenshotsTileLine(bringingIn.copy(backfillDone = 3), paired = true))
        assertEquals(ScreenshotsTileLine.S21, screenshotsTileLine(bringingIn.copy(capability = false), paired = true))
    }

    @Test
    fun aLostGrantAndAPauseOutrankEverything() {
        assertEquals(ScreenshotsTileLine.A7, screenshotsTileLine(bringingIn.copy(accessLost = true), paired = false))
        assertEquals(ScreenshotsTileLine.S16, screenshotsTileLine(bringingIn.copy(paused = true), paired = false))
    }

    @Test
    fun beforeTheConfirmTheTileAsksHowFarBackAndNeverSetUpIsSilent() {
        assertEquals(ScreenshotsTileLine.S11, screenshotsTileLine(PhoneScreenshotsState(accessGranted = true), paired = true))
        assertNull(screenshotsTileLine(PhoneScreenshotsState(), paired = true))
        assertNull(
            screenshotsTileLine(PhoneScreenshotsState(accessGranted = true, firstSetUp = false), paired = true),
            "turned off, re-chosen or re-paired: the shared off line, not the first set-up's",
        )
    }
}
