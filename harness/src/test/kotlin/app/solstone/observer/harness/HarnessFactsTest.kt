// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.harness

import app.solstone.core.model.IdentityState
import app.solstone.core.model.ReasonCode
import app.solstone.core.model.SilencedFact
import app.solstone.core.model.SourceState
import app.solstone.core.sources.SourceCondition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class HarnessFactsTest {
    @Test
    fun missingPermissionsAndStaleHeartbeatAreNeverOn() {
        val cameraDenied = fixture(permissionStatus = grantedPermissions().copy(cameraGranted = false))
        assertNotEquals(SourceState.ON, cameraDenied.controller.diagnostics().state)

        val f = fixture()
        f.controller.start()
        f.heartbeat.fresh = false
        val diagnostics = f.controller.diagnostics()
        assertEquals(SourceState.NEEDS_ATTENTION, diagnostics.state)
        assertEquals(ReasonCode.SERVICE_KILLED, diagnostics.reason)
    }

    @Test
    fun relayPairedHealthyInputsReduceToOn() {
        val diagnostics = assembleDiagnostics(
            HarnessFactInputs(
                desiredOn = true,
                engineRunning = true,
                permissionStatus = grantedPermissions(),
                fgsHeartbeatFresh = true,
                providerEmitting = true,
                storageOk = true,
                credentialPresent = true,
                endpointPresent = false,
                relayOriginPresent = true,
                identityState = IdentityState.PAIRED,
                silenced = SilencedFact.NOT_SILENCED,
            ),
        )

        assertEquals(SourceState.ON, diagnostics.state)
        assertEquals(ReasonCode.NONE, diagnostics.reason)
    }

    @Test
    fun sourceConditionsAggregateAndReachDiagnosticsWithoutMergingFreshness() {
        val unknown = sourceRuntimeSnapshotOf(
            engineRunning = true,
            providerEmitting = true,
            storageOk = true,
            conditions = listOf(condition(SilencedFact.NOT_SILENCED), condition(SilencedFact.UNKNOWN)),
        )
        assertEquals(SilencedFact.UNKNOWN, unknown.silenced)
        assertEquals(SourceState.ON, diagnosticsFor(unknown.silenced).state)

        val silenced = sourceRuntimeSnapshotOf(
            engineRunning = true,
            providerEmitting = true,
            storageOk = true,
            conditions = listOf(
                condition(SilencedFact.NOT_SILENCED),
                condition(SilencedFact.UNKNOWN),
                condition(SilencedFact.SILENCED),
            ),
        )
        assertEquals(SilencedFact.SILENCED, silenced.silenced)
        assertEquals(SourceState.PAUSED, diagnosticsFor(silenced.silenced).state)
        assertEquals(
            SilencedFact.UNKNOWN,
            sourceRuntimeSnapshotOf(true, true, true, emptyList()).silenced,
        )
    }

    @Test
    fun allOmittedConditionsAfterStartNeedAttentionInsteadOfOn() {
        val snapshot = sourceRuntimeSnapshotOf(
            engineRunning = false,
            providerEmitting = true,
            storageOk = true,
            conditions = emptyList(),
            engineStartIssued = true,
        )
        val f = fixture(snapshot = snapshot)
        f.desiredStore.setDesiredOn(true)

        val diagnostics = f.controller.diagnostics()

        assertEquals(SourceState.NEEDS_ATTENTION, diagnostics.state)
        assertEquals(ReasonCode.REBOOTED, diagnostics.reason)
    }

    private fun diagnosticsFor(silenced: SilencedFact) =
        assembleDiagnostics(
            HarnessFactInputs(
                desiredOn = true,
                engineRunning = true,
                permissionStatus = grantedPermissions(),
                fgsHeartbeatFresh = true,
                providerEmitting = true,
                storageOk = true,
                credentialPresent = true,
                endpointPresent = true,
                relayOriginPresent = false,
                identityState = IdentityState.PAIRED,
                silenced = silenced,
            ),
        )

    private fun condition(silenced: SilencedFact) =
        SourceCondition(
            desiredOn = true,
            running = true,
            available = true,
            needsAttention = false,
            paused = false,
            silenced = silenced,
        )
}
