// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone

import android.content.Context
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.solstone.core.identity.ClientCredential
import app.solstone.core.identity.GraphMutationResult
import app.solstone.core.model.DirectEndpoint
import app.solstone.core.model.IdentityState
import app.solstone.core.model.PairedHome
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

    private fun installTestPairing(standIn: JournalLoopbackStandIn) {
        val stores = syncStores(context)
        val publisher = stores.publisher
        publisher.forget()

        val home = PairedHome(
            instanceId = standIn.instanceId,
            homeLabel = "Home",
            relayOrigin = null,
            caChainFingerprint = "sha256:ca-1",
            clientCertFingerprint = "sha256:client-test",
            observerHandle = "obs",
            deviceToken = null,
            expiresAt = null,
            state = IdentityState.PAIRED,
        )
        val clientCert = JournalLoopbackStandIn.signedCertificate(
            subjectPublicKey = standIn.serverKeyPair.public,
            issuerKeyPair = standIn.caKeyPair,
            issuerSubject = "Journal CA",
            subjectName = "solstone-client",
            isCa = false,
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
        val factory = phoneSpec.pairingAccessoryFactory!!

        ActivityScenario.launch(PhoneShellActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContentView(factory(activity) {})
            }
            composeRule.onNodeWithText("that doesn't match").performClick()
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

        ActivityScenario.launch(PhoneShellActivity::class.java).use {
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

        ActivityScenario.launch(PhoneShellActivity::class.java).use {
            composeRule.onNodeWithTag("phoneShelfOpener").performClick()
            composeRule.waitForIdle()

            composeRule.onNodeWithTag("shelfRow-thisDevice").performClick()
            composeRule.waitForIdle()

            composeRule.onNodeWithText("unpair").performClick()
            composeRule.waitForIdle()

            composeRule.onNodeWithText("unpair").performClick()
            composeRule.waitForIdle()

            composeRule.waitUntil(5000) {
                server.recordedRequests.any { it.method == "DELETE" && it.path.startsWith("/app/network/api/clients/") }
            }
            assertTrue(server.recordedRequests.any { it.method == "DELETE" && it.path.startsWith("/app/network/api/clients/") })
        }
    }

    @Test
    fun markViewCallingRequestMarkCausesGetIdentity() {
        val server = standIn!!
        installTestPairing(server)
        val stores = syncStores(context)
        stores.journalMarkStore.clear()
        val factory = phoneSpec.pairingAccessoryFactory!!

        ActivityScenario.launch(PhoneShellActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContentView(factory(activity) {})
            }
            composeRule.waitForIdle()
            composeRule.waitUntil(5000) {
                server.recordedRequests.any { it.method == "GET" && it.path == "/app/network/api/identity" }
            }
            assertTrue(server.recordedRequests.any { it.method == "GET" && it.path == "/app/network/api/identity" })
        }
    }
}

