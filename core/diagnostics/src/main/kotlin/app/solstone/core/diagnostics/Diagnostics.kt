// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.diagnostics

import app.solstone.core.model.IdentityState
import app.solstone.core.model.ReasonCode
import app.solstone.core.model.SourceState

enum class PairingFact { UNPAIRED, PAIRED, REVOKED }

fun pairingFactOf(
    credentialPresent: Boolean,
    endpointPresent: Boolean,
    relayOriginPresent: Boolean,
    identityState: IdentityState?,
): PairingFact =
    when {
        identityState == IdentityState.REVOKED -> PairingFact.REVOKED
        identityState == IdentityState.PAIRED && credentialPresent && (endpointPresent || relayOriginPresent) -> PairingFact.PAIRED
        else -> PairingFact.UNPAIRED
    }

data class SourceFacts(
  val desiredOn: Boolean,
  val engineRunning: Boolean,
  val permissionGranted: Boolean,
  val fgsHeartbeatFresh: Boolean,
  val providerEmitting: Boolean,
  val storageOk: Boolean,
  val pairing: PairingFact,
  val exemptionVerified: Boolean,
)

fun reduce(f: SourceFacts): Pair<SourceState, ReasonCode> =
    when {
        !f.permissionGranted -> SourceState.NEEDS_ATTENTION to ReasonCode.PERMISSION_REVOKED
        f.pairing == PairingFact.REVOKED -> SourceState.NEEDS_ATTENTION to ReasonCode.AUTH_REVOKED
        f.desiredOn && !f.fgsHeartbeatFresh -> SourceState.NEEDS_ATTENTION to ReasonCode.SERVICE_KILLED
        !f.storageOk -> SourceState.NEEDS_ATTENTION to ReasonCode.STORAGE_FULL
        f.desiredOn && f.pairing != PairingFact.PAIRED -> SourceState.NEEDS_ATTENTION to ReasonCode.UNPAIRED
        f.desiredOn && !f.providerEmitting -> SourceState.NEEDS_ATTENTION to ReasonCode.PROVIDER_SILENT
        f.desiredOn && !f.engineRunning -> SourceState.NEEDS_ATTENTION to ReasonCode.REBOOTED
        f.desiredOn && !f.exemptionVerified -> SourceState.NEEDS_ATTENTION to ReasonCode.EXEMPTION_UNVERIFIED
        !f.desiredOn -> SourceState.OFF to ReasonCode.NONE
        else -> SourceState.ON to ReasonCode.NONE
    }
