// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import app.solstone.core.identity.JournalMarkPresentation
import app.solstone.core.identity.PairingGeneration
import app.solstone.core.pl.JournalIdentityRefreshCoordinator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PhoneJournalMarkTest {

    @Test
    fun isPairingConfirmationEnabledWithNullCoordinator() {
        assertTrue(
            isPairingConfirmationEnabled(
                coordinator = null,
                presentationGeneration = null,
                currentPairing = null,
                presentation = JournalMarkPresentation.Loading,
            )
        )
    }

    @Test
    fun isPairingConfirmationEnabledWithCoordinator() {
        val coordinator = JournalIdentityRefreshCoordinator(object : app.solstone.core.identity.JournalMarkStore {
            override fun load() = null
            override fun save(record: app.solstone.core.identity.JournalMarkRecord) {}
            override fun clear() {}
        })
        val pairing = PairingGeneration("home-1", "sha256:cert-1")
        val otherPairing = PairingGeneration("home-2", "sha256:cert-2")

        // Loading is disabled even on current pairing
        assertFalse(
            isPairingConfirmationEnabled(
                coordinator = coordinator,
                presentationGeneration = pairing,
                currentPairing = pairing,
                presentation = JournalMarkPresentation.Loading,
            )
        )

        // Identified, Generic, Unavailable are enabled on current pairing
        val mark = app.solstone.core.identity.JournalMark(
            icon1 = app.solstone.core.identity.JournalMarkIcon("a", "<path/>", "blue", "#3b82f6", 0),
            icon2 = app.solstone.core.identity.JournalMarkIcon("b", "<path/>", "blue", "#3b82f6", 0),
            words = listOf("word1", "word2"),
        )
        assertTrue(
            isPairingConfirmationEnabled(
                coordinator = coordinator,
                presentationGeneration = pairing,
                currentPairing = pairing,
                presentation = JournalMarkPresentation.Identified(mark),
            )
        )
        assertTrue(
            isPairingConfirmationEnabled(
                coordinator = coordinator,
                presentationGeneration = pairing,
                currentPairing = pairing,
                presentation = JournalMarkPresentation.Generic,
            )
        )
        assertTrue(
            isPairingConfirmationEnabled(
                coordinator = coordinator,
                presentationGeneration = pairing,
                currentPairing = pairing,
                presentation = JournalMarkPresentation.Unavailable,
            )
        )

        // Different generation is disabled
        assertFalse(
            isPairingConfirmationEnabled(
                coordinator = coordinator,
                presentationGeneration = otherPairing,
                currentPairing = pairing,
                presentation = JournalMarkPresentation.Identified(mark),
            )
        )
        assertFalse(
            isPairingConfirmationEnabled(
                coordinator = coordinator,
                presentationGeneration = otherPairing,
                currentPairing = pairing,
                presentation = JournalMarkPresentation.Generic,
            )
        )
        assertFalse(
            isPairingConfirmationEnabled(
                coordinator = coordinator,
                presentationGeneration = otherPairing,
                currentPairing = pairing,
                presentation = JournalMarkPresentation.Unavailable,
            )
        )

        // Null generation or null current pairing is disabled
        assertFalse(
            isPairingConfirmationEnabled(
                coordinator = coordinator,
                presentationGeneration = null,
                currentPairing = pairing,
                presentation = JournalMarkPresentation.Identified(mark),
            )
        )
        assertFalse(
            isPairingConfirmationEnabled(
                coordinator = coordinator,
                presentationGeneration = pairing,
                currentPairing = null,
                presentation = JournalMarkPresentation.Identified(mark),
            )
        )
    }
}
