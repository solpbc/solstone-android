// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.pl

import app.solstone.core.identity.JournalMark
import app.solstone.core.identity.GraphRevisions
import app.solstone.core.identity.JournalMarkIcon
import app.solstone.core.identity.PairingGraphSnapshot
import app.solstone.core.identity.PersistenceIssue
import app.solstone.core.model.DirectEndpoint
import app.solstone.core.model.IdentityState
import app.solstone.core.model.PairedHome
import java.util.concurrent.Executor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    // Falsified by a dial made to pair clearing a sighting: it is pinned to the link's CA and says
    // nothing about the paired journal, wherever it happens to reach.
    @Test
    fun aDialMadeToPairNeitherClearsNorStartsASighting() {
        val store = sightings()
        val lines = mutableListOf<String>()
        val log = DialEventLog(store) { lines += it }
        store.seen("10.0.0.1:7657", null)

        log.recordPairDial("10.0.0.1", 7657, DialOutcome.CONNECTED)
        log.recordPairDial("10.0.0.2", 7657, DialOutcome.UNKNOWN_JOURNAL)

        assertEquals(listOf<String?>("10.0.0.1:7657"), store.current().map { it.address })
        assertEquals(2, lines.size)
    }

    // Falsified by a dial begun under the old pairing writing into the new pairing's list.
    @Test
    fun aDialOvertakenByARePairSaysNothing() {
        val store = sightings()
        val log = DialEventLog(store) {}
        val startedAt = log.sightingEpoch()

        store.clear()
        log.recordFailure("10.0.0.1", 7657, UnknownJournalException("10.0.0.1:7657", null), startedAt = startedAt)
        assertTrue(store.current().isEmpty())

        log.recordFailure("10.0.0.1", 7657, UnknownJournalException("10.0.0.1:7657", null))
        assertEquals(1, store.current().size)
    }

    @Test
    fun aDifferentPairingEndsEverySightingAndReportsTheChange() {
        val store = sightings()
        assertFalse(store.followPairing(committed("a", "sha256:one", "10.0.0.1")))
        store.seen("10.0.0.1:7657", null)
        store.seen(null, null)

        assertFalse(store.followPairing(committed("a", "sha256:one", "10.0.0.1")))
        assertEquals(2, store.current().size)

        assertTrue(store.followPairing(committed("a", "sha256:two", "10.0.0.1")))
        assertTrue(store.current().isEmpty())
    }

    @Test
    fun aPairingThatLeftReportsTheChangeAndAnUnreadableOneSaysNothing() {
        val store = sightings()
        store.followPairing(committed("a", "sha256:one", "10.0.0.1"))
        store.seen(null, null)

        assertFalse(store.followPairing(PairingGraphSnapshot.Uncertain(2, PersistenceIssue.PERSISTENCE_FAILED)))
        assertEquals(1, store.current().size)

        assertTrue(store.followPairing(PairingGraphSnapshot.Absent(3)))
        assertTrue(store.current().isEmpty())
    }

    // Falsified by keeping the row: nothing dials an address no longer saved, so it could never clear.
    @Test
    fun anAddressNoLongerSavedLosesItsRowAndTheRelayKeepsItsOwn() {
        val store = sightings()
        store.followPairing(committed("a", "sha256:one", "10.0.0.1", "10.0.0.2"))
        store.seen("10.0.0.1:7657", null)
        store.seen("10.0.0.2:7657", null)
        store.seen(null, null)
        var heard = 0
        store.addListener { heard++ }

        assertFalse(store.followPairing(committed("a", "sha256:one", "10.0.0.2")))

        assertEquals(listOf("10.0.0.2:7657", null), store.current().map { it.address })
        assertEquals(1, heard)
    }

    private fun committed(instance: String, cert: String, vararg hosts: String): PairingGraphSnapshot.Committed {
        val endpoints = hosts.map { DirectEndpoint(it, 7657) }
        return PairingGraphSnapshot.Committed(
            sequenceNumber = 1,
            revisions = GraphRevisions(1, 1, 1),
            home = PairedHome(instance, instance, null, "sha256:ca", cert, null, null, null, IdentityState.PAIRED),
            hasDirectEndpoint = endpoints.isNotEmpty(),
            directAssociated = endpoints.isNotEmpty(),
            relayLiveEligible = false,
            directEndpoint = endpoints.firstOrNull(),
            directEndpoints = endpoints,
        )
    }

    private fun markOf(claim: String) = JournalMark(
        icon1 = JournalMarkIcon(claim, "<svg/>", claim, "#000000", 0),
        icon2 = JournalMarkIcon(claim, "<svg/>", claim, "#ffffff", 45),
        words = listOf(claim, "word"),
    )
}
