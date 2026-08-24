// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.harness

import app.solstone.core.diagnostics.PairingFact
import app.solstone.core.diagnostics.SourceFacts
import app.solstone.core.diagnostics.reduce
import app.solstone.core.model.ReasonCode
import app.solstone.core.model.SilencedFact
import app.solstone.core.model.SourceState
import kotlin.test.Test
import kotlin.test.assertEquals

class HarnessFactsAgreementTest {
    @Test
    fun displayContractCoverageCoversBothEnums() {
        // This covers display inputs, not reducer-reachable state and reason pairs.
        val displayCoverage = mapOf(
            SourceState.OFF to setOf(ReasonCode.NONE),
            SourceState.SETTING_UP to setOf(ReasonCode.NONE),
            SourceState.ON to setOf(ReasonCode.NONE),
            SourceState.PAUSED to setOf(ReasonCode.NONE),
            SourceState.NEEDS_ATTENTION to ReasonCode.entries.toSet(),
        )

        assertEquals(SourceState.entries.toSet(), displayCoverage.keys)
        assertEquals(ReasonCode.entries.toSet(), displayCoverage.values.flatten().toSet())
    }

    @Test
    fun reducerHasWitnessForEveryShellState() {
        val facts = mapOf(
            SourceState.OFF to healthy().copy(desiredOn = false),
            SourceState.SETTING_UP to healthy().copy(engineRunning = false, engineStartIssued = false),
            SourceState.ON to healthy(),
            SourceState.PAUSED to healthy().copy(silenced = SilencedFact.SILENCED),
            SourceState.NEEDS_ATTENTION to healthy().copy(permissionGranted = false),
        )

        assertEquals(SourceState.entries.toSet(), facts.keys)
        assertEquals(SourceState.entries.toSet(), facts.values.map { reduce(it).first }.toSet())
    }

    private fun healthy() = SourceFacts(
        desiredOn = true,
        engineRunning = true,
        permissionGranted = true,
        fgsHeartbeatFresh = true,
        providerEmitting = true,
        storageOk = true,
        pairing = PairingFact.PAIRED,
        silenced = SilencedFact.NOT_SILENCED,
    )
}
