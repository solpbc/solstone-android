// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.harness

import app.solstone.core.diagnostics.SourceFacts
import app.solstone.core.diagnostics.pairingFactOf
import app.solstone.core.diagnostics.reduce
import app.solstone.core.model.IdentityState
import app.solstone.core.model.ReasonCode
import app.solstone.core.model.SilencedFact
import app.solstone.core.model.SourceState
import app.solstone.platform.fgs.CaptureForegroundType
import app.solstone.platform.fgs.PermissionStatus
import app.solstone.platform.fgs.satisfiableCaptureForegroundTypes

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
    val silenced: SilencedFact,
    val identityPersistenceOk: Boolean = true,
    val engineStartIssued: Boolean = true,
    val fgsStartEvidence: Boolean = true,
    val declaredCaptureForegroundTypes: Set<CaptureForegroundType> = setOf(
        CaptureForegroundType.MICROPHONE,
        CaptureForegroundType.LOCATION,
        CaptureForegroundType.CAMERA,
    ),
)

fun assembleDiagnostics(inputs: HarnessFactInputs): HarnessDiagnostics {
    val facts = sourceFactsFor(inputs)
    val (state, reason) = reduce(facts)
    return HarnessDiagnostics(state = state, reason = reason, display = displayFor(state, reason))
}

internal fun sourceFactsFor(inputs: HarnessFactInputs): SourceFacts {
    val subset = satisfiableCaptureForegroundTypes(
        microphoneGranted = inputs.permissionStatus.microphoneGranted,
        cameraGranted = inputs.permissionStatus.cameraGranted,
        locationGranted = inputs.permissionStatus.locationGranted,
        declared = inputs.declaredCaptureForegroundTypes,
    )
    return SourceFacts(
        desiredOn = inputs.desiredOn,
        engineRunning = inputs.engineRunning,
        permissionGranted = subset.isNotEmpty(),
        fgsHeartbeatFresh = inputs.fgsHeartbeatFresh,
        providerEmitting = inputs.providerEmitting,
        storageOk = inputs.storageOk,
        identityPersistenceOk = inputs.identityPersistenceOk,
        pairing = pairingFactOf(
            inputs.credentialPresent,
            inputs.endpointPresent,
            inputs.relayOriginPresent,
            inputs.identityState,
        ),
        silenced = inputs.silenced,
        engineStartIssued = inputs.engineStartIssued,
        fgsStartEvidence = inputs.fgsStartEvidence,
    )
}

// ⛔ The reason half is NOT written here. It is [reasonDiagnosis]'s, and the local copy this
// function used to carry is exactly how `open sol to resume observing` outlived the product name.
fun displayFor(state: SourceState, reason: ReasonCode): String =
    reasonDiagnosis(reason)?.let { "${state.label()}: $it" } ?: state.label()

// The state words are locked lowercase cross-platform; ⛔ a surface does not get to Title-Case
// them because it happens to be a diagnostic one.
private fun SourceState.label(): String =
    when (this) {
        SourceState.OFF -> "off"
        SourceState.SETTING_UP -> "setting up"
        SourceState.ON -> "on"
        SourceState.PAUSED -> "paused"
        SourceState.NEEDS_ATTENTION -> "needs attention"
    }
