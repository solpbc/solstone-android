// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.watch.courier

data class WatchRecorderSnapshot(
    val startSucceeded: Boolean = false,
    val lastInputTimestampEpochMs: Long? = null,
    val clientSilenced: Boolean = false,
    val terminalReason: WindowPumpStopReason? = null,
    val startedAtEpochMs: Long? = null,
    val firstInputTimestampEpochMs: Long? = null,
    val startedAtElapsedMs: Long? = null,
    val lastInputElapsedMs: Long? = null,
)
