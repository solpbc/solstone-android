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

        val loadingRequests = pairingPromptRequestsMark(
            coordinator = coordinator,
            presentation = JournalMarkPresentation.Loading,
            presentationGeneration = gen,
            currentPairing = gen,
        )
        val loadingShape = pairingPromptShape(
            coordinator = coordinator,
            presentation = JournalMarkPresentation.Loading,
            presentationGeneration = gen,
            currentPairing = gen,
        )
        assertTrue(loadingShape is PairingPromptShape.Connecting)
        assertTrue(loadingRequests)
        assertFalse(loadingShape.showsQuestion)
        assertEquals(null, loadingShape.card)
        assertFalse(loadingShape.withBody)
        assertEquals(null, loadingShape.primary)
        assertEquals(null, loadingShape.secondary)
        assertFalse(loadingShape.primaryEnabled)

        val genericRequests = pairingPromptRequestsMark(
            coordinator = coordinator,
            presentation = JournalMarkPresentation.Generic,
            presentationGeneration = gen,
            currentPairing = gen,
        )
        val genericShape = pairingPromptShape(
            coordinator = coordinator,
            presentation = JournalMarkPresentation.Generic,
            presentationGeneration = gen,
            currentPairing = gen,
        )
        assertTrue(genericShape is PairingPromptShape.Unverified)
        assertFalse(genericRequests)
        assertFalse(genericShape.showsQuestion)
        assertEquals(JournalMarkPresentation.Generic, genericShape.card)
        assertFalse(genericShape.withBody)
        assertEquals(PairingPromptAction.Confirm, genericShape.primary)
        assertEquals(PairingPromptAction.Drop, genericShape.secondary)
        assertTrue(genericShape.primaryEnabled)

        val unavailableRequests = pairingPromptRequestsMark(
            coordinator = coordinator,
            presentation = JournalMarkPresentation.Unavailable,
            presentationGeneration = gen,
            currentPairing = gen,
        )
        val unavailableShape = pairingPromptShape(
            coordinator = coordinator,
            presentation = JournalMarkPresentation.Unavailable,
            presentationGeneration = gen,
            currentPairing = gen,
        )
        assertTrue(unavailableShape is PairingPromptShape.Unverified)
        assertTrue(unavailableRequests)
        assertFalse(unavailableShape.showsQuestion)
        assertEquals(JournalMarkPresentation.Unavailable, unavailableShape.card)
        assertTrue(unavailableShape.withBody)
        assertEquals(PairingPromptAction.Confirm, unavailableShape.primary)
        assertEquals(PairingPromptAction.Drop, unavailableShape.secondary)
        assertTrue(unavailableShape.primaryEnabled)

        val identifiedPres = JournalMarkPresentation.Identified(fixtureMark)
        val matchRequests = pairingPromptRequestsMark(
            coordinator = coordinator,
            presentation = identifiedPres,
            presentationGeneration = gen,
            currentPairing = gen,
        )
        val matchShape = pairingPromptShape(
            coordinator = coordinator,
            presentation = identifiedPres,
            presentationGeneration = gen,
            currentPairing = gen,
        )
        assertTrue(matchShape is PairingPromptShape.Match)
        assertFalse(matchRequests)
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
        val requests = pairingPromptRequestsMark(
            coordinator = coordinator,
            presentation = JournalMarkPresentation.Loading,
            presentationGeneration = gen,
            currentPairing = gen,
        )
        val shape = pairingPromptShape(
            coordinator = coordinator,
            presentation = JournalMarkPresentation.Loading,
            presentationGeneration = gen,
            currentPairing = gen,
        )
        assertTrue(shape is PairingPromptShape.Connecting)
        assertTrue(requests)
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
            val requests = pairingPromptRequestsMark(
                coordinator = coordinator,
                presentation = pres,
                presentationGeneration = genA,
                currentPairing = genB,
            )
            val shape = pairingPromptShape(
                coordinator = coordinator,
                presentation = pres,
                presentationGeneration = genA,
                currentPairing = genB,
            )
            assertTrue(shape is PairingPromptShape.Connecting)
            assertTrue(requests)
        }
    }

    @Test
    fun settledIdentifiedAndGenericAtCurrentGenerationDoNotRequest() {
        val coordinator = createTestCoordinator()
        val gen = PairingGeneration("home-1", "sha256:cert-1")

        val presentations = listOf(
            JournalMarkPresentation.Identified(fixtureMark),
            JournalMarkPresentation.Generic,
        )

        for (pres in presentations) {
            val requests = pairingPromptRequestsMark(
                coordinator = coordinator,
                presentation = pres,
                presentationGeneration = gen,
                currentPairing = gen,
            )
            val shape = pairingPromptShape(
                coordinator = coordinator,
                presentation = pres,
                presentationGeneration = gen,
                currentPairing = gen,
            )
            assertFalse(shape is PairingPromptShape.Connecting)
            assertFalse(requests)
        }
    }

    @Test
    fun settledUnavailableAtCurrentGenerationRequests() {
        val coordinator = createTestCoordinator()
        val gen = PairingGeneration("home-1", "sha256:cert-1")

        val requests = pairingPromptRequestsMark(
            coordinator = coordinator,
            presentation = JournalMarkPresentation.Unavailable,
            presentationGeneration = gen,
            currentPairing = gen,
        )
        val shape = pairingPromptShape(
            coordinator = coordinator,
            presentation = JournalMarkPresentation.Unavailable,
            presentationGeneration = gen,
            currentPairing = gen,
        )
        assertFalse(shape is PairingPromptShape.Connecting)
        assertTrue(requests)
    }

    @Test
    fun nullCurrentPairingWithDifferentGenerationConnectsWithoutRequest() {
        val coordinator = createTestCoordinator()
        val genA = PairingGeneration("home-1", "sha256:cert-1")

        val idRequests = pairingPromptRequestsMark(
            coordinator = coordinator,
            presentation = JournalMarkPresentation.Identified(fixtureMark),
            presentationGeneration = genA,
            currentPairing = null,
        )
        val idShape = pairingPromptShape(
            coordinator = coordinator,
            presentation = JournalMarkPresentation.Identified(fixtureMark),
            presentationGeneration = genA,
            currentPairing = null,
        )
        assertTrue(idShape is PairingPromptShape.Connecting)
        assertFalse(idRequests)

        val genRequests = pairingPromptRequestsMark(
            coordinator = coordinator,
            presentation = JournalMarkPresentation.Generic,
            presentationGeneration = genA,
            currentPairing = null,
        )
        val genShape = pairingPromptShape(
            coordinator = coordinator,
            presentation = JournalMarkPresentation.Generic,
            presentationGeneration = genA,
            currentPairing = null,
        )
        assertTrue(genShape is PairingPromptShape.Connecting)
        assertFalse(genRequests)
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
            val requests = pairingPromptRequestsMark(
                coordinator = coordinator,
                presentation = pres,
                presentationGeneration = null,
                currentPairing = null,
            )
            val shape = pairingPromptShape(
                coordinator = coordinator,
                presentation = pres,
                presentationGeneration = null,
                currentPairing = null,
            )
            assertFalse(shape is PairingPromptShape.Connecting)
            assertFalse(requests)
        }
    }

    @Test
    fun absentCoordinatorIsUnverifiedWithoutRequest() {
        val currentPairing = PairingGeneration("home-1", "sha256:cert-1")
        val requests = pairingPromptRequestsMark(
            coordinator = null,
            presentation = JournalMarkPresentation.Loading,
            presentationGeneration = null,
            currentPairing = currentPairing,
        )
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
        assertFalse(requests)
    }

    @Test
    fun pairingPromptRequestsMarkDecisionTable() {
        val coordinator = createTestCoordinator()
        val genA = PairingGeneration("home-1", "sha256:cert-1")
        val genB = PairingGeneration("home-2", "sha256:cert-2")

        // 1. coordinator null -> false
        assertFalse(pairingPromptRequestsMark(null, JournalMarkPresentation.Loading, genA, genA))
        assertFalse(pairingPromptRequestsMark(null, JournalMarkPresentation.Unavailable, genA, genA))

        // 2. currentPairing null -> false
        assertFalse(pairingPromptRequestsMark(coordinator, JournalMarkPresentation.Loading, genA, null))
        assertFalse(pairingPromptRequestsMark(coordinator, JournalMarkPresentation.Unavailable, genA, null))
        assertFalse(pairingPromptRequestsMark(coordinator, JournalMarkPresentation.Identified(fixtureMark), genA, null))
        assertFalse(pairingPromptRequestsMark(coordinator, JournalMarkPresentation.Generic, genA, null))

        // 3. stale generation (presentationGeneration != currentPairing) with currentPairing != null -> true
        assertTrue(pairingPromptRequestsMark(coordinator, JournalMarkPresentation.Identified(fixtureMark), genA, genB))
        assertTrue(pairingPromptRequestsMark(coordinator, JournalMarkPresentation.Generic, genA, genB))
        assertTrue(pairingPromptRequestsMark(coordinator, JournalMarkPresentation.Unavailable, genA, genB))
        assertTrue(pairingPromptRequestsMark(coordinator, JournalMarkPresentation.Loading, genA, genB))

        // 4. Loading at current pairing -> true
        assertTrue(pairingPromptRequestsMark(coordinator, JournalMarkPresentation.Loading, genA, genA))

        // 5. Unavailable at current pairing -> true
        assertTrue(pairingPromptRequestsMark(coordinator, JournalMarkPresentation.Unavailable, genA, genA))

        // 6. Identified at current pairing -> false
        assertFalse(pairingPromptRequestsMark(coordinator, JournalMarkPresentation.Identified(fixtureMark), genA, genA))

        // 7. Generic at current pairing -> false
        assertFalse(pairingPromptRequestsMark(coordinator, JournalMarkPresentation.Generic, genA, genA))
    }

    @Test
    fun awaitingFlagRendersConnectingAndAwaitingFalsePreservesSettledShape() {
        val coordinator = createTestCoordinator()
        val genA = PairingGeneration("home-1", "sha256:cert-1")
        val genB = PairingGeneration("home-2", "sha256:cert-2")

        val presentations = listOf(
            JournalMarkPresentation.Identified(fixtureMark),
            JournalMarkPresentation.Unavailable,
            JournalMarkPresentation.Generic,
        )

        for (pres in presentations) {
            // Current generation with awaiting = true
            val shapeCurrentAwaiting = pairingPromptShape(
                coordinator = coordinator,
                presentation = pres,
                presentationGeneration = genA,
                currentPairing = genA,
                awaiting = true,
            )
            assertTrue(shapeCurrentAwaiting is PairingPromptShape.Connecting)

            // Stale generation with awaiting = true
            val shapeStaleAwaiting = pairingPromptShape(
                coordinator = coordinator,
                presentation = pres,
                presentationGeneration = genA,
                currentPairing = genB,
                awaiting = true,
            )
            assertTrue(shapeStaleAwaiting is PairingPromptShape.Connecting)

            // Current generation with awaiting = false keeps settled shape
            val shapeCurrentSettled = pairingPromptShape(
                coordinator = coordinator,
                presentation = pres,
                presentationGeneration = genA,
                currentPairing = genA,
                awaiting = false,
            )
            assertFalse(shapeCurrentSettled is PairingPromptShape.Connecting)
            assertTrue(shapeCurrentSettled.primaryEnabled)
        }
    }

    @Test
    fun initialWaitSeedingFromCoordinatorState() {
        val coordinator = createTestCoordinator()
        val gen = PairingGeneration("home-1", "sha256:cert-1")

        val unavailableReq = pairingPromptRequestsMark(
            coordinator = coordinator,
            presentation = JournalMarkPresentation.Unavailable,
            presentationGeneration = gen,
            currentPairing = gen,
        )
        val unavailableWait = if (unavailableReq) PairingPromptWait.OpenPending else PairingPromptWait.None
        assertEquals(PairingPromptWait.OpenPending, unavailableWait)

        val identifiedReq = pairingPromptRequestsMark(
            coordinator = coordinator,
            presentation = JournalMarkPresentation.Identified(fixtureMark),
            presentationGeneration = gen,
            currentPairing = gen,
        )
        val identifiedWait = if (identifiedReq) PairingPromptWait.OpenPending else PairingPromptWait.None
        assertEquals(PairingPromptWait.None, identifiedWait)

        val genericReq = pairingPromptRequestsMark(
            coordinator = coordinator,
            presentation = JournalMarkPresentation.Generic,
            presentationGeneration = gen,
            currentPairing = gen,
        )
        val genericWait = if (genericReq) PairingPromptWait.OpenPending else PairingPromptWait.None
        assertEquals(PairingPromptWait.None, genericWait)
    }

    @Test
    fun pairingPromptWaitAfterRequestStepFunction() {
        // decision false, ticket non-null -> None
        assertEquals(PairingPromptWait.None, pairingPromptWaitAfterRequest(requestsMark = false, ticket = 42L))
        // decision false, ticket null -> None
        assertEquals(PairingPromptWait.None, pairingPromptWaitAfterRequest(requestsMark = false, ticket = null))
        // decision true, ticket null -> None
        assertEquals(PairingPromptWait.None, pairingPromptWaitAfterRequest(requestsMark = true, ticket = null))
        // decision true, ticket present -> Ticket
        assertEquals(PairingPromptWait.Ticket(42L), pairingPromptWaitAfterRequest(requestsMark = true, ticket = 42L))
    }
}
