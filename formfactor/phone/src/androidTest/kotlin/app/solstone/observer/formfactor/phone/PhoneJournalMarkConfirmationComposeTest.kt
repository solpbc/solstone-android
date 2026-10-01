// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.solstone.core.identity.ClientCredential
import app.solstone.core.identity.GraphMutationResult
import app.solstone.core.identity.GraphRevisions
import app.solstone.core.identity.JournalMark
import app.solstone.core.identity.JournalMarkIcon
import app.solstone.core.identity.JournalMarkRecord
import app.solstone.core.identity.JournalMarkStore
import app.solstone.core.identity.PairingGeneration
import app.solstone.core.identity.PairingGraphSnapshot
import app.solstone.core.identity.PairingLease
import app.solstone.core.identity.PairingPublisher
import app.solstone.core.identity.PersistenceIssue
import app.solstone.core.identity.StoreInspectResult
import app.solstone.core.identity.SubscriptionHandle
import app.solstone.core.model.IdentityState
import app.solstone.core.model.PairedHome
import app.solstone.core.identity.JournalMarkPresentation
import app.solstone.core.pl.JournalIdentityRefreshCoordinator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class PhoneJournalMarkConfirmationComposeTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val sampleMark = JournalMark(
        icon1 = JournalMarkIcon(
            name = "piano",
            svg = "<path d=\"M18.5 3H5.5\"/>",
            colorName = "blue",
            colorHex = "#3b82f6",
            rot = 45,
        ),
        icon2 = JournalMarkIcon(
            name = "key",
            svg = "<circle cx=\"7.5\" cy=\"15.5\" r=\"5.5\"/>",
            colorName = "purple",
            colorHex = "#a855f7",
            rot = 0,
        ),
        words = listOf("liquefy", "smock"),
    )

    private val pairingP = PairingGeneration("inst-1", "sha256:cert-1")

    private fun sampleHome(pairing: PairingGeneration) = PairedHome(
        instanceId = pairing.instanceId,
        homeLabel = "Home",
        relayOrigin = null,
        caChainFingerprint = "sha256:ca-1",
        clientCertFingerprint = pairing.clientCertFingerprint,
        observerHandle = null,
        deviceToken = null,
        expiresAt = null,
        state = IdentityState.PAIRED,
    )

    private fun committedSnapshot(pairing: PairingGeneration) = PairingGraphSnapshot.Committed(
        sequenceNumber = 1L,
        revisions = GraphRevisions(1L, 1L, 1L),
        home = sampleHome(pairing),
        hasDirectEndpoint = false,
        directAssociated = false,
        relayLiveEligible = false,
    )

    private class StubPublisher(var snapshot: PairingGraphSnapshot) : PairingPublisher {
        override fun currentSnapshot(): PairingGraphSnapshot = snapshot
        override fun subscribe(observer: (PairingGraphSnapshot) -> Unit): SubscriptionHandle = error("unused")
        override fun <T> withMutationBoundary(block: () -> T): T = block()
        override fun acquireDirectLease(): PairingLease.Direct? = error("unused")
        override fun acquireRelayLease(): PairingLease.Relay? = error("unused")
        override fun validateLease(lease: PairingLease): Boolean = error("unused")
        override fun installOrReplace(home: PairedHome, credential: ClientCredential, directEndpoint: app.solstone.core.model.DirectEndpoint?, isDirectAssociated: Boolean): GraphMutationResult = error("unused")
        override fun updateRelayAccess(expectedPairing: PairingGeneration, relayOrigin: String, deviceToken: String, expiresAt: String?): GraphMutationResult = error("unused")
        override fun revokeRelayAccess(expectedPairing: PairingGeneration): GraphMutationResult = error("unused")
        override fun forget(): GraphMutationResult = error("unused")
        override fun associateDirectIfProven(expectedPairing: PairingGeneration, endpoint: app.solstone.core.model.DirectEndpoint, proof: () -> Boolean): Boolean = error("unused")
    }

    private class FakeConfirmationStore : app.solstone.core.identity.JournalConfirmationStore {
        var confirmedFingerprint: String? = null
        var inspectResult: StoreInspectResult<app.solstone.core.identity.JournalConfirmation> = StoreInspectResult.Missing

        override fun inspect(): StoreInspectResult<app.solstone.core.identity.JournalConfirmation> = inspectResult
        override fun confirm(fingerprint: String) {
            confirmedFingerprint = fingerprint
            inspectResult = StoreInspectResult.Ready(app.solstone.core.identity.JournalConfirmation(fingerprint))
        }
        override fun settle() {
            inspectResult = StoreInspectResult.Ready(app.solstone.core.identity.JournalConfirmation(null))
        }
        override fun addListener(listener: () -> Unit): () -> Unit = { }
    }

    private class FakeMarkStore(var result: StoreInspectResult<JournalMarkRecord> = StoreInspectResult.Missing) : JournalMarkStore {
        override fun inspect(): StoreInspectResult<JournalMarkRecord> = result
        override fun load(): JournalMarkRecord? = (result as? StoreInspectResult.Ready<JournalMarkRecord>)?.value
        override fun save(record: JournalMarkRecord) { result = StoreInspectResult.Ready(record) }
        override fun clear() { result = StoreInspectResult.Missing }
    }

    @Test
    fun storeMissingShowsMarkLoadingAccessibleNameDisablesYesAndCallsRequestMark() {
        val store = FakeMarkStore(StoreInspectResult.Missing)
        val publisher = StubPublisher(committedSnapshot(pairingP))
        val coordinator = JournalIdentityRefreshCoordinator(store = store, publisher = publisher)
        val requestCalls = AtomicInteger(0)

        composeRule.setContent {
            PhoneTheme {
                PairingSuccessMark(
                    coordinator = coordinator,
                    currentPairing = { pairingP },
                    requestMark = { requestCalls.incrementAndGet() },
                )
            }
        }

        composeRule.onNodeWithTag(PAIRING_CONNECTING_TAG).assertExists()
        composeRule.onNodeWithTag("journalMarkCard").assertDoesNotExist()
        composeRule.onNodeWithText("yes, this is my journal").assertDoesNotExist()
        assertEquals(1, requestCalls.get())
        coordinator.close()
    }

    @Test
    fun storeReadyWithNullMarkYieldsGenericAndEnablesYes() {
        val record = JournalMarkRecord(instanceId = pairingP.instanceId, mark = null, pairing = pairingP)
        val store = FakeMarkStore(StoreInspectResult.Ready(record))
        val publisher = StubPublisher(committedSnapshot(pairingP))
        val coordinator = JournalIdentityRefreshCoordinator(store = store, publisher = publisher)

        composeRule.setContent {
            PhoneTheme {
                PairingSuccessMark(
                    coordinator = coordinator,
                    currentPairing = { pairingP },
                )
            }
        }

        composeRule.onNodeWithTag("journalMarkCard")
            .assertContentDescriptionEquals("your journal, not set up yet")
        composeRule.onNodeWithText("does this match your journal?").assertDoesNotExist()
        composeRule.onNodeWithTag(PAIRING_CONTINUE_ANYWAY_TAG).assertIsEnabled()
        coordinator.close()
    }

    @Test
    fun storeReadyWithMarkYieldsIdentifiedShowsWordsAndEnablesYes() {
        val record = JournalMarkRecord(instanceId = pairingP.instanceId, mark = sampleMark, pairing = pairingP)
        val store = FakeMarkStore(StoreInspectResult.Ready(record))
        val publisher = StubPublisher(committedSnapshot(pairingP))
        val coordinator = JournalIdentityRefreshCoordinator(store = store, publisher = publisher)

        composeRule.setContent {
            PhoneTheme {
                PairingSuccessMark(
                    coordinator = coordinator,
                    currentPairing = { pairingP },
                )
            }
        }

        composeRule.onNodeWithTag("journalMarkCard")
            .assertContentDescriptionEquals("blue, purple, liquefy, smock")
        composeRule.onNodeWithText("liquefy", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText("smock", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText("yes, this is my journal").assertIsEnabled()
        coordinator.close()
    }

    @Test
    fun storeUnreadableYieldsUnavailableAndEnablesYes() {
        val store = FakeMarkStore(StoreInspectResult.Unreadable(PersistenceIssue.PERSISTENCE_FAILED, "corrupt"))
        val publisher = StubPublisher(committedSnapshot(pairingP))
        val coordinator = JournalIdentityRefreshCoordinator(store = store, publisher = publisher)

        composeRule.setContent {
            PhoneTheme {
                PairingSuccessMark(
                    coordinator = coordinator,
                    currentPairing = { pairingP },
                )
            }
        }

        composeRule.onNodeWithTag("journalMarkCard")
            .assertContentDescriptionEquals("your journal's mark, unavailable right now")
        composeRule.onNodeWithText("does this match your journal?").assertDoesNotExist()
        composeRule.onNodeWithTag(PAIRING_CONTINUE_ANYWAY_TAG).assertIsEnabled()
        coordinator.close()
    }

    @Test
    fun identifiedPresentationWithStaleCommittedPairingDisablesYesButton() {
        val pairingA = PairingGeneration("inst-a", "sha256:cert-a")
        val pairingB = PairingGeneration("inst-b", "sha256:cert-b")
        val record = JournalMarkRecord(instanceId = pairingA.instanceId, mark = sampleMark, pairing = pairingA)
        val store = FakeMarkStore(StoreInspectResult.Ready(record))
        val publisher = StubPublisher(committedSnapshot(pairingA))
        val coordinator = JournalIdentityRefreshCoordinator(store = store, publisher = publisher)

        assertEquals(JournalMarkPresentation.Identified(sampleMark), coordinator.currentPresentation())
        assertEquals(pairingA, coordinator.currentPresentationGeneration())

        // Set publisher's committed pairing to B without calling onPairingChanged
        publisher.snapshot = committedSnapshot(pairingB)

        composeRule.setContent {
            PhoneTheme {
                PairingSuccessMark(
                    coordinator = coordinator,
                    currentPairing = { pairingB },
                )
            }
        }

        assertEquals(JournalMarkPresentation.Identified(sampleMark), coordinator.currentPresentation())
        assertEquals(pairingA, coordinator.currentPresentationGeneration())
        assertTrue(pairingA != pairingB)
        composeRule.onNodeWithTag(PAIRING_CONNECTING_TAG).assertExists()
        composeRule.onNodeWithText("yes, this is my journal").assertDoesNotExist()
        composeRule.onNodeWithTag("journalMarkCard").assertDoesNotExist()
        coordinator.close()
    }

    @Test
    fun yesButtonClickUsesComposedGenerationAndRejectsIfPairingChanged() {
        val pairingA = PairingGeneration("inst-a", "sha256:cert-a")
        val pairingB = PairingGeneration("inst-b", "sha256:cert-b")
        val record = JournalMarkRecord(instanceId = pairingA.instanceId, mark = sampleMark, pairing = pairingA)
        val markStore = FakeMarkStore(StoreInspectResult.Ready(record))
        val confirmStore = FakeConfirmationStore()
        val publisher = StubPublisher(committedSnapshot(pairingA))
        val coordinator = JournalIdentityRefreshCoordinator(store = markStore, publisher = publisher)

        var confirmedCalled = false

        composeRule.setContent {
            PhoneTheme {
                PairingSuccessMark(
                    coordinator = coordinator,
                    currentPairing = { pairingA },
                    onYes = { presented ->
                        app.solstone.platform.work.confirmCurrentJournal(
                            publisher = publisher,
                            store = confirmStore,
                            fingerprint = presented.clientCertFingerprint,
                        )
                    },
                    onConfirmed = { confirmedCalled = true },
                )
            }
        }

        assertEquals(pairingA, coordinator.currentPresentationGeneration())
        composeRule.onNodeWithText("yes, this is my journal").assertIsEnabled()

        // Commit B on publisher without firing generation listener (no recomposition)
        publisher.snapshot = committedSnapshot(pairingB)

        composeRule.onNodeWithText("yes, this is my journal").performClick()
        composeRule.waitForIdle()

        assertNull(confirmStore.confirmedFingerprint)
        assertFalse(confirmedCalled)
        composeRule.onNodeWithText("does this match your journal?").assertExists()
        coordinator.close()
    }

    @Test
    fun yesButtonClickWhilePairingCurrentConfirmsCurrentPairing() {
        val pairingA = PairingGeneration("inst-a", "sha256:cert-a")
        val record = JournalMarkRecord(instanceId = pairingA.instanceId, mark = sampleMark, pairing = pairingA)
        val markStore = FakeMarkStore(StoreInspectResult.Ready(record))
        val confirmStore = FakeConfirmationStore()
        val publisher = StubPublisher(committedSnapshot(pairingA))
        val coordinator = JournalIdentityRefreshCoordinator(store = markStore, publisher = publisher)

        var confirmedCalled = false

        composeRule.setContent {
            PhoneTheme {
                PairingSuccessMark(
                    coordinator = coordinator,
                    currentPairing = { pairingA },
                    onYes = { presented ->
                        app.solstone.platform.work.confirmCurrentJournal(
                            publisher = publisher,
                            store = confirmStore,
                            fingerprint = presented.clientCertFingerprint,
                        )
                    },
                    onConfirmed = { confirmedCalled = true },
                )
            }
        }

        assertEquals(pairingA, coordinator.currentPresentationGeneration())
        composeRule.onNodeWithText("yes, this is my journal").assertIsEnabled()

        composeRule.onNodeWithText("yes, this is my journal").performClick()
        composeRule.waitForIdle()

        assertEquals("sha256:cert-a", confirmStore.confirmedFingerprint)
        assertTrue(confirmedCalled)
        composeRule.onNodeWithText("this phone is connected to your journal.").assertExists()
        coordinator.close()
    }

    @Test
    fun yesClickSuccessWithGenericShowsConnectedSentenceAndCallsOnConfirmed() {
        val store = FakeMarkStore(StoreInspectResult.Ready(JournalMarkRecord(pairingP.instanceId, null, pairingP)))
        val publisher = StubPublisher(committedSnapshot(pairingP))
        val coordinator = JournalIdentityRefreshCoordinator(store = store, publisher = publisher)
        var onYesCalled = false
        var confirmedCalled = false

        composeRule.setContent {
            PhoneTheme {
                PairingSuccessMark(
                    coordinator = coordinator,
                    currentPairing = { pairingP },
                    onYes = { _ ->
                        onYesCalled = true
                        true
                    },
                    onConfirmed = { confirmedCalled = true },
                )
            }
        }

        composeRule.onNodeWithTag(PAIRING_CONTINUE_ANYWAY_TAG).performClick()
        composeRule.waitForIdle()

        assertTrue(onYesCalled)
        assertTrue(confirmedCalled)
        composeRule.onNodeWithText("this phone is connected to your journal.").assertExists()
        coordinator.close()
    }

    @Test
    fun yesClickSuccessWithIdentifiedShowsConnectedSentenceAndCallsOnConfirmed() {
        val store = FakeMarkStore(StoreInspectResult.Ready(JournalMarkRecord(pairingP.instanceId, sampleMark, pairingP)))
        val publisher = StubPublisher(committedSnapshot(pairingP))
        val coordinator = JournalIdentityRefreshCoordinator(store = store, publisher = publisher)
        var onYesCalled = false
        var confirmedCalled = false

        composeRule.setContent {
            PhoneTheme {
                PairingSuccessMark(
                    coordinator = coordinator,
                    currentPairing = { pairingP },
                    onYes = { _ ->
                        onYesCalled = true
                        true
                    },
                    onConfirmed = { confirmedCalled = true },
                )
            }
        }

        composeRule.onNodeWithText("yes, this is my journal").performClick()
        composeRule.waitForIdle()

        assertTrue(onYesCalled)
        assertTrue(confirmedCalled)
        composeRule.onNodeWithText("this phone is connected to your journal.").assertExists()
        coordinator.close()
    }

    @Test
    fun yesClickSuccessWithUnavailableShowsConnectedSentenceAndCallsOnConfirmed() {
        val store = FakeMarkStore(StoreInspectResult.Unreadable(PersistenceIssue.PERSISTENCE_FAILED, "unreadable"))
        val publisher = StubPublisher(committedSnapshot(pairingP))
        val coordinator = JournalIdentityRefreshCoordinator(store = store, publisher = publisher)
        var onYesCalled = false
        var confirmedCalled = false
        var mismatchCount = 0

        composeRule.setContent {
            PhoneTheme {
                PairingSuccessMark(
                    coordinator = coordinator,
                    currentPairing = { pairingP },
                    onYes = { _ ->
                        onYesCalled = true
                        true
                    },
                    onConfirmed = { confirmedCalled = true },
                    onMismatch = {
                        mismatchCount += 1
                        PairingMismatchResult.Disconnected
                    },
                )
            }
        }

        composeRule.onNodeWithText("does this match your journal?").assertDoesNotExist()
        composeRule.onNodeWithTag("journalMarkCard").assertExists()

        composeRule.onNodeWithTag(PAIRING_CONTINUE_ANYWAY_TAG).performClick()
        composeRule.waitForIdle()

        assertTrue(onYesCalled)
        assertEquals(0, mismatchCount)
        assertTrue(confirmedCalled)
        composeRule.onNodeWithText("this phone is connected to your journal.").assertExists()
        coordinator.close()
    }

    @Test
    fun unavailableCancelCallsOnMismatchOnceAndNeverOnYes() {
        val store = FakeMarkStore(StoreInspectResult.Unreadable(PersistenceIssue.PERSISTENCE_FAILED, "unreadable"))
        val publisher = StubPublisher(committedSnapshot(pairingP))
        val coordinator = JournalIdentityRefreshCoordinator(store = store, publisher = publisher)
        var onYesCalled = false
        var confirmedCount = 0
        var mismatchCount = 0

        composeRule.setContent {
            PhoneTheme {
                PairingSuccessMark(
                    coordinator = coordinator,
                    currentPairing = { pairingP },
                    onYes = { _ ->
                        onYesCalled = true
                        true
                    },
                    onConfirmed = { confirmedCount += 1 },
                    onMismatch = {
                        mismatchCount += 1
                        PairingMismatchResult.Disconnected
                    },
                )
            }
        }

        composeRule.onNodeWithTag(PAIRING_CANCEL_PAIRING_TAG).performClick()
        composeRule.waitForIdle()

        assertFalse(onYesCalled)
        assertEquals(1, mismatchCount)
        assertEquals(1, confirmedCount)
        composeRule.onNodeWithText("this phone is no longer connected to that journal.").assertExists()
        coordinator.close()
    }

    @Test
    fun yesClickFailureStaysOnPromptWithoutConnectedSentenceOrDisconnectError() {
        val store = FakeMarkStore(StoreInspectResult.Ready(JournalMarkRecord(pairingP.instanceId, null, pairingP)))
        val publisher = StubPublisher(committedSnapshot(pairingP))
        val coordinator = JournalIdentityRefreshCoordinator(store = store, publisher = publisher)
        var onYesCalled = false
        var confirmedCalled = false

        composeRule.setContent {
            PhoneTheme {
                PairingSuccessMark(
                    coordinator = coordinator,
                    currentPairing = { pairingP },
                    onYes = { _ ->
                        onYesCalled = true
                        false
                    },
                    onConfirmed = { confirmedCalled = true },
                )
            }
        }

        composeRule.onNodeWithTag(PAIRING_CONTINUE_ANYWAY_TAG).performClick()
        composeRule.waitForIdle()

        assertTrue(onYesCalled)
        assertFalse(confirmedCalled)
        composeRule.onNodeWithTag(PAIRING_CONTINUE_ANYWAY_TAG).assertExists()
        composeRule.onNodeWithText("does this match your journal?").assertDoesNotExist()
        composeRule.onNodeWithText("this phone is connected to your journal.").assertDoesNotExist()
        composeRule.onNodeWithText("couldn't disconnect this phone. try again.").assertDoesNotExist()
        coordinator.close()
    }

    @Test
    fun thatDoesNotMatchClickDoesNotCallOnYesCallsOnConfirmedAndShowsMismatchSentence() {
        val store = FakeMarkStore(StoreInspectResult.Ready(JournalMarkRecord(pairingP.instanceId, null, pairingP)))
        val publisher = StubPublisher(committedSnapshot(pairingP))
        val coordinator = JournalIdentityRefreshCoordinator(store = store, publisher = publisher)
        var onYesCalled = false
        var confirmedCount = 0
        var mismatchCount = 0

        composeRule.setContent {
            PhoneTheme {
                PairingSuccessMark(
                    coordinator = coordinator,
                    currentPairing = { pairingP },
                    onYes = { _ ->
                        onYesCalled = true
                        true
                    },
                    onConfirmed = { confirmedCount += 1 },
                    onMismatch = {
                        mismatchCount += 1
                        PairingMismatchResult.Disconnected
                    },
                )
            }
        }

        composeRule.onNodeWithText("does this match your journal?").assertDoesNotExist()
        composeRule.onNodeWithTag(PAIRING_CANCEL_PAIRING_TAG).performClick()
        composeRule.waitForIdle()

        assertFalse(onYesCalled)
        assertEquals(1, mismatchCount)
        assertEquals(1, confirmedCount)
        composeRule.onNodeWithText("this phone is no longer connected to that journal.").assertExists()
        coordinator.close()
    }
}
