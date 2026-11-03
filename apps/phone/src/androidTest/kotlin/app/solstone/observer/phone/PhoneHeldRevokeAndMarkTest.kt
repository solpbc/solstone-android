// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.solstone.core.crypto.sha256Hex
import app.solstone.core.identity.JournalConfirmationPolicy
import app.solstone.core.identity.awaitingMarkConfirmation
import app.solstone.core.identity.ClientCredential
import app.solstone.core.identity.GraphMutationResult
import app.solstone.core.identity.PairingGraphSnapshot
import app.solstone.core.model.DirectEndpoint
import app.solstone.core.model.IdentityState
import app.solstone.core.model.PairedHome
import app.solstone.core.observer.INGEST_PATH
import app.solstone.core.observer.SEGMENTS_PATH
import app.solstone.core.pl.journalMarkFromCaPem
import app.solstone.platform.work.holdPairingWhoseIdIsNotItsCas
import app.solstone.platform.work.syncStores
import app.solstone.testing.JournalLoopbackStandIn
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhoneHeldRevokeAndMarkTest {
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private var standIn: JournalLoopbackStandIn? = null

    @Before
    fun setUp() {
        standIn = JournalLoopbackStandIn()
        resetObserverRuntime()
        resetPersistence(context)
        PhoneShellActivity.promptedConfirmationThisProcess = true
    }

    @After
    fun tearDown() {
        standIn?.close()
        standIn = null
        resetObserverRuntime()
        PhoneShellActivity.promptedConfirmationThisProcess = false
        runCatching { syncStores(context).publisher.forget() }
    }

    private fun installTestPairing(standIn: JournalLoopbackStandIn, instanceId: String = standIn.instanceId) {
        val stores = syncStores(context)
        val publisher = stores.publisher
        publisher.forget()

        val clientCert = JournalLoopbackStandIn.signedCertificate(
            subjectPublicKey = standIn.serverKeyPair.public,
            issuerKeyPair = standIn.caKeyPair,
            issuerSubject = "Journal CA",
            subjectName = "solstone-client",
            isCa = false,
        )
        val home = PairedHome(
            instanceId = instanceId,
            homeLabel = "Home",
            relayOrigin = null,
            caChainFingerprint = "sha256:ca-1",
            clientCertFingerprint = "sha256:" + sha256Hex(clientCert.encoded),
            observerHandle = "obs",
            deviceToken = null,
            expiresAt = null,
            state = IdentityState.PAIRED,
        )
        val cred = ClientCredential(
            privateKeyPem = app.solstone.core.crypto.pem("PRIVATE KEY", standIn.serverKeyPair.private.encoded),
            clientCertPem = app.solstone.core.crypto.pem("CERTIFICATE", clientCert.encoded),
            caChainPem = listOf(standIn.caPem),
        )
        val result = publisher.installOrReplace(
            home = home,
            credential = cred,
            directEndpoint = DirectEndpoint("127.0.0.1", standIn.port),
            isDirectAssociated = true,
        )
        assertTrue(result is GraphMutationResult.Applied)
    }

    @Test
    fun pairingAccessoryMismatchClickCausesDeleteUnderClients() {
        val server = standIn!!
        installTestPairing(server)
        val stores = syncStores(context)
        val factory = phoneSpec.pairingAccessoryFactory!!

        ActivityScenario.launch(PhoneShellActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContentView(factory(activity) {})
            }
            composeRule.waitUntil(MARK_DERIVED_MILLIS) {
                composeRule.onAllNodesWithTag("pairingNoMatch").fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithTag("pairingNoMatch").performClick()
            composeRule.waitForIdle()

            composeRule.waitUntil(5000) {
                server.recordedRequests.any { it.method == "DELETE" && it.path.startsWith("/app/network/api/clients/") }
            }
            assertTrue(server.recordedRequests.any { it.method == "DELETE" && it.path.startsWith("/app/network/api/clients/") })
        }
    }

    @Test
    fun shellForgetJournalControlCausesDeleteUnderClients() {
        val server = standIn!!
        installTestPairing(server)

        ActivityScenario.launch(PhoneShellActivity::class.java).use { scenario ->
            lateinit var activity: PhoneShellActivity
            scenario.onActivity { activity = it }
            composeRule.waitUntil(10_000) {
                composeRule.onAllNodes(androidx.compose.ui.test.hasTestTag("pairingHeld")).fetchSemanticsNodes().isNotEmpty()
            }
            pressSystemBackWhenFocused(activity)
            composeRule.waitUntil(10_000) {
                composeRule.onAllNodes(androidx.compose.ui.test.hasTestTag("pairingHeld")).fetchSemanticsNodes().isEmpty()
            }
            val stores = syncStores(context)
            assertTrue(awaitingMarkConfirmation(JournalConfirmationPolicy.consults, stores.publisher.currentSnapshot(), stores.journalConfirmationStore))
            composeRule.onNodeWithTag("phoneShelfOpener").performClick()
            composeRule.waitForIdle()

            composeRule.onNodeWithTag("shelfRow-yourJournal").performClick()
            composeRule.waitForIdle()

            composeRule.onNodeWithText("forget this journal").performClick()
            composeRule.waitForIdle()

            composeRule.onNodeWithText("forget").performClick()
            composeRule.waitForIdle()

            composeRule.waitUntil(5000) {
                server.recordedRequests.any { it.method == "DELETE" && it.path.startsWith("/app/network/api/clients/") }
            }
            assertTrue(server.recordedRequests.any { it.method == "DELETE" && it.path.startsWith("/app/network/api/clients/") })
        }
    }

    @Test
    fun shellUnpairThisDeviceControlCausesDeleteUnderClients() {
        val server = standIn!!
        installTestPairing(server)

        ActivityScenario.launch(PhoneShellActivity::class.java).use { scenario ->
            lateinit var activity: PhoneShellActivity
            scenario.onActivity { activity = it }
            composeRule.waitUntil(10_000) {
                composeRule.onAllNodes(androidx.compose.ui.test.hasTestTag("pairingHeld")).fetchSemanticsNodes().isNotEmpty()
            }
            pressSystemBackWhenFocused(activity)
            composeRule.waitUntil(10_000) {
                composeRule.onAllNodes(androidx.compose.ui.test.hasTestTag("pairingHeld")).fetchSemanticsNodes().isEmpty()
            }
            val stores = syncStores(context)
            assertTrue(awaitingMarkConfirmation(JournalConfirmationPolicy.consults, stores.publisher.currentSnapshot(), stores.journalConfirmationStore))
            composeRule.onNodeWithTag("phoneShelfOpener").performClick()
            composeRule.waitForIdle()

            composeRule.onNodeWithTag("shelfRow-thisDevice").performClick()
            composeRule.waitForIdle()

            composeRule.onNodeWithText("unpair").performClick()
            composeRule.waitForIdle()

            composeRule.onNode(hasText("unpair") and hasAnyAncestor(isDialog())).performClick()
            composeRule.waitForIdle()

            composeRule.waitUntil(5000) {
                server.recordedRequests.any { it.method == "DELETE" && it.path.startsWith("/app/network/api/clients/") }
            }
            assertTrue(server.recordedRequests.any { it.method == "DELETE" && it.path.startsWith("/app/network/api/clients/") })
        }
    }

    // A pairing stored before ids were checked. On start it goes back to held, the prompt asks about
    // the mark its stored CA gives, nothing is sent to it meanwhile, and `no` tears it down as usual.
    @Test
    fun aStoredPairingWhoseIdIsNotItsCasIsHeldAskedAndTornDownOnNo() {
        val server = standIn!!
        val otherJournal = JournalLoopbackStandIn()
        try {
            installTestPairing(server, instanceId = otherJournal.instanceId)
            val stores = syncStores(context)
            val committed = stores.publisher.currentSnapshot() as PairingGraphSnapshot.Committed
            stores.journalConfirmationStore.confirm(committed.home.clientCertFingerprint)

            holdPairingWhoseIdIsNotItsCas(stores.publisher, stores.journalConfirmationStore)

            assertTrue(phoneAwaitingMarkConfirmation(context))
            val expected = journalMarkFromCaPem(server.caPem)
            val factory = phoneSpec.pairingAccessoryFactory!!
            ActivityScenario.launch(PhoneShellActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.setContentView(factory(activity) {})
                }
                composeRule.waitUntil(MARK_DERIVED_MILLIS) {
                    composeRule.onAllNodesWithTag("pairingNoMatch").fetchSemanticsNodes().isNotEmpty()
                }
                composeRule.onNodeWithText(expected.words[0], useUnmergedTree = true).assertExists()
                assertTrue(server.recordedRequests.none { it.path.startsWith(INGEST_PATH) || it.path.startsWith(SEGMENTS_PATH) })

                composeRule.onNodeWithTag("pairingNoMatch").performClick()
                composeRule.waitUntil(5000) {
                    stores.publisher.currentSnapshot() is PairingGraphSnapshot.Absent
                }
            }
        } finally {
            otherJournal.close()
        }
    }

    // The mark on the prompt comes from the CA this phone pinned, not from anything the peer serves.
    @Test
    fun theMarkViewShowsTheMarkOfThePinnedCaWithoutAskingTheJournal() {
        val server = standIn!!
        installTestPairing(server)
        val stores = syncStores(context)
        stores.journalMarkStore.clear()
        val factory = phoneSpec.pairingAccessoryFactory!!
        val expected = journalMarkFromCaPem(server.caPem)

        ActivityScenario.launch(PhoneShellActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContentView(factory(activity) {})
            }
            composeRule.waitUntil(MARK_DERIVED_MILLIS) {
                composeRule.onAllNodesWithText(expected.words[0], useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            composeRule.onNodeWithText(expected.words[1], useUnmergedTree = true).assertExists()
            assertTrue(server.recordedRequests.none { it.path == "/app/network/api/identity" })
        }
    }

    private companion object {
        // Argon2 on an emulator, once per pairing.
        const val MARK_DERIVED_MILLIS = 60_000L
    }
}
