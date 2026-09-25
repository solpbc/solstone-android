// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone

enum class CapturePermissionRoute { AlreadyGranted, RequestDialog, OpenAppSettings }

fun capturePermissionRoute(
    granted: Boolean,
    previouslyRequested: Boolean,
    shouldShowRationale: Boolean,
): CapturePermissionRoute =
    when {
        granted -> CapturePermissionRoute.AlreadyGranted
        !previouslyRequested -> CapturePermissionRoute.RequestDialog
        shouldShowRationale -> CapturePermissionRoute.RequestDialog
        else -> CapturePermissionRoute.OpenAppSettings
    }

fun permissionResultOpensSettings(): Boolean = false

enum class PriorSourceWish { Unexpressed, Off, On }
enum class DenialWishWrite { RemoveEntry, KeepOff, KeepOn }

fun denialWishWrite(prior: PriorSourceWish): DenialWishWrite =
    when (prior) {
        PriorSourceWish.Unexpressed -> DenialWishWrite.RemoveEntry
        PriorSourceWish.Off -> DenialWishWrite.KeepOff
        PriorSourceWish.On -> DenialWishWrite.KeepOn
    }
