// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.harness

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class RoutinePlStatusTest {
    @Test
    fun aRoutineRefreshReusesARecentProbeUntilItAges() {
        var now = 0L
        var probes = 0
        val f = fixture(
            plStatusProbe = PlStatusProbe { probes += 1; HarnessPlStatus.Reachable(200) },
            monotonicMs = { now },
        )

        f.controller.routinePlStatus()
        now += 5_000
        f.controller.routinePlStatus()
        assertEquals(1, probes, "the 5 s poll does not open a session per tick")

        now += ROUTINE_PROBE_MAX_AGE_MS
        f.controller.routinePlStatus()
        assertEquals(2, probes)
    }

    @Test
    fun anOwnersCheckAndAPairingChangeProbeFresh() {
        var probes = 0
        val f = fixture(
            plStatusProbe = PlStatusProbe { probes += 1; HarnessPlStatus.Reachable(200) },
            monotonicMs = { 0L },
        )
        f.controller.routinePlStatus()

        f.controller.probePlStatus()
        assertEquals(2, probes, "an explicit check never reads the cache")

        f.identityStore.save(pairedHome(instanceId = "home-2"))
        assertIs<HarnessPlStatus.Reachable>(f.controller.routinePlStatus())
        assertEquals(3, probes, "a different pairing is never answered from the old one's probe")
    }
}
