// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import app.solstone.core.model.ReasonCode
import app.solstone.core.model.SourceState
import app.solstone.observer.harness.ObserverStatus
import app.solstone.observer.harness.SourceWish
import app.solstone.observer.harness.SourcesReadModel

sealed interface PhoneWidgetStartOutcome {
    data object None : PhoneWidgetStartOutcome
    data class Refused(val reason: ReasonCode) : PhoneWidgetStartOutcome
}

enum class PhoneObserverWidgetColorRole {
    SURFACE,
    CONTENT,
    ACTIVE,
    ATTENTION,
}

data class PhoneObserverWidgetModel(
    val audioChecked: Boolean,
    val audioWishOn: Boolean,
    val stateWord: String,
    val needsAttention: Boolean,
    val reason: ReasonCode,
    val pendingCount: Int,
    val syncText: String,
    val colors: Set<PhoneObserverWidgetColorRole>,
)

fun renderPhoneObserverWidget(
    readModel: SourcesReadModel?,
    statusModel: PhoneStatusModel,
    startOutcome: PhoneWidgetStartOutcome,
): PhoneObserverWidgetModel {
    val audio = readModel?.sources?.singleOrNull { it.sourceId == "audio" }
    val audioWish = audio?.wish
    val observer = when (startOutcome) {
        PhoneWidgetStartOutcome.None -> when (audioWish) {
            SourceWish.Off -> ObserverStatus(SourceState.OFF, ReasonCode.NONE)
            else -> readModel?.observer ?: ObserverStatus(SourceState.OFF, ReasonCode.NONE)
        }
        is PhoneWidgetStartOutcome.Refused -> ObserverStatus(SourceState.OFF, startOutcome.reason)
    }
    val audioWishOn = audioWish == SourceWish.On
    val audioState = audio?.state ?: SourceState.OFF
    val presentation = when {
        observer.state == SourceState.NEEDS_ATTENTION -> observer
        audioState != SourceState.ON -> ObserverStatus(audioState, audio?.reason ?: ReasonCode.NONE)
        else -> observer
    }
    val audioChecked = observer.state == SourceState.ON && audioState == SourceState.ON
    val needsAttention = !audioChecked
    return PhoneObserverWidgetModel(
        audioChecked = audioChecked,
        audioWishOn = audioWishOn,
        stateWord = sourceStateCopy(presentation.state),
        needsAttention = needsAttention,
        reason = presentation.reason,
        pendingCount = statusModel.pendingCount,
        syncText = statusPillText(statusModel),
        colors = buildSet {
            add(PhoneObserverWidgetColorRole.SURFACE)
            add(PhoneObserverWidgetColorRole.CONTENT)
            add(if (needsAttention) PhoneObserverWidgetColorRole.ATTENTION else PhoneObserverWidgetColorRole.ACTIVE)
        },
    )
}
