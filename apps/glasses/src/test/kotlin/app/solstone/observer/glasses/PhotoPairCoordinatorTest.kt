// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.glasses

import app.solstone.core.diagnostics.PairingFact
import app.solstone.observer.harness.HarnessPairProbeResult
import app.solstone.observer.harness.PairAttemptOutcome
import app.solstone.observer.harness.PairConnectionMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class PhotoPairCoordinatorTest {
    @Test
    fun onChangeScansBoundedRecentCandidates() {
        val f = fixture(
            candidates = listOf(ImageRef(1, NOW), ImageRef(2, NOW)),
            decodedById = mutableMapOf(1L to null, 2L to null),
        )

        f.coordinator.onChange()

        assertEquals(1, f.candidateCalls)
        assertEquals(listOf(1L, 2L), f.decodedIds)
    }

    @Test
    fun queryErrorEmptyCandidatesIsNoOpAndDoesNotDisableFutureScans() {
        val f = fixture(candidates = emptyList())

        f.coordinator.onChange()

        assertEquals(emptyList(), f.pairCalls)
        assertEquals(0, f.startedCueCalls)
        assertEquals(0, f.failedCueCalls)
        assertEquals(0, f.unregisterCalls)

        f.candidates = listOf(ImageRef(1, NOW))
        f.decodedById[1L] = PAIR_A
        f.coordinator.onChange()

        assertEquals(listOf(PAIR_A), f.pairCalls)
        assertEquals(1, f.unregisterCalls)
    }

    @Test
    fun coalescesConcurrentChangesToOneFollowUpScan() {
        val f = fixture(decodedById = mutableMapOf(1L to null))
        var reentered = false
        f.decodeHook = {
            if (!reentered) {
                reentered = true
                f.coordinator.onChange()
            }
        }

        f.coordinator.onChange()

        assertEquals(2, f.candidateCalls)
        assertEquals(listOf(1L, 1L), f.decodedIds)
    }

    @Test
    fun reassertsMaxCandidateBoundFromCoordinator() {
        val candidates = (1L..25L).map { ImageRef(it, NOW) }
        val f = fixture(
            candidates = candidates,
            decodedById = candidates.associate { it.id to null }.toMutableMap(),
        )

        f.coordinator.onChange()

        assertEquals((1L..20L).toList(), f.decodedIds)
    }

    @Test
    fun reassertsMaxAgeBoundFromCoordinator() {
        val f = fixture(
            candidates = listOf(
                ImageRef(1, NOW - PhotoPairCoordinator.MAX_AGE_SECONDS - 1),
                ImageRef(2, NOW),
            ),
            decodedById = mutableMapOf(1L to null, 2L to null),
        )

        f.coordinator.onChange()

        assertEquals(listOf(2L), f.decodedIds)
    }

    @Test
    fun imageBeforeAppStartIsNeverACandidate() {
        val appStart = NOW - 10
        val f = fixture(
            candidates = listOf(ImageRef(1, appStart - 1), ImageRef(2, appStart + 1)),
            decodedById = mutableMapOf(1L to PAIR_A, 2L to PAIR_B),
            appStartSeconds = appStart,
        )

        f.coordinator.onChange()

        assertEquals(listOf(2L), f.decodedIds)
        assertEquals(listOf(PAIR_B), f.pairCalls)
    }

    @Test
    fun pairedOutcomeDedupsUnregistersAndStops() {
        val f = fixture(decodedById = mutableMapOf(1L to PAIR_A, 2L to PAIR_B))

        f.coordinator.onChange()
        f.coordinator.onChange()

        assertEquals(listOf(PAIR_A), f.pairCalls)
        assertEquals(1, f.unregisterCalls)
        assertEquals(listOf(1L, 1L), f.decodedIds)
    }

    @Test
    fun alreadyConnectedOutcomeDedupsUnregistersAndStops() {
        val f = fixture(
            decodedById = mutableMapOf(1L to PAIR_A),
            outcomeByLink = mutableMapOf(PAIR_A to linked(connectionMode = PairConnectionMode.ALREADY_CONNECTED)),
        )

        f.coordinator.onChange()

        assertEquals(listOf(PAIR_A), f.pairCalls)
        assertEquals(1, f.unregisterCalls)
    }

    @Test
    fun retryOutcomeDoesNotDedupAndStopsCurrentScan() {
        val f = fixture(
            candidates = listOf(ImageRef(1, NOW), ImageRef(2, NOW)),
            decodedById = mutableMapOf(1L to PAIR_A, 2L to PAIR_B),
            outcomeByLink = mutableMapOf(PAIR_A to PairAttemptOutcome.Retry, PAIR_B to linked()),
        )

        f.coordinator.onChange()
        f.coordinator.onChange()

        assertEquals(listOf(PAIR_A, PAIR_A), f.pairCalls)
        assertEquals(listOf(1L, 1L), f.decodedIds)
        assertEquals(0, f.unregisterCalls)
    }

    @Test
    fun otherFailureOutcomeDedupsEmitsFailedCueAndContinues() {
        val f = fixture(
            candidates = listOf(ImageRef(1, NOW), ImageRef(2, NOW)),
            decodedById = mutableMapOf(1L to PAIR_A, 2L to null),
            outcomeByLink = mutableMapOf(PAIR_A to PairAttemptOutcome.OtherFailure("IOException", null)),
        )

        f.coordinator.onChange()
        f.coordinator.onChange()

        assertEquals(listOf(PAIR_A), f.pairCalls)
        assertEquals(1, f.failedCueCalls)
        assertEquals(listOf(1L, 2L, 1L, 2L), f.decodedIds)
    }

    @Test
    fun reconnectingOutcomeDedupsAndContinues() {
        val f = fixture(
            candidates = listOf(ImageRef(1, NOW), ImageRef(2, NOW)),
            decodedById = mutableMapOf(1L to PAIR_A, 2L to null),
            outcomeByLink = mutableMapOf(PAIR_A to linked(connectionMode = PairConnectionMode.RECONNECTING)),
        )

        f.coordinator.onChange()
        f.coordinator.onChange()

        assertEquals(listOf(PAIR_A), f.pairCalls)
        assertEquals(listOf(1L, 2L, 1L, 2L), f.decodedIds)
        assertEquals(0, f.unregisterCalls)
    }

    @Test
    fun ignoredMalformedDecodedLinkDedupsWithoutCue() {
        val f = fixture(decodedById = mutableMapOf(1L to "hello"))

        f.coordinator.onChange()
        f.coordinator.onChange()

        assertEquals(emptyList(), f.pairCalls)
        assertEquals(1, f.looksLikeCalls)
        assertEquals(0, f.startedCueCalls)
        assertEquals(0, f.failedCueCalls)
    }

    @Test
    fun decodeNullDoesNotCueOrDedupAndContinues() {
        val f = fixture(
            candidates = listOf(ImageRef(1, NOW), ImageRef(2, NOW)),
            decodedById = mutableMapOf(1L to null, 2L to "hello"),
        )

        f.coordinator.onChange()
        f.coordinator.onChange()

        assertEquals(listOf(1L, 2L, 1L, 2L), f.decodedIds)
        assertEquals(0, f.startedCueCalls)
        assertEquals(0, f.failedCueCalls)
    }

    @Test
    fun startedCueEmittedBeforePairAttemptForPairLink() {
        val f = fixture(decodedById = mutableMapOf(1L to PAIR_A))

        f.coordinator.onChange()

        assertEquals(listOf("started:$PAIR_A", "pair:$PAIR_A"), f.events)
    }

    @Test
    fun attemptedPairLinksAreSkippedOnFutureScans() {
        val f = fixture(
            decodedById = mutableMapOf(1L to PAIR_A),
            outcomeByLink = mutableMapOf(PAIR_A to PairAttemptOutcome.OtherFailure("IOException", null)),
        )

        f.coordinator.onChange()
        f.coordinator.onChange()

        assertEquals(listOf(PAIR_A), f.pairCalls)
        assertEquals(1, f.failedCueCalls)
    }

    @Test
    fun unregisterIsIdempotentAcrossRepeatedSuccess() {
        val f = fixture(decodedById = mutableMapOf(1L to PAIR_A))

        f.coordinator.onChange()
        f.decodedById[1L] = PAIR_B
        f.coordinator.onChange()

        assertEquals(listOf(PAIR_A, PAIR_B), f.pairCalls)
        assertEquals(2, f.unregisterCalls)
    }

    @Test
    fun preflightOfflineCuesDoesNotPairOrBurnAndLaterUsableNetworkAttemptsSameLink() {
        val f = fixture(decodedById = mutableMapOf(1L to PAIR_A), networkUsable = false)

        f.coordinator.onChange()

        assertEquals(1, f.networkCueCalls)
        assertEquals(emptyList(), f.pairCalls)

        f.networkUsable = true
        f.coordinator.onChange()

        assertEquals(listOf(PAIR_A), f.pairCalls)
        assertEquals(1, f.unregisterCalls)
    }

    @Test
    fun repeatedOfflineScansAnnounceOnceUntilUsableNetworkReturns() {
        val f = fixture(decodedById = mutableMapOf(1L to PAIR_A), networkUsable = false)

        f.coordinator.onChange()
        f.coordinator.onChange()

        assertEquals(1, f.networkCueCalls)
        assertEquals(emptyList(), f.pairCalls)

        f.networkUsable = true
        f.outcomeByLink[PAIR_A] = PairAttemptOutcome.NetworkUnavailable
        f.coordinator.onChange()

        assertEquals(2, f.networkCueCalls)
        assertEquals(listOf(PAIR_A), f.pairCalls)
    }

    @Test
    fun postAttemptNetworkUnavailableCuesAndDoesNotBurn() {
        val f = fixture(
            decodedById = mutableMapOf(1L to PAIR_A),
            outcomeByLink = mutableMapOf(PAIR_A to PairAttemptOutcome.NetworkUnavailable),
        )

        f.coordinator.onChange()
        f.outcomeByLink[PAIR_A] = linked()
        f.coordinator.onChange()

        assertEquals(listOf(PAIR_A, PAIR_A), f.pairCalls)
        assertEquals(1, f.networkCueCalls)
        assertEquals(1, f.unregisterCalls)
    }

    @Test
    fun windowClosedOutcomeEmitsRefreshCodeCueAndBurns() {
        val f = fixture(
            decodedById = mutableMapOf(1L to PAIR_A),
            outcomeByLink = mutableMapOf(PAIR_A to PairAttemptOutcome.WindowClosed(401)),
        )

        f.coordinator.onChange()
        f.coordinator.onChange()

        assertEquals(listOf(PAIR_A), f.pairCalls)
        assertEquals(1, f.refreshCueCalls)
    }

    @Test
    fun outcomeLogsAreRedactedAndUseCarriedFields() {
        val cases = listOf(
            linked() to "relay-pair outcome=Linked mode=PAIRING",
            PairAttemptOutcome.NetworkUnavailable to "relay-pair outcome=NetworkUnavailable",
            PairAttemptOutcome.WindowClosed(401) to "relay-pair outcome=WindowClosed status=401",
            PairAttemptOutcome.OtherFailure("IOException", 503) to
                "relay-pair outcome=OtherFailure type=IOException status=503",
            PairAttemptOutcome.Retry to "relay-pair outcome=Retry",
        )
        val logs = mutableListOf<String>()

        cases.forEachIndexed { index, (outcome, _) ->
            val secretLink = "pair:ticket-$index-token-cert-exception-message"
            val f = fixture(
                decodedById = mutableMapOf(1L to secretLink),
                outcomeByLink = mutableMapOf(secretLink to outcome),
            )
            f.coordinator.onChange()
            logs += f.logs
        }

        assertEquals(cases.map { it.second }, logs)
        val captured = logs.joinToString("\n")
        assertFalse(captured.contains("pair:ticket"))
        assertFalse(captured.contains("ticket"))
        assertFalse(captured.contains("token"))
        assertFalse(captured.contains("cert"))
        assertFalse(captured.contains("exception-message"))
    }

    private class Fixture(
        var candidates: List<ImageRef>,
        var pairing: PairingFact,
        var nowSeconds: Long,
        var appStartSeconds: Long,
        val decodedById: MutableMap<Long, String?>,
        val outcomeByLink: MutableMap<String, PairAttemptOutcome>,
        var networkUsable: Boolean,
    ) {
        var candidateCalls = 0
        var unregisterCalls = 0
        var startedCueCalls = 0
        var networkCueCalls = 0
        var refreshCueCalls = 0
        var failedCueCalls = 0
        var looksLikeCalls = 0
        val decodedIds = mutableListOf<Long>()
        val pairCalls = mutableListOf<String>()
        val events = mutableListOf<String>()
        val logs = mutableListOf<String>()
        var decodeHook: ((ImageRef) -> Unit)? = null

        val coordinator = PhotoPairCoordinator(
            PhotoPairSeams(
                recentImageCandidates = {
                    candidateCalls += 1
                    candidates
                },
                appStartSeconds = { appStartSeconds },
                decode = { ref ->
                    decodedIds += ref.id
                    decodeHook?.invoke(ref)
                    decodedById[ref.id]
                },
                pairingFact = { pairing },
                onScannedPairLink = { raw ->
                    pairCalls += raw
                    events += "pair:$raw"
                    outcomeByLink.getValue(raw)
                },
                looksLikePairLink = {
                    looksLikeCalls += 1
                    it.startsWith("pair:")
                },
                unregisterWatcher = { unregisterCalls += 1 },
                onPairingStartedCue = {
                    startedCueCalls += 1
                    events += "started:${decodedById[decodedIds.last()]}"
                },
                onNetworkUnavailableCue = { networkCueCalls += 1 },
                onRefreshCodeCue = { refreshCueCalls += 1 },
                onPairingFailedCue = { failedCueCalls += 1 },
                log = { logs += it },
                isUsableNetworkPresent = { networkUsable },
                nowSeconds = { nowSeconds },
            ),
        )
    }

    private companion object {
        const val NOW = 2_000_000L
        const val PAIR_A = "pair:a"
        const val PAIR_B = "pair:b"

        fun fixture(
            candidates: List<ImageRef> = listOf(ImageRef(1, NOW)),
            pairing: PairingFact = PairingFact.UNPAIRED,
            nowSeconds: Long = NOW,
            appStartSeconds: Long = 0L,
            decodedById: MutableMap<Long, String?> = mutableMapOf(1L to PAIR_A),
            outcomeByLink: MutableMap<String, PairAttemptOutcome> = mutableMapOf(
                PAIR_A to linked(),
                PAIR_B to linked(),
            ),
            networkUsable: Boolean = true,
        ): Fixture =
            Fixture(candidates, pairing, nowSeconds, appStartSeconds, decodedById, outcomeByLink, networkUsable)

        fun linked(connectionMode: PairConnectionMode = PairConnectionMode.PAIRING): PairAttemptOutcome =
            PairAttemptOutcome.Linked(successResult(connectionMode))

        fun successResult(connectionMode: PairConnectionMode = PairConnectionMode.PAIRING): HarnessPairProbeResult =
            HarnessPairProbeResult(
                handshakePinned = true,
                pairStatus = 200,
                statusStatus = 200,
                statusBody = "ok",
                homeLabel = "home",
                endpointHost = "10.0.0.2",
                endpointPort = 7657,
                connectionMode = connectionMode,
            )
    }
}
