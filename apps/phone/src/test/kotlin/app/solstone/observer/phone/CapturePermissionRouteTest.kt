// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone

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

}
