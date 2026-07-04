// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.fgs

object HeartbeatMonitor {
    fun isFresh(
        nowNanos: Long,
        lastBeatNanos: Long?,
        lastStartRequestedNanos: Long?,
        staleAfterNanos: Long,
        startGraceNanos: Long,
    ): Boolean {
        require(staleAfterNanos >= 0L) { "staleAfterNanos must be non-negative" }
        require(startGraceNanos >= 0L) { "startGraceNanos must be non-negative" }
        return lastBeatNanos?.let { nowNanos - it <= staleAfterNanos } == true ||
            lastStartRequestedNanos?.let { nowNanos - it <= startGraceNanos } == true
    }
}
