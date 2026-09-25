// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone

import app.solstone.platform.fgs.CaptureForegroundType
import kotlin.test.Test
import kotlin.test.assertEquals

class CapturePermissionRouteTest {
    @Test
    fun alreadyGrantedRoutesToAlreadyGranted() {
        assertEquals(
            CapturePermissionRoute.AlreadyGranted,
            capturePermissionRoute(granted = true, previouslyRequested = false, shouldShowRationale = false),
        )
    }

    @Test
    fun firstRequestShowsDialogEvenWhenRationaleIsFalse() {
        assertEquals(
            CapturePermissionRoute.RequestDialog,
            capturePermissionRoute(granted = false, previouslyRequested = false, shouldShowRationale = false),
        )
    }

    @Test
    fun secondRequestShowsDialogWhenRationaleIsTrue() {
        assertEquals(
            CapturePermissionRoute.RequestDialog,
            capturePermissionRoute(granted = false, previouslyRequested = true, shouldShowRationale = true),
        )
    }

    @Test
    fun suppressedDialogRoutesToSettings() {
        assertEquals(
            CapturePermissionRoute.OpenAppSettings,
            capturePermissionRoute(granted = false, previouslyRequested = true, shouldShowRationale = false),
        )
    }

    @Test
    fun permissionResultDoesNotOpenSettings() {
        assertEquals(false, permissionResultOpensSettings())
    }

    @Test
    fun denialWishWriteRules() {
        assertEquals(DenialWishWrite.RemoveEntry, denialWishWrite(PriorSourceWish.Unexpressed))
        assertEquals(DenialWishWrite.KeepOff, denialWishWrite(PriorSourceWish.Off))
        assertEquals(DenialWishWrite.KeepOn, denialWishWrite(PriorSourceWish.On))
    }

    @Test
    fun coldAudioOffPlanning() {
        val declared = setOf(CaptureForegroundType.MICROPHONE, CaptureForegroundType.LOCATION)
        
        // Unreadable store writes nothing
        val unreadablePlan = planColdAudioOff(
            store = app.solstone.observer.harness.WishStoreState.Unreadable,
            microphoneGranted = true,
            cameraGranted = false,
            locationGranted = true,
            declared = declared,
            serviceHeld = true,
        )
        assertEquals(null, unreadablePlan.wishesToWrite)
        assertEquals(false, unreadablePlan.stopService)
        assertEquals(false, unreadablePlan.commitDesiredOff)

        // Only audio was on: turning audio off ends session
        val onlyAudioPlan = planColdAudioOff(
            store = app.solstone.observer.harness.WishStoreState.Loaded(mapOf("audio" to app.solstone.observer.harness.SourceWish.On)),
            microphoneGranted = true,
            cameraGranted = false,
            locationGranted = true,
            declared = declared,
            serviceHeld = true,
        )
        assertEquals(mapOf("audio" to app.solstone.observer.harness.SourceWish.Off), onlyAudioPlan.wishesToWrite)
        assertEquals(true, onlyAudioPlan.recordOwnerStopped)
        assertEquals(true, onlyAudioPlan.commitDesiredOff)
        assertEquals(true, onlyAudioPlan.stopService)
        assertEquals(false, onlyAudioPlan.refreshRunningMask)

        // Audio and location both on: turning audio off leaves location on
        val audioAndLocationPlan = planColdAudioOff(
            store = app.solstone.observer.harness.WishStoreState.Loaded(
                mapOf("audio" to app.solstone.observer.harness.SourceWish.On, "location" to app.solstone.observer.harness.SourceWish.On)
            ),
            microphoneGranted = true,
            cameraGranted = false,
            locationGranted = true,
            declared = declared,
            serviceHeld = true,
        )
        assertEquals(
            mapOf("audio" to app.solstone.observer.harness.SourceWish.Off, "location" to app.solstone.observer.harness.SourceWish.On),
            audioAndLocationPlan.wishesToWrite,
        )
        assertEquals(false, audioAndLocationPlan.recordOwnerStopped)
        assertEquals(false, audioAndLocationPlan.commitDesiredOff)
        assertEquals(false, audioAndLocationPlan.stopService)
        assertEquals(true, audioAndLocationPlan.refreshRunningMask)
    }
}
