// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone

import app.solstone.core.identity.ClientCredential
import app.solstone.core.identity.GraphMutationResult
import app.solstone.core.identity.PairingGeneration
import app.solstone.core.identity.PairingGraphSnapshot
import app.solstone.core.identity.awaitingMarkConfirmation
import app.solstone.core.model.IdentityState
import app.solstone.core.model.PairedHome
import app.solstone.platform.identity.file.FileClientCredentialStore
import app.solstone.platform.identity.file.FileJournalConfirmationStore
import app.solstone.platform.identity.file.FilePairingGraph
import app.solstone.platform.identity.file.SecretProtector
import app.solstone.platform.work.JournalRevokeOutcome
import app.solstone.platform.work.confirmCurrentJournal
import app.solstone.platform.work.revokeThisDeviceOnJournal
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.rules.TemporaryFolder

class PhoneMismatchOperationTest {
    @get:Rule val temp = TemporaryFolder()
    private val protector = object : SecretProtector {
        override fun protect(plaintext: ByteArray) = plaintext
        override fun unprotect(wrapped: ByteArray) = wrapped
    }

    private fun graph() = FilePairingGraph(
        identityFile = File(temp.root, "identity.tsv"),
        credentialFile = File(temp.root, "credential.pem"),
        endpointFile = File(temp.root, "endpoint.txt"),
        commitMarkerFile = File(temp.root, "pairing.commit"),
        protector = protector,
        pushKeyFile = File(temp.root, "push-key.bin"),
        pushKeyProtector = protector,
    )

    private fun install(graph: FilePairingGraph, id: String): PairingGraphSnapshot.Committed {
        val certificate = java.util.Base64.getEncoder().encodeToString(id.toByteArray())
        val fingerprint = java.security.MessageDigest.getInstance("SHA-256").digest(id.toByteArray())
            .joinToString("") { "%02x".format(it) }
        assertIs<GraphMutationResult.Applied>(graph.installOrReplace(
            home = PairedHome(
                instanceId = id, homeLabel = id, relayOrigin = "https://link.solstone.app",
                caChainFingerprint = "sha256:ca", clientCertFingerprint = fingerprint,
                observerHandle = "phone", deviceToken = "test-$id", expiresAt = null,
                state = IdentityState.PAIRED,
            ),
            credential = ClientCredential(
                privateKeyPem = "-----BEGIN PRIVATE KEY-----\nkey\n-----END PRIVATE KEY-----\n",
                clientCertPem = "-----BEGIN CERTIFICATE-----\n$certificate\n-----END CERTIFICATE-----\n",
                caChainPem = listOf("-----BEGIN CERTIFICATE-----\nca\n-----END CERTIFICATE-----\n"),
            ),
            directEndpoint = null, isDirectAssociated = false,
        ))
        return assertIs<PairingGraphSnapshot.Committed>(graph.currentSnapshot())
    }

