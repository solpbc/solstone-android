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
        PhoneWidgetStartOutcome.None -> when {
            // ⚠ Ordered before the wish check: an unexpressed source resolves its wish to `Off`, so
            // keying on the wish alone would render it as an owner's choice to be off.
            audio?.wishExpressed == false ->
                ObserverStatus(SourceState.READY_TO_SET_UP, ReasonCode.NONE)
            audioWish == SourceWish.Off -> ObserverStatus(SourceState.OFF, ReasonCode.NONE)
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
    // 🔴 A FAULT, not merely "not running" — this was `!audioChecked`, which coloured the widget
    // for attention whenever audio was anything but on. A source the owner never set up would have
    // rendered `ready to set up` in attention colours; so would one they deliberately switched off,
    // and so would one transitionally setting up. ✅ A refusal is still a fault even though it
    // presents as `off`, which is why the reason is the second half of the test and not just the
    // state word.
    // ⚠ `readModel == null` first, and it is the same distinction the wish store draws between
    // Unreadable and Absent: we could not read the sources, which is not the same as reading them
    // and finding nothing wrong. ⛔ Rendering "fine" over an unknown is the confident wrongness the
    // honest-state rules exist to prevent.
    val needsAttention = readModel == null ||
        presentation.state == SourceState.NEEDS_ATTENTION ||
        presentation.reason != ReasonCode.NONE
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
