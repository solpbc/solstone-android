// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import app.solstone.core.model.ReasonCode
import app.solstone.core.model.SourceState
import app.solstone.observer.harness.SourceStatus
import app.solstone.observer.harness.SourceWish
import app.solstone.observer.harness.SourcesReadModel

const val STATUS_UNAVAILABLE = "status unavailable"

data class PhoneIntakeNotificationModel(
    val stateWord: String,
    val route: PhoneRoute?,
)

fun derivePhoneIntakeNotification(
    snapshot: SourcesReadModel?,
    fgsLive: Boolean,
    desiredOn: Boolean,
): PhoneIntakeNotificationModel {
    if (snapshot == null) {
        return PhoneIntakeNotificationModel(
            stateWord = STATUS_UNAVAILABLE,
            route = null,
        )
    }

    val enabledSources = snapshot.sources.filter { it.wish == SourceWish.On }
    if (!desiredOn || enabledSources.isEmpty()) {
        return PhoneIntakeNotificationModel(
            stateWord = "off",
            route = null,
        )
    }

    val attentionSources = enabledSources.filter {
        it.state == SourceState.NEEDS_ATTENTION ||
            it.reason == ReasonCode.FOREGROUND_TYPE_NOT_HELD ||
            it.reason == ReasonCode.FOREGROUND_START_NOT_ALLOWED
    }

    if (attentionSources.isNotEmpty()) {
        val minSourceId = attentionSources.minOf { it.sourceId }
        return PhoneIntakeNotificationModel(
            stateWord = "needs attention",
            route = PhoneRoute.SourceDetail(minSourceId),
        )
    }

    if (snapshot.observer.reason in setOf(
            ReasonCode.SERVICE_KILLED,
            ReasonCode.REBOOTED,
            ReasonCode.FOREGROUND_START_NOT_ALLOWED,
        )) {
        return PhoneIntakeNotificationModel("needs attention", null)
    }

    if (enabledSources.any { it.state == SourceState.SETTING_UP }) {
        return PhoneIntakeNotificationModel(
            stateWord = "setting up",
            route = null,
        )
    }

    if (enabledSources.any { it.state == SourceState.PAUSED }) {
        return PhoneIntakeNotificationModel(
            stateWord = "paused",
            route = null,
        )
    }

    if (fgsLive && enabledSources.all { it.state == SourceState.ON }) {
        return PhoneIntakeNotificationModel(
            stateWord = "on",
            route = null,
        )
    }

    return PhoneIntakeNotificationModel(
        stateWord = "setting up",
        route = null,
    )
}

fun derivePhoneIntakeNotificationCatching(
    supplier: () -> SourcesReadModel?,
    fgsLive: Boolean,
    desiredOn: Boolean,
): PhoneIntakeNotificationModel =
    runCatching {
        derivePhoneIntakeNotification(
            snapshot = supplier(),
            fgsLive = fgsLive,
            desiredOn = desiredOn,
        )
    }.getOrElse {
        PhoneIntakeNotificationModel(
            stateWord = STATUS_UNAVAILABLE,
            route = null,
        )
    }
