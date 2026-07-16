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
    val relayOriginPresent: Boolean,
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
        pairing = pairingFactOf(
            inputs.credentialPresent,
            inputs.endpointPresent,
            inputs.relayOriginPresent,
            inputs.identityState,
        ),
        exemptionVerified = inputs.exemptionVerified,
    )
    val (state, reason) = reduce(facts)
    return HarnessDiagnostics(state = state, reason = reason, display = displayFor(state, reason))
}

fun displayFor(state: SourceState, reason: ReasonCode): String =
    reason.text()?.let { "${state.label()}: $it" } ?: state.label()

private fun ReasonCode.text(): String? =
    when (this) {
        ReasonCode.NONE -> null
        ReasonCode.PERMISSION_REVOKED -> "permissions needed"
        ReasonCode.SERVICE_KILLED -> "observing was stopped by the system"
        ReasonCode.REBOOTED -> "restart observing after reboot"
        ReasonCode.UNPAIRED -> "not paired with your journal"
        ReasonCode.STORAGE_FULL -> "phone storage is full"
        ReasonCode.PROVIDER_SILENT -> "nothing observed recently"
        ReasonCode.AUTH_REVOKED -> "access was revoked - pair again"
        ReasonCode.EXEMPTION_UNVERIFIED -> "battery settings may stop sol in the background"
        ReasonCode.TRANSPORT_UNAVAILABLE -> "can't reach your journal"
        ReasonCode.FOREGROUND_START_NOT_ALLOWED -> "open sol to resume observing"
        ReasonCode.DESIRED_OFF -> "observing is turned off"
    }

private fun SourceState.label(): String =
    when (this) {
        SourceState.OFF -> "Off"
        SourceState.SETTING_UP -> "Setting up"
        SourceState.ON -> "On"
        SourceState.PAUSED -> "Paused"
        SourceState.NEEDS_ATTENTION -> "Needs attention"
    }
