// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.diagnostics

import app.solstone.core.model.IdentityState
import app.solstone.core.model.ReasonCode
import app.solstone.core.model.SourceState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class DiagnosticsTest {
    @Test
    fun reduceMapsFailureFactsInPrecedenceOrder() {
        assertEquals(SourceState.NEEDS_ATTENTION to ReasonCode.PERMISSION_REVOKED, reduce(healthy().copy(permissionGranted = false)))
        assertEquals(SourceState.NEEDS_ATTENTION to ReasonCode.SERVICE_KILLED, reduce(healthy().copy(fgsHeartbeatFresh = false)))
        assertEquals(SourceState.NEEDS_ATTENTION to ReasonCode.REBOOTED, reduce(healthy().copy(engineRunning = false)))
        assertEquals(SourceState.NEEDS_ATTENTION to ReasonCode.UNPAIRED, reduce(healthy().copy(pairing = PairingFact.UNPAIRED)))
        assertEquals(SourceState.NEEDS_ATTENTION to ReasonCode.STORAGE_FULL, reduce(healthy().copy(storageOk = false)))
        assertEquals(SourceState.NEEDS_ATTENTION to ReasonCode.PROVIDER_SILENT, reduce(healthy().copy(providerEmitting = false)))
        assertEquals(SourceState.NEEDS_ATTENTION to ReasonCode.AUTH_REVOKED, reduce(healthy().copy(pairing = PairingFact.REVOKED)))
    }

    @Test
    fun reduceDoesNotReturnOnUntilExemptionIsVerified() {
        val result = reduce(healthy().copy(exemptionVerified = false))
        assertEquals(SourceState.NEEDS_ATTENTION to ReasonCode.EXEMPTION_UNVERIFIED, result)
        assertNotEquals(SourceState.ON, result.first)
    }

    @Test
    fun desiredOnWithoutRuntimeFactsNeedsAttention() {
        assertEquals(SourceState.NEEDS_ATTENTION to ReasonCode.REBOOTED, reduce(healthy().copy(engineRunning = false)))
    }

    @Test
    fun reduceMapsS1HonestStateFactsIndividually() {
        assertEquals(SourceState.NEEDS_ATTENTION to ReasonCode.PERMISSION_REVOKED, reduce(healthy().copy(permissionGranted = false)))
        assertEquals(SourceState.NEEDS_ATTENTION to ReasonCode.SERVICE_KILLED, reduce(healthy().copy(fgsHeartbeatFresh = false)))
        assertEquals(SourceState.NEEDS_ATTENTION to ReasonCode.PROVIDER_SILENT, reduce(healthy().copy(providerEmitting = false)))
        assertEquals(SourceState.NEEDS_ATTENTION to ReasonCode.STORAGE_FULL, reduce(healthy().copy(storageOk = false)))
        assertEquals(SourceState.NEEDS_ATTENTION to ReasonCode.EXEMPTION_UNVERIFIED, reduce(healthy().copy(exemptionVerified = false)))
    }

    @Test
    fun reduceMapsOffAndHealthyOn() {
        assertEquals(SourceState.OFF to ReasonCode.NONE, reduce(healthy().copy(desiredOn = false)))
        assertEquals(SourceState.ON to ReasonCode.NONE, reduce(healthy()))
    }

    @Test
    fun reduceMapsPairingFacts() {
        assertEquals(SourceState.NEEDS_ATTENTION to ReasonCode.UNPAIRED, reduce(healthy().copy(pairing = PairingFact.UNPAIRED)))
        assertEquals(SourceState.NEEDS_ATTENTION to ReasonCode.AUTH_REVOKED, reduce(healthy().copy(pairing = PairingFact.REVOKED)))
    }

    @Test
    fun pairingFactOfClassifiesEachCombination() {
        assertEquals(
            PairingFact.UNPAIRED,
            pairingFactOf(credentialPresent = false, endpointPresent = false, relayOriginPresent = false, identityState = null),
        )
        assertEquals(
            PairingFact.REVOKED,
            pairingFactOf(
                credentialPresent = true,
                endpointPresent = false,
                relayOriginPresent = true,
                identityState = IdentityState.REVOKED,
            ),
        )
        assertEquals(
            PairingFact.PAIRED,
            pairingFactOf(
                credentialPresent = true,
                endpointPresent = true,
                relayOriginPresent = false,
                identityState = IdentityState.PAIRED,
            ),
        )
        assertEquals(
            PairingFact.PAIRED,
            pairingFactOf(
                credentialPresent = true,
                endpointPresent = false,
                relayOriginPresent = true,
                identityState = IdentityState.PAIRED,
            ),
        )
        assertEquals(
            PairingFact.UNPAIRED,
            pairingFactOf(
                credentialPresent = true,
                endpointPresent = false,
                relayOriginPresent = false,
                identityState = IdentityState.PAIRED,
            ),
        )
        assertEquals(
            PairingFact.UNPAIRED,
            pairingFactOf(
                credentialPresent = false,
                endpointPresent = true,
                relayOriginPresent = false,
                identityState = IdentityState.PAIRED,
            ),
        )
        assertEquals(
            PairingFact.UNPAIRED,
            pairingFactOf(
                credentialPresent = false,
                endpointPresent = false,
                relayOriginPresent = false,
                identityState = IdentityState.UNPAIRED,
            ),
        )
    }

    private fun healthy() = SourceFacts(
        desiredOn = true,
        engineRunning = true,
        permissionGranted = true,
        fgsHeartbeatFresh = true,
        providerEmitting = true,
        storageOk = true,
        pairing = PairingFact.PAIRED,
        exemptionVerified = true,
    )
}
