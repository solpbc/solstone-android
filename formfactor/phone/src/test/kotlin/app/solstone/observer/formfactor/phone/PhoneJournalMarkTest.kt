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

    private fun createTestCoordinator() = JournalIdentityRefreshCoordinator(object : app.solstone.core.identity.JournalMarkStore {
        override fun load() = null
        override fun save(record: app.solstone.core.identity.JournalMarkRecord) {}
        override fun clear() {}
    })

    private val fixtureMark = app.solstone.core.identity.JournalMark(
        icon1 = app.solstone.core.identity.JournalMarkIcon("a", "<path/>", "blue", "#3b82f6", 0),
        icon2 = app.solstone.core.identity.JournalMarkIcon("b", "<path/>", "blue", "#3b82f6", 0),
        words = listOf("word1", "word2"),
    )

    @Test
    fun fourPresentationsAtCurrentGenerationAreNotAConstant() {
        val coordinator = createTestCoordinator()
        val gen = PairingGeneration("home-1", "sha256:cert-1")

        val loadingShape = pairingPromptShape(
            coordinator = coordinator,
            presentation = JournalMarkPresentation.Loading,
            presentationGeneration = gen,
            currentPairing = gen,
        )
        assertTrue(loadingShape is PairingPromptShape.Connecting)
        assertTrue(loadingShape.requestsMark)
        assertFalse(loadingShape.showsQuestion)
        assertEquals(null, loadingShape.card)
        assertFalse(loadingShape.withBody)
        assertEquals(null, loadingShape.primary)
        assertEquals(null, loadingShape.secondary)
        assertFalse(loadingShape.primaryEnabled)

        val genericShape = pairingPromptShape(
            coordinator = coordinator,
            presentation = JournalMarkPresentation.Generic,
            presentationGeneration = gen,
            currentPairing = gen,
        )
        assertTrue(genericShape is PairingPromptShape.Unverified)
        assertFalse(genericShape.requestsMark)
        assertFalse(genericShape.showsQuestion)
        assertEquals(JournalMarkPresentation.Generic, genericShape.card)
        assertFalse(genericShape.withBody)
        assertEquals(PairingPromptAction.Confirm, genericShape.primary)
        assertEquals(PairingPromptAction.Drop, genericShape.secondary)
        assertTrue(genericShape.primaryEnabled)

        val unavailableShape = pairingPromptShape(
            coordinator = coordinator,
            presentation = JournalMarkPresentation.Unavailable,
            presentationGeneration = gen,
            currentPairing = gen,
        )
        assertTrue(unavailableShape is PairingPromptShape.Unverified)
        assertFalse(unavailableShape.requestsMark)
        assertFalse(unavailableShape.showsQuestion)
        assertEquals(JournalMarkPresentation.Unavailable, unavailableShape.card)
        assertTrue(unavailableShape.withBody)
        assertEquals(PairingPromptAction.Confirm, unavailableShape.primary)
        assertEquals(PairingPromptAction.Drop, unavailableShape.secondary)
        assertTrue(unavailableShape.primaryEnabled)

        val identifiedPres = JournalMarkPresentation.Identified(fixtureMark)
        val matchShape = pairingPromptShape(
            coordinator = coordinator,
            presentation = identifiedPres,
            presentationGeneration = gen,
            currentPairing = gen,
        )
        assertTrue(matchShape is PairingPromptShape.Match)
        assertFalse(matchShape.requestsMark)
        assertTrue(matchShape.showsQuestion)
        assertEquals(identifiedPres, matchShape.card)
        assertFalse(matchShape.withBody)
        assertEquals(PairingPromptAction.Confirm, matchShape.primary)
        assertEquals(PairingPromptAction.Drop, matchShape.secondary)
        assertTrue(matchShape.primaryEnabled)
    }

    @Test
    fun currentGenerationLoadingRequestsMarkAndRendersConnecting() {
        val coordinator = createTestCoordinator()
        val gen = PairingGeneration("home-1", "sha256:cert-1")
        val shape = pairingPromptShape(
            coordinator = coordinator,
            presentation = JournalMarkPresentation.Loading,
            presentationGeneration = gen,
            currentPairing = gen,
        )
        assertTrue(shape is PairingPromptShape.Connecting)
        assertTrue(shape.requestsMark)
    }

    @Test
    fun staleGenerationOnEveryPresentationRequestsAndConnects() {
        val coordinator = createTestCoordinator()
        val genA = PairingGeneration("home-1", "sha256:cert-1")
        val genB = PairingGeneration("home-2", "sha256:cert-2")

        val presentations = listOf(
            JournalMarkPresentation.Identified(fixtureMark),
            JournalMarkPresentation.Unavailable,
            JournalMarkPresentation.Generic,
            JournalMarkPresentation.Loading,
        )

        for (pres in presentations) {
            val shape = pairingPromptShape(
                coordinator = coordinator,
                presentation = pres,
                presentationGeneration = genA,
                currentPairing = genB,
            )
            assertTrue(shape is PairingPromptShape.Connecting)
            assertTrue(shape.requestsMark)
        }
    }

    @Test
    fun settledPresentationsAtCurrentGenerationDoNotRequest() {
        val coordinator = createTestCoordinator()
        val gen = PairingGeneration("home-1", "sha256:cert-1")

        val presentations = listOf(
            JournalMarkPresentation.Identified(fixtureMark),
            JournalMarkPresentation.Unavailable,
            JournalMarkPresentation.Generic,
        )

        for (pres in presentations) {
            val shape = pairingPromptShape(
                coordinator = coordinator,
                presentation = pres,
                presentationGeneration = gen,
                currentPairing = gen,
            )
            assertFalse(shape is PairingPromptShape.Connecting)
            assertFalse(shape.requestsMark)
        }
    }

    @Test
    fun nullCurrentPairingWithDifferentGenerationConnectsWithoutRequest() {
        val coordinator = createTestCoordinator()
        val genA = PairingGeneration("home-1", "sha256:cert-1")

        val idShape = pairingPromptShape(
            coordinator = coordinator,
            presentation = JournalMarkPresentation.Identified(fixtureMark),
            presentationGeneration = genA,
            currentPairing = null,
        )
        assertTrue(idShape is PairingPromptShape.Connecting)
        assertFalse(idShape.requestsMark)

        val genShape = pairingPromptShape(
            coordinator = coordinator,
            presentation = JournalMarkPresentation.Generic,
            presentationGeneration = genA,
            currentPairing = null,
        )
        assertTrue(genShape is PairingPromptShape.Connecting)
        assertFalse(genShape.requestsMark)
    }

    @Test
    fun bothNullGenerationsDoNotRequestSettledPresentations() {
        val coordinator = createTestCoordinator()

        val presentations = listOf(
            JournalMarkPresentation.Generic,
            JournalMarkPresentation.Unavailable,
            JournalMarkPresentation.Identified(fixtureMark),
        )

        for (pres in presentations) {
            val shape = pairingPromptShape(
                coordinator = coordinator,
                presentation = pres,
                presentationGeneration = null,
                currentPairing = null,
            )
            assertFalse(shape is PairingPromptShape.Connecting)
            assertFalse(shape.requestsMark)
        }
    }

    @Test
    fun absentCoordinatorIsUnverifiedWithoutRequest() {
        val currentPairing = PairingGeneration("home-1", "sha256:cert-1")
        val shape = pairingPromptShape(
            coordinator = null,
            presentation = JournalMarkPresentation.Loading,
            presentationGeneration = null,
            currentPairing = currentPairing,
        )
        assertTrue(shape is PairingPromptShape.Unverified)
        assertFalse(shape.withBody)
        assertEquals(JournalMarkPresentation.Generic, shape.card)
        assertFalse(shape.showsQuestion)
        assertFalse(shape.requestsMark)
    }
}
