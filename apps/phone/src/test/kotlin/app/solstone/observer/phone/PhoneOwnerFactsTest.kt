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
        assertEquals("—", facts.location)
        assertEquals("—", facts.connection)
        assertEquals("running", facts.intake)
        assertEquals("direct", phoneJournalFacts(committed(true, false), null, false).location)
        assertEquals("relay", phoneJournalFacts(committed(false, true), null, false).location)
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
