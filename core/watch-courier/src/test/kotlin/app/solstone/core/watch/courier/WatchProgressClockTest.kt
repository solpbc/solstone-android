// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.watch.courier

import kotlin.test.Test
import kotlin.test.assertEquals

class WatchProgressClockTest {
    @Test
    fun missingFirstAudioReachesAttentionWithinOneMinute() {
        val snapshot = WatchRecorderSnapshot(startSucceeded = true, startedAtEpochMs = 1000, startedAtElapsedMs = 1000)
        assertEquals(WatchSessionState.STARTING, WatchSessionReducer.reduce(snapshot, 2000, nowElapsedMs = 2000).state)
        assertEquals(WatchSessionState.INTERRUPTED, WatchSessionReducer.reduce(snapshot, 11000, nowElapsedMs = 11000).state)
        assertEquals(WatchSessionState.ATTENTION, WatchSessionReducer.reduce(snapshot, 61000, nowElapsedMs = 61000).state)
    }

    @Test
    fun progressUsesMonotonicTimeWhenTheWallClockChanges() {
        val snapshot = WatchRecorderSnapshot(
            startSucceeded = true,
            lastInputTimestampEpochMs = 1900,
            startedAtElapsedMs = 100,
            lastInputElapsedMs = 900,
        )
        assertEquals(WatchSessionState.ACTIVE, WatchSessionReducer.reduce(snapshot, 3_600_000, nowElapsedMs = 950).state)
        assertEquals(WatchSessionState.ATTENTION, WatchSessionReducer.reduce(snapshot, 1901, nowElapsedMs = 61_000).state)
    }
}
