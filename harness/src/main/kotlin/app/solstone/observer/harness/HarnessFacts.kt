// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.harness

import app.solstone.core.diagnostics.SourceFacts
import app.solstone.core.diagnostics.pairingFactOf
import app.solstone.core.diagnostics.reduce
import app.solstone.core.model.IdentityState
import app.solstone.core.model.ReasonCode
import app.solstone.core.model.SourceState
import app.solstone.platform.fgs.PermissionStatus

data class HarnessFactInputs(
    val desiredOn: Boolean,
    val engineRunning: Boolean,
    val permissionStatus: PermissionStatus,
    val fgsHeartbeatFresh: Boolean,
    val providerEmitting: Boolean,
    val storageOk: Boolean,
    val credentialPresent: Boolean,
    val endpointPresent: Boolean,
    val identityState: IdentityState?,
    val exemptionVerified: Boolean,
)

fun assembleDiagnostics(inputs: HarnessFactInputs): HarnessDiagnostics {
    val facts = SourceFacts(
        desiredOn = inputs.desiredOn,
        engineRunning = inputs.engineRunning,
        permissionGranted = inputs.permissionStatus.allRequiredGranted,
        fgsHeartbeatFresh = inputs.fgsHeartbeatFresh,
        providerEmitting = inputs.providerEmitting,
        storageOk = inputs.storageOk,
        pairing = pairingFactOf(inputs.credentialPresent, inputs.endpointPresent, inputs.identityState),
        exemptionVerified = inputs.exemptionVerified,
    )
    val (state, reason) = reduce(facts)
    return HarnessDiagnostics(state = state, reason = reason, display = displayFor(state, reason))
}

fun displayFor(state: SourceState, reason: ReasonCode): String =
    if (reason == ReasonCode.NONE) {
        state.label()
    } else {
        "${state.label()}: ${reason.name.lowercase()}"
    }

private fun SourceState.label(): String =
    when (this) {
        SourceState.OFF -> "Off"
        SourceState.SETTING_UP -> "Setting up"
        SourceState.ON -> "On"
        SourceState.PAUSED -> "Paused"
        SourceState.NEEDS_ATTENTION -> "Needs attention"
    }
