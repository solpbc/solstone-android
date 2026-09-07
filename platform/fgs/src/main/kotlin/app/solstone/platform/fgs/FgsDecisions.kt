// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.fgs

import app.solstone.core.model.ReasonCode
import app.solstone.core.model.SourceState

enum class CaptureForegroundType {
    MICROPHONE,
    LOCATION,
    CAMERA;

    val token: String
        get() = name.lowercase()
}

data class ObserverStartCommandPlan(
    val enterForeground: Boolean,
    val initialNeedsAttention: Boolean,
    val dispatchRehydrate: Boolean,
    val postAttentionOn102: Boolean,
    val stopSelf: Boolean,
)

fun onStartCommandPlan(
    hasIntent: Boolean,
    hasRehydrator: Boolean,
    subsetEmpty: Boolean = false,
): ObserverStartCommandPlan =
    if (subsetEmpty) {
        ObserverStartCommandPlan(
            enterForeground = false,
            initialNeedsAttention = true,
            dispatchRehydrate = false,
            postAttentionOn102 = true,
            stopSelf = true,
        )
    } else {
        ObserverStartCommandPlan(
            enterForeground = true,
            initialNeedsAttention = true,
            dispatchRehydrate = hasRehydrator,
            postAttentionOn102 = !hasIntent && !hasRehydrator,
            stopSelf = !hasIntent && !hasRehydrator,
        )
    }

fun satisfiableCaptureForegroundTypes(
    microphoneGranted: Boolean,
    cameraGranted: Boolean,
    locationGranted: Boolean,
    declared: Set<CaptureForegroundType>,
): Set<CaptureForegroundType> {
    val result = linkedSetOf<CaptureForegroundType>()
    if (CaptureForegroundType.MICROPHONE in declared && microphoneGranted) {
        result += CaptureForegroundType.MICROPHONE
    }
    if (CaptureForegroundType.LOCATION in declared && locationGranted) {
        result += CaptureForegroundType.LOCATION
    }
    if (CaptureForegroundType.CAMERA in declared && cameraGranted) {
        result += CaptureForegroundType.CAMERA
    }
    return result
}

fun captureForegroundTypesFromTokens(tokens: Set<String>): Set<CaptureForegroundType> =
    tokens.mapNotNull { token ->
        when (token.trim().lowercase()) {
            "microphone" -> CaptureForegroundType.MICROPHONE
            "location" -> CaptureForegroundType.LOCATION
            "camera" -> CaptureForegroundType.CAMERA
            else -> null
        }
    }.toSet()

fun typesDiagLine(
    granted: Set<CaptureForegroundType>,
    declared: Set<CaptureForegroundType>,
    subset: Set<CaptureForegroundType>,
): String {
    fun formatTokens(types: Set<CaptureForegroundType>): String =
        CaptureForegroundType.entries
            .filter { it in types }
            .joinToString(",") { it.token }

    return "fgs phase=types granted=${formatTokens(granted)} declared=${formatTokens(declared)} subset=${formatTokens(subset)}"
}

fun handledForegroundStartExceptionNames(): Set<String> =
    setOf(
        "android.app.ForegroundServiceStartNotAllowedException",
        "android.app.MissingForegroundServiceTypeException",
        "android.app.InvalidForegroundServiceTypeException",
        "java.lang.SecurityException",
        "java.lang.IllegalArgumentException",
        "ForegroundServiceStartNotAllowedException",
        "MissingForegroundServiceTypeException",
        "InvalidForegroundServiceTypeException",
        "SecurityException",
        "IllegalArgumentException",
    )

fun widgetRefusalReasonForStartException(className: String): ReasonCode =
    when (className) {
        "SecurityException",
        "java.lang.SecurityException",
        "MissingForegroundServiceTypeException",
        "android.app.MissingForegroundServiceTypeException",
        "InvalidForegroundServiceTypeException",
        "android.app.InvalidForegroundServiceTypeException",
        "IllegalArgumentException",
        "java.lang.IllegalArgumentException",
        "EmptyCaptureForegroundSubset",
        -> ReasonCode.PERMISSION_REVOKED
        else -> ReasonCode.FOREGROUND_START_NOT_ALLOWED
    }

fun needsAttentionForState(state: SourceState): Boolean = state != SourceState.ON

fun shouldOfferStartAction(isRunning: Boolean, hasEnabledSources: Boolean = true): Boolean =
    !isRunning && hasEnabledSources

fun shouldOfferStopAction(isLiveForegroundService: Boolean): Boolean = isLiveForegroundService

fun shouldNotifyCaptureStopped(lastObservedStateWord: String?, currentStateWord: String): Boolean =
    lastObservedStateWord == "on" && currentStateWord == "off"

fun startFailureDiagLine(exceptionClassName: String): String =
    "fgs start-failure exception=$exceptionClassName"

