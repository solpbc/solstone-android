// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.scaffold

import kotlin.test.Test
import kotlin.test.assertEquals

class ScanEntryTest {
    @Test
    fun aGrantedCameraGoesStraightToTheScannerWhateverWasAsked() {
        CameraAsk.entries.forEach { ask ->
            assertEquals(ScanEntry.Scan, scanEntry(hasCamera = true, ask = ask))
        }
    }

    @Test
    fun aFreshEntryWithoutTheCameraAsksForIt() {
        // The defect: this case used to open the camera and print the platform's exception.
        assertEquals(ScanEntry.AskForCamera, scanEntry(hasCamera = false, ask = CameraAsk.NotAsked))
    }

    @Test
    fun aRotationUnderThePromptDoesNotRaiseASecondOne() {
        assertEquals(ScanEntry.AwaitAnswer, scanEntry(hasCamera = false, ask = CameraAsk.Awaiting))
    }

    @Test
    fun aDenialLandsOnTheCameraOffScreenAndNeverAsksAgain() {
        assertEquals(ScanEntry.CameraOff, scanEntry(hasCamera = false, ask = CameraAsk.Answered))
    }
}
