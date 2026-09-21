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
        val facts = phoneJournalFacts(
            committed(direct = true, relayOrigin = "https://link.solstone.app"),
            status = null,
            intakeRunning = true,
        )
        assertEquals("journal", facts.label)
        assertEquals("journal-ca", facts.fingerprint)
        // ⚠ Both routes live is not an unknown: it is the case the app knows most about.
        assertEquals("straight to your journal, or through the relay sol pbc runs", facts.location)
        assertEquals("—", facts.connection)
        // The same two words the ongoing notification uses; `running` beside the shade's `on`
        // gave one state word two vocabularies.
        assertEquals("on", facts.intake)
        // `intake` follows the live service, never the owner's standing wish.
        assertEquals(
            "off",
            phoneJournalFacts(committed(direct = true, relayOrigin = null), null, intakeRunning = false).intake,
        )
    }

    @Test
    fun howYourPhoneConnectsAttributionMatrix() {
        // Direct only
        assertEquals(
            "straight to your journal",
            phoneJournalFacts(committed(direct = true, relayOrigin = null), null, false).location,
        )
        // Default relay only
        assertEquals(
            "through the relay sol pbc runs",
            phoneJournalFacts(committed(direct = false, relayOrigin = "https://link.solstone.app"), null, false).location,
        )
        // Trailing slash default
        assertEquals(
            "through the relay sol pbc runs",
            phoneJournalFacts(committed(direct = false, relayOrigin = "https://link.solstone.app/"), null, false).location,
        )
        // Mixed-case default
        assertEquals(
            "through the relay sol pbc runs",
            phoneJournalFacts(committed(direct = false, relayOrigin = "HTTPS://Link.Solstone.App"), null, false).location,
        )
        // Explicit port 443 default
        assertEquals(
            "through the relay sol pbc runs",
            phoneJournalFacts(committed(direct = false, relayOrigin = "https://link.solstone.app:443"), null, false).location,
        )
        // Lookalike origin (prefix match trap)
        assertEquals(
            "through a relay",
            phoneJournalFacts(committed(direct = false, relayOrigin = "https://link.solstone.app.evil.com"), null, false).location,
        )
        // Subdomain that would fool host-containment
        assertEquals(
            "through a relay",
            phoneJournalFacts(committed(direct = false, relayOrigin = "https://subdomain.link.solstone.app"), null, false).location,
        )
        // Other host
        assertEquals(
            "through a relay",
            phoneJournalFacts(committed(direct = false, relayOrigin = "https://relay.example"), null, false).location,
        )
        // Custom port on default host
        assertEquals(
            "through a relay",
            phoneJournalFacts(committed(direct = false, relayOrigin = "https://link.solstone.app:8443"), null, false).location,
        )
        // Invalid origin string that parses to null
        assertEquals(
            "through a relay",
            phoneJournalFacts(committed(direct = false, relayOrigin = "https://link solstone app"), null, false).location,
        )
        // Literal empty stored origin is present-but-unparseable, distinct from null
        assertEquals(
            "through a relay",
            phoneJournalFacts(committed(direct = false, relayOrigin = ""), null, false).location,
        )
        // Both routes with non-default relay
        assertEquals(
            "straight to your journal, or through a relay",
            phoneJournalFacts(committed(direct = true, relayOrigin = "https://relay.example"), null, false).location,
        )
        // Neither eligible / uncommitted
        val uncommitted = phoneJournalFacts(PairingGraphSnapshot.Absent(1), null, false)
        assertEquals(
            "—",
            uncommitted.location,
        )
        assertEquals("—", uncommitted.label)
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

    private fun committed(
        direct: Boolean,
        relayOrigin: String?,
        relayLiveEligible: Boolean = true,
    ): PairingGraphSnapshot.Committed {
        val hasRelay = relayOrigin != null
        return PairingGraphSnapshot.Committed(
            sequenceNumber = 1,
            revisions = GraphRevisions(1, 1, 1),
            home = PairedHome(
                instanceId = "journal",
                homeLabel = "journal",
                relayOrigin = relayOrigin,
                caChainFingerprint = "journal-ca",
                clientCertFingerprint = "phone-client",
                observerHandle = null,
                deviceToken = if (hasRelay) "token" else null,
                expiresAt = null,
                state = IdentityState.PAIRED,
            ),
            hasDirectEndpoint = direct,
            directAssociated = direct,
            relayLiveEligible = hasRelay && relayLiveEligible,
        )
    }
}
