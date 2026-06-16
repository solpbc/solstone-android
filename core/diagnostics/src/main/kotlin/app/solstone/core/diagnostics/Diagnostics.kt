// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.diagnostics

import app.solstone.core.model.ReasonCode
import app.solstone.core.model.SourceState

data class SourceFacts(
  val desiredOn: Boolean,
  val engineRunning: Boolean,
  val permissionGranted: Boolean,
  val fgsHeartbeatFresh: Boolean,
  val providerEmitting: Boolean,
  val storageOk: Boolean,
  val linkPaired: Boolean,
  val authValid: Boolean,
  val exemptionVerified: Boolean,
)

fun reduce(f: SourceFacts): Pair<SourceState, ReasonCode> =
    when {
        !f.permissionGranted -> SourceState.NEEDS_ATTENTION to ReasonCode.PERMISSION_REVOKED
        !f.authValid -> SourceState.NEEDS_ATTENTION to ReasonCode.AUTH_REVOKED
        f.desiredOn && !f.fgsHeartbeatFresh -> SourceState.NEEDS_ATTENTION to ReasonCode.SERVICE_KILLED
        !f.storageOk -> SourceState.NEEDS_ATTENTION to ReasonCode.STORAGE_FULL
        f.desiredOn && !f.linkPaired -> SourceState.NEEDS_ATTENTION to ReasonCode.UNPAIRED
        f.desiredOn && !f.providerEmitting -> SourceState.NEEDS_ATTENTION to ReasonCode.PROVIDER_SILENT
        f.desiredOn && !f.engineRunning -> SourceState.NEEDS_ATTENTION to ReasonCode.REBOOTED
        f.desiredOn && !f.exemptionVerified -> SourceState.NEEDS_ATTENTION to ReasonCode.EXEMPTION_UNVERIFIED
        !f.desiredOn -> SourceState.OFF to ReasonCode.NONE
        else -> SourceState.ON to ReasonCode.NONE
    }

/**
 * Redacted diagnostics carry only states, counts, short error codes, and hash prefixes.
 * They must never contain payload bytes, file contents, pair links, keys, or media.
 */
data class RedactedDiagnostics(
    val sourceStates: Map<String, SourceState>,
    val reasonCounts: Map<ReasonCode, Int>,
    val queuedBundleCount: Int,
    val failedBundleCount: Int,
    val lastErrorCode: String?,
    val fingerprintHashPrefix: String?,
)
