// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.fgs

object HeartbeatMonitor {
    fun isFresh(nowNanos: Long, lastBeatNanos: Long?, staleAfterNanos: Long): Boolean {
        require(staleAfterNanos >= 0L) { "staleAfterNanos must be non-negative" }
        if (lastBeatNanos == null) return false
        return nowNanos - lastBeatNanos <= staleAfterNanos
    }
}
