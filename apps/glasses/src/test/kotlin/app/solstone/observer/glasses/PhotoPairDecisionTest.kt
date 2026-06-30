// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.glasses

import app.solstone.observer.harness.HarnessPairProbeResult
import app.solstone.observer.harness.PairAttemptOutcome
import app.solstone.observer.harness.PairConnectionMode
import kotlin.test.Test
import kotlin.test.assertEquals

class PhotoPairDecisionTest {
    @Test
    fun mapsPairedResult() {
        assertEquals(
            PhotoPairOutcome.PAIRED,
            decidePhotoPair(PairAttemptOutcome.Linked(successResult())),
        )
    }

    @Test
    fun mapsAlreadyConnectedResult() {
        assertEquals(
            PhotoPairOutcome.ALREADY_CONNECTED,
            decidePhotoPair(PairAttemptOutcome.Linked(successResult(connectionMode = PairConnectionMode.ALREADY_CONNECTED))),
        )
    }

    @Test
    fun mapsReconnectingResult() {
        assertEquals(
            PhotoPairOutcome.RECONNECTING,
            decidePhotoPair(PairAttemptOutcome.Linked(successResult(connectionMode = PairConnectionMode.RECONNECTING))),
        )
    }

    @Test
    fun mapsRetry() {
        assertEquals(PhotoPairOutcome.RETRY, decidePhotoPair(PairAttemptOutcome.Retry))
    }

    @Test
    fun mapsNetworkUnavailable() {
        assertEquals(PhotoPairOutcome.NETWORK_UNAVAILABLE, decidePhotoPair(PairAttemptOutcome.NetworkUnavailable))
    }

    @Test
    fun mapsWindowClosedToRefreshCode() {
        assertEquals(PhotoPairOutcome.REFRESH_CODE, decidePhotoPair(PairAttemptOutcome.WindowClosed(401)))
    }

    @Test
    fun mapsOtherFailureToFailed() {
        assertEquals(PhotoPairOutcome.FAILED, decidePhotoPair(PairAttemptOutcome.OtherFailure("IOException", null)))
    }

    private fun successResult(connectionMode: PairConnectionMode = PairConnectionMode.PAIRING) =
        HarnessPairProbeResult(
            handshakePinned = true,
            pairStatus = 200,
            statusStatus = 503,
            statusBody = "unavailable",
            homeLabel = "home",
            endpointHost = "10.0.0.2",
            endpointPort = 7657,
            connectionMode = connectionMode,
        )
}
