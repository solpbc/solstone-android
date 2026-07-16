// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.power

sealed interface GuidanceIntentPreparation {
    data class Ready(val action: String, val data: String?) : GuidanceIntentPreparation
    data class Invalid(val reason: String) : GuidanceIntentPreparation
}

sealed interface GuidanceLaunchResult {
    data object Launched : GuidanceLaunchResult
    data class Failed(val message: String) : GuidanceLaunchResult
}

fun GuidanceAction.prepareIntent(packageName: String): GuidanceIntentPreparation {
    if (packageName.isBlank()) return GuidanceIntentPreparation.Invalid("app package is unavailable")
    val data = if (intentAction == APPLICATION_DETAILS_SETTINGS && intentData == PACKAGE_URI) {
        "$PACKAGE_URI$packageName"
    } else {
        intentData
    }
    if (intentAction == APPLICATION_DETAILS_SETTINGS && data?.substringAfter(PACKAGE_URI, "").isNullOrBlank()) {
        return GuidanceIntentPreparation.Invalid("app settings target is missing")
    }
    return GuidanceIntentPreparation.Ready(intentAction, data)
}

private const val APPLICATION_DETAILS_SETTINGS = "android.settings.APPLICATION_DETAILS_SETTINGS"
private const val PACKAGE_URI = "package:"
