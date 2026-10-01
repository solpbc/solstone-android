// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.identity

import app.solstone.core.model.IdentityState
import app.solstone.core.model.PairedHome
import kotlin.test.Test
import kotlin.test.assertEquals

class JournalConfirmationPredicatesTest {
    private class FakeConfirmationStore(
        private val result: StoreInspectResult<JournalConfirmation>,
    ) : JournalConfirmationStore {
        override fun inspect(): StoreInspectResult<JournalConfirmation> = result
        override fun confirm(fingerprint: String) {}
        override fun settle() {}
        override fun addListener(listener: () -> Unit): () -> Unit = { }
    }

    private val targetFp = "sha256:target-fingerprint"
    private val otherFp = "sha256:other-fingerprint"

    private val storeReadyMatch = FakeConfirmationStore(StoreInspectResult.Ready(JournalConfirmation(confirmed = targetFp)))
    private val storeReadyOther = FakeConfirmationStore(StoreInspectResult.Ready(JournalConfirmation(confirmed = otherFp)))
    private val storeReadyNull = FakeConfirmationStore(StoreInspectResult.Ready(JournalConfirmation(confirmed = null)))
    private val storeMissing = FakeConfirmationStore(StoreInspectResult.Missing)
    private val storeUnreadable = FakeConfirmationStore(StoreInspectResult.Unreadable(PersistenceIssue.PERSISTENCE_FAILED, "test"))

    private fun testHome(state: IdentityState, cert: String = targetFp) = PairedHome(
        instanceId = "home-1",
        homeLabel = "Home",
        relayOrigin = "https://link.solstone.app",
        caChainFingerprint = "sha256:ca-1",
        clientCertFingerprint = cert,
        observerHandle = "obs",
        deviceToken = "token-1",
        expiresAt = "2030-01-01T00:00:00Z",
        state = state,
    )

    private fun committed(state: IdentityState, cert: String = targetFp) = PairingGraphSnapshot.Committed(
        sequenceNumber = 1L,
        revisions = GraphRevisions(1, 1, 1),
        home = testHome(state, cert),
        hasDirectEndpoint = false,
        directAssociated = false,
        relayLiveEligible = false,
    )

    @Test
    fun confirmedForTable() {
        data class Row(
            val consults: Boolean,
            val store: JournalConfirmationStore,
            val fingerprint: String,
            val expected: Boolean,
        )

        val table = listOf(
            // consults = false is true across all store results and fingerprints
            Row(consults = false, store = storeReadyMatch, fingerprint = targetFp, expected = true),
            Row(consults = false, store = storeReadyOther, fingerprint = targetFp, expected = true),
            Row(consults = false, store = storeReadyNull, fingerprint = targetFp, expected = true),
            Row(consults = false, store = storeMissing, fingerprint = targetFp, expected = true),
            Row(consults = false, store = storeUnreadable, fingerprint = targetFp, expected = true),
            Row(consults = false, store = storeReadyMatch, fingerprint = "sha256:diff", expected = true),
            Row(consults = false, store = storeMissing, fingerprint = "sha256:diff", expected = true),

            // consults = true is true only for Ready(matching fingerprint)
            Row(consults = true, store = storeReadyMatch, fingerprint = targetFp, expected = true),
            Row(consults = true, store = storeReadyOther, fingerprint = targetFp, expected = false),
            Row(consults = true, store = storeReadyNull, fingerprint = targetFp, expected = false),
            Row(consults = true, store = storeMissing, fingerprint = targetFp, expected = false),
            Row(consults = true, store = storeUnreadable, fingerprint = targetFp, expected = false),
        )

        for ((index, row) in table.withIndex()) {
            val actual = confirmedFor(row.consults, row.store, row.fingerprint)
            assertEquals(row.expected, actual, "confirmedFor failed at row $index: $row")
        }
    }

    @Test
    fun awaitingMarkConfirmationTable() {
        data class Row(
            val consults: Boolean,
            val snapshot: PairingGraphSnapshot,
            val store: JournalConfirmationStore,
            val expected: Boolean,
        )

        val absent = PairingGraphSnapshot.Absent(1L)
        val uncertain = PairingGraphSnapshot.Uncertain(1L, PersistenceIssue.PERSISTENCE_FAILED)
        val revoked = committed(IdentityState.REVOKED)
        val paired = committed(IdentityState.PAIRED)

        val table = listOf(
            // Absent, Uncertain, Revoked are always false regardless of consults or store
            Row(consults = true, snapshot = absent, store = storeMissing, expected = false),
            Row(consults = false, snapshot = absent, store = storeMissing, expected = false),
            Row(consults = true, snapshot = absent, store = storeReadyMatch, expected = false),
            Row(consults = true, snapshot = uncertain, store = storeMissing, expected = false),
            Row(consults = false, snapshot = uncertain, store = storeMissing, expected = false),
            Row(consults = true, snapshot = uncertain, store = storeReadyMatch, expected = false),
            Row(consults = true, snapshot = revoked, store = storeMissing, expected = false),
            Row(consults = false, snapshot = revoked, store = storeMissing, expected = false),
            Row(consults = true, snapshot = revoked, store = storeReadyMatch, expected = false),
            Row(consults = false, snapshot = revoked, store = storeReadyMatch, expected = false),

            // Committed(PAIRED) with consults = false is always false
            Row(consults = false, snapshot = paired, store = storeMissing, expected = false),
            Row(consults = false, snapshot = paired, store = storeReadyMatch, expected = false),

            // Committed(PAIRED) with consults = true: true when unconfirmed, next to flipping twin
            Row(consults = true, snapshot = paired, store = storeMissing, expected = true),
            Row(consults = false, snapshot = paired, store = storeMissing, expected = false), // twin: consults false

            Row(consults = true, snapshot = paired, store = storeUnreadable, expected = true),
            Row(consults = true, snapshot = paired, store = storeReadyMatch, expected = false), // twin: Ready(match)

            Row(consults = true, snapshot = paired, store = storeReadyOther, expected = true),
            Row(consults = true, snapshot = paired, store = storeReadyMatch, expected = false), // twin: Ready(match)

            Row(consults = true, snapshot = paired, store = storeReadyNull, expected = true),
            Row(consults = false, snapshot = paired, store = storeReadyNull, expected = false), // twin: consults false
        )

        for ((index, row) in table.withIndex()) {
            val actual = awaitingMarkConfirmation(row.consults, row.snapshot, row.store)
            assertEquals(row.expected, actual, "awaitingMarkConfirmation failed at row $index: $row")
        }
    }
}
