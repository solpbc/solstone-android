// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.glasses

import app.solstone.core.model.ReasonCode
import app.solstone.core.model.SourceState

sealed interface RuntimeCommandResult

data object CommandSucceeded : RuntimeCommandResult

data class CommandNeedsAttention(
    val state: SourceState,
    val reason: ReasonCode,
) : RuntimeCommandResult

data class CommandBlocked(
    val reason: RuntimeCommandBlockReason,
) : RuntimeCommandResult

enum class RuntimeCommandBlockReason {
    RuntimeUnavailable,
    MissingPermissions,
    InvalidPairLinkOrCameraBusy,
    PairingProbeFailed,
}
