// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.diagnostics

import app.solstone.core.model.IdentityState
import app.solstone.core.model.ReasonCode
import app.solstone.core.model.SilencedFact
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
  val silenced: SilencedFact,
  val engineStartIssued: Boolean = true,
  val conditionNeedsAttention: Boolean = false,
  val paused: Boolean = false,
  val foregroundTypeHeld: Boolean = true,
  val startRefused: Boolean = false,
  val identityPersistenceOk: Boolean = true,
  // ⚠ Whether intake has ever been started, so a stale heartbeat can be told apart from one
  // that never beat. `fgsHeartbeatFresh = false` has TWO causes and only one is a fault: the
  // service beat and went silent, or it was never asked to start at all. `providerEmitting`
  // carries the same conflation and [engineStartIssued] already separates it. Defaults true so a
  // caller that only models the failure keeps its existing meaning.
  val fgsStartEvidence: Boolean = true,
  // ⚠ Whether the OWNER has expressed a wish for this source, which is not the same as the wish
  // having a value — a defaulted wish is not an expressed one. Defaults true so a caller that
  // models only the failure keeps its meaning.
  val wishExpressed: Boolean = true,
  // ⚠ Whether a runtime permission request for THIS source is on screen right now. A missing
  // permission has two meanings and only one is a fault: the owner declined it, or the system is
  // asking them this instant. Defaults false so a caller that models only the settled state keeps
  // its meaning.
  val permissionRequestInFlight: Boolean = false,
)

// NONE is correct for off, on, paused, and setting up. Only a needs-attention row with NONE is a
// defect. DESIRED_OFF is deliberately not emitted on the shell path, so an off source reads OFF +
// NONE by design.
fun reduce(f: SourceFacts): Pair<SourceState, ReasonCode> =
    when {
        // 🔴 FIRST, and that position is the requirement rather than a detail. A source the owner has
        // never asked for has not FAILED — it has not been set up, and reporting it as a fault
        // manufactures an owner intent and then blames them for it. Four branches below carry no
        // `desiredOn` gate (`permissionGranted`, `pairing == REVOKED`, `identityPersistenceOk`,
        // `storageOk`), so anything short of first lets one of them claim a never-chosen source.
        !f.wishExpressed -> SourceState.READY_TO_SET_UP to ReasonCode.NONE
        // 🔴 Before the fault branch, and for the same reason `!wishExpressed` is before it: the
        // owner has just said yes and the system is asking them. Reporting `needs attention:
        // permissions needed` in that window is a fault word arriving as the direct result of
        // saying yes — caught on the Play demonstration video, rendered behind the dialog it was
        // reacting to.
        !f.permissionGranted && f.permissionRequestInFlight -> SourceState.SETTING_UP to ReasonCode.NONE
        !f.permissionGranted -> SourceState.NEEDS_ATTENTION to ReasonCode.PERMISSION_REVOKED
        f.desiredOn && !f.foregroundTypeHeld -> SourceState.NEEDS_ATTENTION to ReasonCode.FOREGROUND_TYPE_NOT_HELD
        f.desiredOn && f.startRefused -> SourceState.NEEDS_ATTENTION to ReasonCode.FOREGROUND_START_NOT_ALLOWED
        f.pairing == PairingFact.REVOKED -> SourceState.NEEDS_ATTENTION to ReasonCode.AUTH_REVOKED
        f.desiredOn && f.fgsStartEvidence && !f.fgsHeartbeatFresh ->
            SourceState.NEEDS_ATTENTION to ReasonCode.SERVICE_KILLED
        !f.identityPersistenceOk -> SourceState.NEEDS_ATTENTION to ReasonCode.PERSISTENCE_FAILED
        !f.storageOk -> SourceState.NEEDS_ATTENTION to ReasonCode.STORAGE_FULL
        f.desiredOn && f.pairing != PairingFact.PAIRED -> SourceState.NEEDS_ATTENTION to ReasonCode.UNPAIRED
        f.desiredOn && f.engineStartIssued && !f.providerEmitting ->
            SourceState.NEEDS_ATTENTION to ReasonCode.PROVIDER_SILENT
        f.desiredOn && f.conditionNeedsAttention -> SourceState.NEEDS_ATTENTION to ReasonCode.PROVIDER_SILENT
        f.desiredOn && f.engineStartIssued && !f.engineRunning -> SourceState.NEEDS_ATTENTION to ReasonCode.REBOOTED
        f.desiredOn && !f.engineStartIssued && !f.engineRunning -> SourceState.SETTING_UP to ReasonCode.NONE
        f.desiredOn && (f.silenced == SilencedFact.SILENCED || f.paused) -> SourceState.PAUSED to ReasonCode.NONE
        !f.desiredOn -> SourceState.OFF to ReasonCode.NONE
        else -> SourceState.ON to ReasonCode.NONE
    }
