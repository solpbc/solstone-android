// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.glasses

import app.solstone.observer.harness.HarnessPairProbeResult
import app.solstone.observer.harness.PairConnectionMode
import kotlin.test.Test
import kotlin.test.assertEquals

class PhotoPairDecisionTest {
    @Test
    fun ignoresMissingQrWithoutCallingPairing() {
        var calls = 0

        val outcome = decidePhotoPair(
            decoded = null,
            looksLikePairLink = { true },
            onScannedPairLink = {
                calls++
                successResult()
            },
        )

        assertEquals(PhotoPairOutcome.IGNORED, outcome)
        assertEquals(0, calls)
    }

    @Test
    fun ignoresNonPairQrWithoutCallingPairing() {
        var calls = 0

        val outcome = decidePhotoPair(
            decoded = "hello",
            looksLikePairLink = { false },
            onScannedPairLink = {
                calls++
                successResult()
            },
        )

        assertEquals(PhotoPairOutcome.IGNORED, outcome)
        assertEquals(0, calls)
    }

    @Test
    fun marksPairingExceptionFailed() {
        val outcome = decidePhotoPair(
            decoded = "https://link.solpbc.org/p#abc",
            looksLikePairLink = { true },
            onScannedPairLink = { throw IllegalArgumentException("bad link") },
        )

        assertEquals(PhotoPairOutcome.FAILED, outcome)
    }

    @Test
    fun marksBusyCameraRetry() {
        val outcome = decidePhotoPair(
            decoded = "https://link.solpbc.org/p#abc",
            looksLikePairLink = { true },
            onScannedPairLink = { null },
        )

        assertEquals(PhotoPairOutcome.RETRY, outcome)
    }

    @Test
    fun marksReturnedResultPairedEvenWhenStatusProbeIsNotOk() {
        val outcome = decidePhotoPair(
            decoded = "https://link.solpbc.org/p#abc",
            looksLikePairLink = { true },
            onScannedPairLink = { successResult(statusStatus = 503) },
        )

        assertEquals(PhotoPairOutcome.PAIRED, outcome)
    }

    @Test
    fun mapsAlreadyConnectedResult() {
        val outcome = decidePhotoPair(
            decoded = "https://link.solpbc.org/p#abc",
            looksLikePairLink = { true },
            onScannedPairLink = { successResult(connectionMode = PairConnectionMode.ALREADY_CONNECTED) },
        )

        assertEquals(PhotoPairOutcome.ALREADY_CONNECTED, outcome)
    }

    @Test
    fun mapsReconnectingResult() {
        val outcome = decidePhotoPair(
            decoded = "https://link.solpbc.org/p#abc",
            looksLikePairLink = { true },
            onScannedPairLink = { successResult(connectionMode = PairConnectionMode.RECONNECTING) },
        )

        assertEquals(PhotoPairOutcome.RECONNECTING, outcome)
    }

    private fun successResult(
        statusStatus: Int = 200,
        connectionMode: PairConnectionMode = PairConnectionMode.PAIRING,
    ) =
        HarnessPairProbeResult(
            handshakePinned = true,
            pairStatus = 200,
            statusStatus = statusStatus,
            statusBody = "unavailable",
            homeLabel = "home",
            endpointHost = "10.0.0.2",
            endpointPort = 7657,
            connectionMode = connectionMode,
        )
}