    @Test fun delayedCleanupCannotClearLaterPairingOrItsPushKey() {
        val graph = graph()
        val a = install(graph, "a")
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        var pushReset = false
        try {
            val cleanup = executor.submit<GraphMutationResult> {
                runBlocking {
                    clearMismatchedPairing(graph, a.pairing,
                        revoke = {
                            entered.countDown()
                            check(release.await(10, TimeUnit.SECONDS))
                            JournalRevokeOutcome.REMOVED
                        },
                        afterCleared = { pushReset = true }, canClear = { true }, log = {},
                    )
                }
            }
            assertTrue(entered.await(10, TimeUnit.SECONDS))
            val b = install(graph, "b")
            graph.obtainPushKey(b.pairing)
            val pushKey = graph.readPushKey(b.pairing)!!
            release.countDown()
            assertIs<GraphMutationResult.Conflict>(cleanup.get(10, TimeUnit.SECONDS))
            assertEquals(b.pairing, assertIs<PairingGraphSnapshot.Committed>(graph.currentSnapshot()).pairing)
            assertContentEquals(pushKey, graph.readPushKey(b.pairing))
            assertFalse(pushReset)
            assertEquals(b.pairing, assertIs<PairingGraphSnapshot.Committed>(graph().currentSnapshot()).pairing)
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test fun staleRevocationIsRefusedBeforeOpeningLaterPairing() {
        val graph = graph()
        val a = install(graph, "a")
        val b = install(graph, "b")
        var opened = false
        assertEquals(JournalRevokeOutcome.UNREACHED, revokeThisDeviceOnJournal(
            graph,
            openDirect = { _, _ -> opened = true; error("must not open") },
            openRelay = { opened = true; error("must not open") },
            expectedPairing = a.pairing,
        ))
        assertFalse(opened)
        assertEquals(b.pairing, assertIs<PairingGraphSnapshot.Committed>(graph.currentSnapshot()).pairing)
    }

    @Test fun cleanupCannotErasePairingConfirmedWhileItWasPending() = runBlocking {
        val graph = graph()
        val a = install(graph, "a")
        val result = clearMismatchedPairing(graph, a.pairing,
            revoke = { JournalRevokeOutcome.UNREACHED },
            afterCleared = { error("must not reset") }, canClear = { false }, log = {},
        )
        assertIs<GraphMutationResult.Conflict>(result)
        assertEquals(a.pairing, assertIs<PairingGraphSnapshot.Committed>(graph.currentSnapshot()).pairing)
    }

    private fun storedCredential() =
        FileClientCredentialStore(File(temp.root, "credential.pem"), protector).load()

    /** The way a pairing link replaces a pairing: what it replaces is kept for the owner's answer. */
    private fun replace(graph: FilePairingGraph, id: String): PairingGraphSnapshot.Committed {
        val fingerprint = java.security.MessageDigest.getInstance("SHA-256").digest(id.toByteArray())
            .joinToString("") { "%02x".format(it) }
        graph.holdPriorFor(PairingGeneration(id, fingerprint))
        return install(graph, id)
    }

    // Falsified by a no that forgets: an owner whose journal was only away ends at not paired, with
    // the credential that journal still accepts deleted.
    @Test fun answeringNoToAReplacementPutsBackThePriorCredentialAndTheJournalKeepsNothing() = runBlocking {
        val graph = graph()
        val a = install(graph, "a")
        val prior = storedCredential()
        val b = replace(graph, "b")
        assertNotEquals(prior, storedCredential())
        var revokedWith: Any? = null

        val result = clearMismatchedPairing(graph, b.pairing,
            revoke = { revokedWith = storedCredential(); JournalRevokeOutcome.REMOVED },
            afterCleared = { error("the prior pairing's push stays as it was") }, canClear = { true }, log = {},
        )

        assertIs<GraphMutationResult.Applied>(result)
        assertEquals(a.pairing, assertIs<PairingGraphSnapshot.Committed>(graph.currentSnapshot()).pairing)
        assertEquals(prior, storedCredential())
        assertEquals(a.pairing, assertIs<PairingGraphSnapshot.Committed>(graph().currentSnapshot()).pairing)
        assertNotEquals(prior, revokedWith)
    }

    // Falsified by a passing keystore failure falling through to forget: the pairing it couldn't read
    // right now would be deleted for good.
    @Test fun aNoThatCantReadThePriorPairingFailsAndForgetsNothing() = runBlocking {
        val graph = graph()
        install(graph, "a")
        val b = replace(graph, "b")
        File(temp.root, "credential.pem.prior").writeText("not a credential")

        val result = clearMismatchedPairing(graph, b.pairing,
            revoke = { JournalRevokeOutcome.UNREACHED },
            afterCleared = { error("must not forget") }, canClear = { true }, log = {},
        )

        assertIs<GraphMutationResult.PersistenceFailed>(result)
        assertEquals(b.pairing, assertIs<PairingGraphSnapshot.Committed>(graph.currentSnapshot()).pairing)
        assertTrue(graph.canRestorePrior(b.pairing))
    }

    // Falsified by holding the prior pairing only in memory.
    @Test fun answeringNoAfterAProcessDeathStillPutsItBack() = runBlocking {
        val first = graph()
        val a = install(first, "a")
        val prior = storedCredential()
        val b = replace(first, "b")

        val relaunched = graph()
        val result = clearMismatchedPairing(relaunched, b.pairing,
            revoke = { JournalRevokeOutcome.UNREACHED },
            afterCleared = { error("must not reset") }, canClear = { true }, log = {},
        )

        assertIs<GraphMutationResult.Applied>(result)
        assertEquals(a.pairing, assertIs<PairingGraphSnapshot.Committed>(relaunched.currentSnapshot()).pairing)
        assertEquals(prior, storedCredential())
    }

    // Falsified by a confirmation lost in the swap: the restored pairing would be held and asked again.
    @Test fun theRestoredPairingKeepsItsAnswer() = runBlocking {
        val graph = graph()
        val store = FileJournalConfirmationStore(File(temp.root, "confirmation.json"))
        val a = install(graph, "a")
        store.confirm(a.home.clientCertFingerprint)
        store.settle()
        val b = replace(graph, "b")
        assertTrue(awaitingMarkConfirmation(true, graph.currentSnapshot(), store))

        clearMismatchedPairing(graph, b.pairing,
            revoke = { JournalRevokeOutcome.UNREACHED },
            afterCleared = { error("must not reset") },
            canClear = { awaitingMarkConfirmation(true, graph.currentSnapshot(), store) },
            log = {},
        )

        assertEquals(a.pairing, assertIs<PairingGraphSnapshot.Committed>(graph.currentSnapshot()).pairing)
        assertFalse(awaitingMarkConfirmation(true, graph.currentSnapshot(), store))
    }

    // Falsified by holding on past the answer: a later no would have nothing to do with this pairing.
    @Test fun answeringYesEndsWhatWasKept() {
        val graph = graph()
        val store = FileJournalConfirmationStore(File(temp.root, "confirmation.json"))
        install(graph, "a")
        val b = replace(graph, "b")
        assertTrue(graph.canRestorePrior(b.pairing))

        assertTrue(confirmCurrentJournal(graph, store, b.home.clientCertFingerprint))

        assertFalse(graph.canRestorePrior(b.pairing))
    }

    @Test fun currentUnconfirmedPairingCanBeCleared() = runBlocking {
        val graph = graph()
        val a = install(graph, "a")
        var pushReset = false
        val result = clearMismatchedPairing(graph, a.pairing,
            revoke = { JournalRevokeOutcome.UNREACHED },
            afterCleared = { pushReset = true }, canClear = { true }, log = {},
        )
        assertIs<GraphMutationResult.Cleared>(result)
        assertIs<PairingGraphSnapshot.Absent>(graph.currentSnapshot())
        assertTrue(pushReset)
    }
}
