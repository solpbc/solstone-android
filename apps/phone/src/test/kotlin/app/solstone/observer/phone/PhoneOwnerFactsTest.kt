// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone

import app.solstone.core.identity.GraphRevisions
import app.solstone.core.identity.PairingGraphSnapshot
import app.solstone.core.identity.PersistenceIssue
import app.solstone.core.model.IdentityState
import app.solstone.core.model.PairedHome
import app.solstone.observer.harness.WishStoreState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PhoneOwnerFactsTest {
    @Test
    fun fingerprintUsesJournalTrustAnchorAndAmbiguousPathStaysUnknown() {
        val facts = phoneJournalFacts(committed(direct = true, relay = true), status = null, intakeRunning = true)
        assertEquals("journal-ca", facts.fingerprint)
        // ⚠ Both routes live is not an unknown: it is the case the app knows most about.
        assertEquals("straight to your journal, or through the relay sol pbc runs", facts.location)
        assertEquals("—", facts.connection)
        // The same two words the ongoing notification uses; `running` beside the shade's `on`
        // gave one state word two vocabularies.
        assertEquals("on", facts.intake)
        // `how it connects` is read by an owner, so it names the route rather than the transport.
        assertEquals(
            "straight to your journal",
            phoneJournalFacts(committed(true, false), null, false).location,
        )
        assertEquals(
            "through the relay sol pbc runs",
            phoneJournalFacts(committed(false, true), null, false).location,
        )
        // `intake` follows the live service, never the owner's standing wish.
        assertEquals("off", phoneJournalFacts(committed(true, false), null, intakeRunning = false).intake)
    }

    @Test
    fun welcomeRequiresBothStoresToBeProvenAbsent() {
        assertTrue(shouldShowWelcome(WishStoreState.Absent, PairingGraphSnapshot.Absent(1)))
        assertFalse(
            shouldShowWelcome(
                WishStoreState.Absent,
                PairingGraphSnapshot.Uncertain(2, PersistenceIssue.PERSISTENCE_FAILED),
            ),
        )
        assertFalse(
            shouldShowWelcome(
                WishStoreState.Unreadable,
                PairingGraphSnapshot.Absent(3),
            ),
        )
    }

    private fun committed(direct: Boolean, relay: Boolean): PairingGraphSnapshot.Committed =
        PairingGraphSnapshot.Committed(
            sequenceNumber = 1,
            revisions = GraphRevisions(1, 1, 1),
            home = PairedHome(
                instanceId = "journal",
                homeLabel = "journal",
                relayOrigin = if (relay) "https://relay.example" else null,
                caChainFingerprint = "journal-ca",
                clientCertFingerprint = "phone-client",
                observerHandle = null,
                deviceToken = if (relay) "token" else null,
                expiresAt = null,
                state = IdentityState.PAIRED,
            ),
            hasDirectEndpoint = direct,
            directAssociated = direct,
            relayLiveEligible = relay,
        )
}
