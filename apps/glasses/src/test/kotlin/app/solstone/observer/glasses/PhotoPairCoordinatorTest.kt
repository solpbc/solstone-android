// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.glasses

import app.solstone.core.diagnostics.PairingFact
import app.solstone.observer.harness.HarnessPairProbeResult
import app.solstone.observer.harness.PairConnectionMode
import kotlin.test.Test
import kotlin.test.assertEquals

class PhotoPairCoordinatorTest {
    @Test
    fun startupUnregistersAndDoesNotScanWhenAlreadyPaired() {
        val f = fixture(pairing = PairingFact.PAIRED)

        f.coordinator.onStartup()

        assertEquals(1, f.unregisterCalls)
        assertEquals(0, f.candidateCalls)
        assertEquals(emptyList(), f.decodedIds)
    }

    @Test
    fun startupScansBoundedRecentCandidatesWhenUnpaired() {
        val f = fixture(
            candidates = listOf(ImageRef(1, NOW), ImageRef(2, NOW)),
            decodedById = mutableMapOf(1L to null, 2L to null),
        )

        f.coordinator.onStartup()

        assertEquals(1, f.candidateCalls)
        assertEquals(listOf(1L, 2L), f.decodedIds)
    }

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
        assertEquals(0, f.readyCueCalls)
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
            resultByLink = mutableMapOf(PAIR_A to successResult(connectionMode = PairConnectionMode.ALREADY_CONNECTED)),
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
            resultByLink = mutableMapOf(PAIR_A to null, PAIR_B to successResult()),
        )

        f.coordinator.onChange()
        f.coordinator.onChange()

        assertEquals(listOf(PAIR_A, PAIR_A), f.pairCalls)
        assertEquals(listOf(1L, 1L), f.decodedIds)
        assertEquals(0, f.unregisterCalls)
    }

    @Test
    fun failedOutcomeDedupsEmitsFailedCueAndContinues() {
        val f = fixture(
            candidates = listOf(ImageRef(1, NOW), ImageRef(2, NOW)),
            decodedById = mutableMapOf(1L to PAIR_A, 2L to null),
            throwingLinks = mutableSetOf(PAIR_A),
        )

        f.coordinator.onChange()

        assertEquals(listOf(PAIR_A), f.pairCalls)
        assertEquals(1, f.failedCueCalls)
        assertEquals(listOf(1L, 2L), f.decodedIds)
    }

    @Test
    fun reconnectingOutcomeDedupsAndContinues() {
        val f = fixture(
            candidates = listOf(ImageRef(1, NOW), ImageRef(2, NOW)),
            decodedById = mutableMapOf(1L to PAIR_A, 2L to null),
            resultByLink = mutableMapOf(PAIR_A to successResult(connectionMode = PairConnectionMode.RECONNECTING)),
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
        assertEquals(2, f.looksLikeCalls)
        assertEquals(0, f.readyCueCalls)
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
        assertEquals(0, f.readyCueCalls)
        assertEquals(0, f.failedCueCalls)
    }

    @Test
    fun readyCueEmittedBeforePairAttemptForPairLink() {
        val f = fixture(decodedById = mutableMapOf(1L to PAIR_A))

        f.coordinator.onChange()

        assertEquals(listOf("ready:$PAIR_A", "pair:$PAIR_A"), f.events)
    }

    @Test
    fun attemptedPairLinksAreSkippedOnFutureScans() {
        val f = fixture(
            decodedById = mutableMapOf(1L to PAIR_A),
            throwingLinks = mutableSetOf(PAIR_A),
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

    private class Fixture(
        var candidates: List<ImageRef>,
        var pairing: PairingFact,
        var nowSeconds: Long,
        val decodedById: MutableMap<Long, String?>,
        val resultByLink: MutableMap<String, HarnessPairProbeResult?>,
        val throwingLinks: MutableSet<String>,
    ) {
        var candidateCalls = 0
        var unregisterCalls = 0
        var readyCueCalls = 0
        var failedCueCalls = 0
        var looksLikeCalls = 0
        val decodedIds = mutableListOf<Long>()
        val pairCalls = mutableListOf<String>()
        val events = mutableListOf<String>()
        var decodeHook: ((ImageRef) -> Unit)? = null

        val coordinator = PhotoPairCoordinator(
            PhotoPairSeams(
                recentImageCandidates = {
                    candidateCalls += 1
                    candidates
                },
                decode = { ref ->
                    decodedIds += ref.id
                    decodeHook?.invoke(ref)
                    decodedById[ref.id]
                },
                pairingFact = { pairing },
                onScannedPairLink = { raw ->
                    pairCalls += raw
                    events += "pair:$raw"
                    if (raw in throwingLinks) throw IllegalArgumentException("bad link")
                    resultByLink[raw]
                },
                looksLikePairLink = {
                    looksLikeCalls += 1
                    it.startsWith("pair:")
                },
                unregisterWatcher = { unregisterCalls += 1 },
                onPairingReadyCue = {
                    readyCueCalls += 1
                    events += "ready:${decodedById[decodedIds.last()]}"
                },
                onPairingFailedCue = { failedCueCalls += 1 },
                log = {},
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
            decodedById: MutableMap<Long, String?> = mutableMapOf(1L to PAIR_A),
            resultByLink: MutableMap<String, HarnessPairProbeResult?> = mutableMapOf(
                PAIR_A to successResult(),
                PAIR_B to successResult(),
            ),
            throwingLinks: MutableSet<String> = mutableSetOf(),
        ): Fixture =
            Fixture(candidates, pairing, nowSeconds, decodedById, resultByLink, throwingLinks)

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
