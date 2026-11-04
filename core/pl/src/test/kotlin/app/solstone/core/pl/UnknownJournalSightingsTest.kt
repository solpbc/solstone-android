// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.pl

import app.solstone.core.identity.JournalMark
import app.solstone.core.identity.JournalMarkIcon
import java.util.concurrent.Executor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UnknownJournalSightingsTest {
    private val queued = ArrayDeque<Runnable>()
    private val derived = mutableListOf<String>()

    private fun sightings(derive: (String) -> JournalMark = { markOf(it).also { _ -> derived += it } }) =
        UnknownJournalSightings(derive, Executor { queued.addLast(it) })

    private fun drain() {
        while (queued.isNotEmpty()) queued.removeFirst().run()
    }

    // Falsified by collapsing rows, or by keying them on anything but the address.
    @Test
    fun eachAddressAndTheRelayKeepsItsOwnRowInTheOrderSeen() {
        val store = sightings()
        store.seen("10.0.0.1:7657", "claim-a")
        store.seen("10.0.0.2:7657", null)
        store.seen(null, "claim-a")

        assertEquals(listOf("10.0.0.1:7657", "10.0.0.2:7657", null), store.current().map { it.address })
    }

    // Falsified by deriving on every sighting: argon2 per poll.
    @Test
    fun aMarkIsWorkedOutOncePerSightingAndAgainOnlyWhenTheClaimChanges() {
        val store = sightings()
        store.seen("10.0.0.1:7657", "claim-a")
        assertIs<ClaimedJournalMark.Pending>(store.current().single().mark)
        drain()
        store.seen("10.0.0.1:7657", "claim-a")
        store.seen("10.0.0.1:7657", "claim-a")
        drain()
        assertEquals(listOf("claim-a"), derived)
        assertEquals(markOf("claim-a"), (store.current().single().mark as ClaimedJournalMark.Derived).mark)

        store.seen("10.0.0.1:7657", "claim-b")
        drain()
        assertEquals(listOf("claim-a", "claim-b"), derived)
        assertEquals(markOf("claim-b"), (store.current().single().mark as ClaimedJournalMark.Derived).mark)
    }

    // Falsified by showing a stale mark under a newer claim after a slow derivation lands.
    @Test
    fun aDerivationOvertakenByANewerClaimIsDropped() {
        val store = sightings()
        store.seen("10.0.0.1:7657", "claim-a")
        store.seen("10.0.0.1:7657", "claim-b")
        drain()
        assertEquals(markOf("claim-b"), (store.current().single().mark as ClaimedJournalMark.Derived).mark)
    }

    @Test
    fun noClaimIsNotPresentedAndDerivesNothing() {
        val store = sightings()
        store.seen("10.0.0.1:7657", null)
        store.seen("10.0.0.2:7657", " ")
        drain()
        assertTrue(store.current().all { it.mark == ClaimedJournalMark.NotPresented })
        assertTrue(store.current().all { it.claimedJournalId == null })
        assertTrue(derived.isEmpty())
    }

    @Test
    fun aDerivationThatFailsIsUnavailableNotNotPresented() {
        val store = sightings { error("no heap") }
        store.seen("10.0.0.1:7657", "claim-a")
        drain()
        assertEquals(ClaimedJournalMark.Unavailable, store.current().single().mark)
    }

    // Falsified by a listener firing on repeats: a listener that reads status dials, which sights
    // again, which would never stop.
    @Test
    fun listenersHearChangesAndNeverARepeat() {
        val store = sightings()
        var heard = 0
        val remove = store.addListener { heard++ }

        store.seen("10.0.0.1:7657", "claim-a")
        assertEquals(1, heard)
        drain()
        assertEquals(2, heard)
        store.seen("10.0.0.1:7657", "claim-a")
        assertEquals(2, heard)
        store.reached("10.0.0.2:7657")
        assertEquals(2, heard)
        store.reached("10.0.0.1:7657")
        assertEquals(3, heard)

        remove()
        store.seen("10.0.0.1:7657", null)
        assertEquals(3, heard)
    }

    @Test
    fun reachingAnAddressClearsOnlyThatRow() {
        val store = sightings()
        store.seen("10.0.0.1:7657", null)
        store.seen(null, null)

        store.reached("10.0.0.1:7657")
        assertEquals(listOf<String?>(null), store.current().map { it.address })
        store.reached(null)
        assertTrue(store.current().isEmpty())
    }

    @Test
    fun theLogPublishesSightingsAndADialThatReachesTheJournalEndsThem() {
        val store = sightings()
        val log = DialEventLog(store) {}

        log.recordFailure("10.0.0.1", 7657, UnknownJournalException("10.0.0.1:7657", "claim-a"))
        log.recordFailure("link.example", 443, UnknownJournalException(null, null), throughRelay = true)
        assertEquals(listOf("10.0.0.1:7657", null), store.current().map { it.address })
        assertEquals("claim-a", store.current().first().claimedJournalId)
        assertNull(store.current().last().claimedJournalId)

        // Another address connecting says nothing about this one.
        log.record("10.0.0.2", 7657, DialOutcome.CONNECTED)
        assertEquals(2, store.current().size)

        log.record("10.0.0.1", 7657, DialOutcome.CONNECTED)
        assertEquals(listOf<String?>(null), store.current().map { it.address })

        // The relay's own address is not the relay's sighting key.
        log.record("link.example", 443, DialOutcome.CONNECTED)
        assertEquals(1, store.current().size)
        log.record("link.example", 443, DialOutcome.CONNECTED, throughRelay = true)
        assertTrue(store.current().isEmpty())
    }

    // Falsified by a dedupe of repeated successes returning before the clear.
    @Test
    fun aRepeatSuccessAtTheSameAddressStillClears() {
        val store = sightings()
        val log = DialEventLog(store) {}
        log.record("10.0.0.1", 7657, DialOutcome.CONNECTED)
        store.seen("10.0.0.1:7657", null)
        log.record("10.0.0.1", 7657, DialOutcome.CONNECTED)
        assertTrue(store.current().isEmpty())
    }

    // A refusal is only ever the paired journal's: it was proven against the pinned CA first.
    @Test
    fun aRefusalFromThePairedJournalEndsTheSighting() {
        val store = sightings()
        val log = DialEventLog(store) {}
        store.seen("10.0.0.1:7657", null)
        log.recordFailure("10.0.0.1", 7657, JournalRefusalException(HandshakeFailure.TLS_ACCESS_DENIED, 49))
        assertTrue(store.current().isEmpty())
    }

    @Test
    fun anOrdinaryFailureEndsNothing() {
        val store = sightings()
        val log = DialEventLog(store) {}
        store.seen("10.0.0.1:7657", null)
        log.recordFailure("10.0.0.1", 7657, java.net.SocketTimeoutException("timed out"))
        assertEquals(1, store.current().size)
    }

    private fun markOf(claim: String) = JournalMark(
        icon1 = JournalMarkIcon(claim, "<svg/>", claim, "#000000", 0),
        icon2 = JournalMarkIcon(claim, "<svg/>", claim, "#ffffff", 45),
        words = listOf(claim, "word"),
    )
}
