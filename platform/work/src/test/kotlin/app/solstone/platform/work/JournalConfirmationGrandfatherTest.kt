// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.work

import app.solstone.core.identity.ClientCredential
import app.solstone.core.identity.JournalConfirmation
import app.solstone.core.identity.JournalConfirmationPolicy
import app.solstone.core.identity.JournalConfirmationStore
import app.solstone.core.identity.PairingPublisher
import app.solstone.core.identity.StoreInspectResult
import app.solstone.core.model.DirectEndpoint
import app.solstone.core.model.IdentityState
import app.solstone.core.model.PairedHome
import app.solstone.platform.identity.file.FileJournalConfirmationStore
import app.solstone.platform.identity.file.FilePairingGraph
import app.solstone.platform.identity.file.SecretProtector
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class JournalConfirmationGrandfatherTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val fakeProtector = object : SecretProtector {
        override fun protect(plaintext: ByteArray) = plaintext
        override fun unprotect(wrapped: ByteArray) = wrapped
    }

    @Before
    fun setUp() {
        JournalConfirmationPolicy.consults = true
        JournalConfirmationGrandfather.resetForTest()
    }

    @After
    fun tearDown() {
        JournalConfirmationPolicy.consults = true
        JournalConfirmationGrandfather.resetForTest()
    }

    private fun testHome(cert: String = "sha256:cert-1") = PairedHome(
        instanceId = "home-1",
        homeLabel = "Home",
        relayOrigin = "https://link.solstone.app",
        caChainFingerprint = "sha256:ca-1",
        clientCertFingerprint = cert,
        observerHandle = "obs",
        deviceToken = "token-1",
        expiresAt = "2030-01-01T00:00:00Z",
        state = IdentityState.PAIRED,
    )

    private fun testCred() = ClientCredential(
        privateKeyPem = "-----BEGIN PRIVATE KEY-----\nkey-1\n-----END PRIVATE KEY-----\n",
        clientCertPem = "-----BEGIN CERTIFICATE-----\ncert-1\n-----END CERTIFICATE-----\n",
        caChainPem = listOf("-----BEGIN CERTIFICATE-----\nca-1\n-----END CERTIFICATE-----\n"),
    )

    private fun createGraph(): FilePairingGraph {
        return FilePairingGraph(
            identityFile = File(temp.root, "identity.tsv"),
            credentialFile = File(temp.root, "credential.pem"),
            endpointFile = File(temp.root, "endpoint.txt"),
            commitMarkerFile = File(temp.root, "pairing.commit"),
            protector = fakeProtector,
        )
    }

    @Test
    fun grandfatherWritesConfirmationForLegacyCommittedPairing() {
        val graph = createGraph()
        graph.installOrReplace(testHome(cert = "sha256:cert-1"), testCred(), DirectEndpoint("10.0.0.1", 7657), true)

        val confirmFile = File(temp.root, "journal_confirmation.json")
        val confirmStore = FileJournalConfirmationStore(confirmFile)
        assertIs<StoreInspectResult.Missing>(confirmStore.inspect())

        JournalConfirmationGrandfather.grandfather(graph, confirmStore)

        val inspected = assertIs<StoreInspectResult.Ready<JournalConfirmation>>(confirmStore.inspect())
        assertEquals("sha256:cert-1", inspected.value.confirmed)
    }

    @Test
    fun grandfatherSettlesNullConfirmedWhenAbsent() {
        val graph = createGraph()
        val confirmFile = File(temp.root, "journal_confirmation.json")
        val confirmStore = FileJournalConfirmationStore(confirmFile)

        JournalConfirmationGrandfather.grandfather(graph, confirmStore)

        val inspected = assertIs<StoreInspectResult.Ready<JournalConfirmation>>(confirmStore.inspect())
        assertEquals(null, inspected.value.confirmed)
    }

    @Test
    fun grandfatherDoesNothingWhenOptedOut() {
        JournalConfirmationPolicy.optOut()
        val graph = createGraph()
        graph.installOrReplace(testHome(cert = "sha256:cert-1"), testCred(), DirectEndpoint("10.0.0.1", 7657), true)

        val confirmFile = File(temp.root, "journal_confirmation.json")
        val confirmStore = FileJournalConfirmationStore(confirmFile)

        JournalConfirmationGrandfather.grandfather(graph, confirmStore)

        assertIs<StoreInspectResult.Missing>(confirmStore.inspect())
    }

    @Test
    fun grandfatherRunsOncePerProcess() {
        val graph = createGraph()
        val confirmFile = File(temp.root, "journal_confirmation.json")
        val confirmStore = FileJournalConfirmationStore(confirmFile)

        // First run when absent -> settles null confirmed
        JournalConfirmationGrandfather.grandfather(graph, confirmStore)
        val inspected1 = assertIs<StoreInspectResult.Ready<JournalConfirmation>>(confirmStore.inspect())
        assertEquals(null, inspected1.value.confirmed)

        // Later pairing is committed, but grandfather already ran in this process -> stays null confirmed
        graph.installOrReplace(testHome(cert = "sha256:cert-1"), testCred(), DirectEndpoint("10.0.0.1", 7657), true)
        JournalConfirmationGrandfather.grandfather(graph, confirmStore)

        val inspected2 = assertIs<StoreInspectResult.Ready<JournalConfirmation>>(confirmStore.inspect())
        assertEquals(null, inspected2.value.confirmed)
    }

    @Test
    fun confirmCurrentJournalValidatesFingerprintAndCommits() {
        val graph = createGraph()
        graph.installOrReplace(testHome(cert = "sha256:match"), testCred(), DirectEndpoint("10.0.0.1", 7657), true)

        val confirmFile = File(temp.root, "journal_confirmation.json")
        val confirmStore = FileJournalConfirmationStore(confirmFile)

        // Mismatched fingerprint
        val mismatchResult = confirmCurrentJournal(graph, confirmStore, "sha256:other")
        assertFalse(mismatchResult)
        assertIs<StoreInspectResult.Missing>(confirmStore.inspect())

        // Matching fingerprint
        val matchResult = confirmCurrentJournal(graph, confirmStore, "sha256:match")
        assertTrue(matchResult)
        val inspected = assertIs<StoreInspectResult.Ready<JournalConfirmation>>(confirmStore.inspect())
        assertEquals("sha256:match", inspected.value.confirmed)
    }

    @Test
    fun grandfatherPreservesVerbatimFingerprint() {
        val graph = createGraph()
        val customCert = "SHA256:ABC-123-UPPERCASE"
        graph.installOrReplace(testHome(cert = customCert), testCred(), DirectEndpoint("10.0.0.1", 7657), true)

        val confirmFile = File(temp.root, "journal_confirmation.json")
        val confirmStore = FileJournalConfirmationStore(confirmFile)

        JournalConfirmationGrandfather.grandfather(graph, confirmStore)

        val inspected = assertIs<StoreInspectResult.Ready<JournalConfirmation>>(confirmStore.inspect())
        assertEquals(customCert, inspected.value.confirmed)
    }

    @Test
    fun grandfatherDoesNothingOnUnreadableConfirmationFile() {
        val graph = createGraph()
        graph.installOrReplace(testHome(cert = "sha256:cert-1"), testCred(), DirectEndpoint("10.0.0.1", 7657), true)

        val confirmFile = File(temp.root, "journal_confirmation.json")
        val garbage = "not valid json {[[".toByteArray()
        confirmFile.writeBytes(garbage)
        val confirmStore = FileJournalConfirmationStore(confirmFile)

        assertIs<StoreInspectResult.Unreadable>(confirmStore.inspect())

        JournalConfirmationGrandfather.grandfather(graph, confirmStore)

        assertIs<StoreInspectResult.Unreadable>(confirmStore.inspect())
        assertEquals(garbage.toList(), confirmFile.readBytes().toList())
    }

    @Test
    fun grandfatherDoesNothingWhenSnapshotIsUncertain() {
        val graph = createGraph()
        graph.installOrReplace(testHome(cert = "sha256:cert-1"), testCred(), DirectEndpoint("10.0.0.1", 7657), true)
        // Corrupt commit marker to make snapshot Uncertain
        File(temp.root, "pairing.commit").writeText("invalid-commit-data")

        val uncertainGraph = createGraph()
        val confirmFile = File(temp.root, "journal_confirmation.json")
        val confirmStore = FileJournalConfirmationStore(confirmFile)

        JournalConfirmationGrandfather.grandfather(uncertainGraph, confirmStore)

        assertIs<StoreInspectResult.Missing>(confirmStore.inspect())
    }

    @Test
    fun grandfatherTestHookWritesSnapshotPairingEvenIfReplacedConcurrently() {
        val graph = createGraph()
        graph.installOrReplace(testHome(cert = "sha256:cert-a"), testCred(), DirectEndpoint("10.0.0.1", 7657), true)

        val confirmFile = File(temp.root, "journal_confirmation.json")
        val confirmStore = FileJournalConfirmationStore(confirmFile)

        JournalConfirmationGrandfather.grandfather(
            graph,
            confirmStore,
            testHook = {
                graph.installOrReplace(testHome(cert = "sha256:cert-b"), testCred(), DirectEndpoint("10.0.0.2", 7657), true)
            },
        )

        // The snapshot read before testHook was cert-a
        val inspected = assertIs<StoreInspectResult.Ready<JournalConfirmation>>(confirmStore.inspect())
        assertEquals("sha256:cert-a", inspected.value.confirmed)
    }

    @Test
    fun confirmCurrentJournalReturnsFalseOnWriteFailure() {
        val graph = createGraph()
        graph.installOrReplace(testHome(cert = "sha256:match"), testCred(), DirectEndpoint("10.0.0.1", 7657), true)

        val failingStore = object : JournalConfirmationStore {
            override fun inspect(): StoreInspectResult<JournalConfirmation> = StoreInspectResult.Missing
            override fun confirm(fingerprint: String) { throw IOException("disk error") }
            override fun settle() { throw IOException("disk error") }
            override fun addListener(listener: () -> Unit): () -> Unit = {}
        }

        val result = confirmCurrentJournal(graph, failingStore, "sha256:match")
        assertFalse(result)
    }

    @Test
    fun grandfatherConfirmThrowsCatchesLogsAndLeavesMissingWithoutThrowing() {
        val graph = createGraph()
        graph.installOrReplace(testHome(cert = "sha256:cert-fail"), testCred(), DirectEndpoint("10.0.0.1", 7657), true)

        var loggedMessage: String? = null
        val oldLogger = workerLog
        workerLog = { level, message, _ ->
            if (level == "w") loggedMessage = message
        }
        try {
            val failingStore = object : JournalConfirmationStore {
                override fun inspect(): StoreInspectResult<JournalConfirmation> = StoreInspectResult.Missing
                override fun confirm(fingerprint: String) { throw IOException("disk error") }
                override fun settle() { throw IOException("disk error") }
                override fun addListener(listener: () -> Unit): () -> Unit = {}
            }

            JournalConfirmationGrandfather.grandfather(graph, failingStore)
            assertIs<StoreInspectResult.Missing>(failingStore.inspect())
            assertEquals("confirmation write failed; pairing left unconfirmed", loggedMessage)

            // resetForTest and run grandfather again
            loggedMessage = null
            JournalConfirmationGrandfather.resetForTest()
            JournalConfirmationGrandfather.grandfather(graph, failingStore)
            assertIs<StoreInspectResult.Missing>(failingStore.inspect())
            assertEquals("confirmation write failed; pairing left unconfirmed", loggedMessage)
        } finally {
            workerLog = oldLogger
        }
    }

    @Test
    fun grandfatherUncertainWritesNothingThenCommittedConfirmsWithoutSettledKey() {
        val graph = createGraph()
        graph.installOrReplace(testHome(cert = "sha256:cert-a"), testCred(), DirectEndpoint("10.0.0.1", 7657), true)
        // Corrupt commit marker to make snapshot Uncertain
        File(temp.root, "pairing.commit").writeText("invalid-commit-data")

        val uncertainGraph = createGraph()
        val confirmFile = File(temp.root, "journal_confirmation.json")
        val confirmStore = FileJournalConfirmationStore(confirmFile)

        JournalConfirmationGrandfather.grandfather(uncertainGraph, confirmStore)
        assertFalse(confirmFile.exists())
        assertIs<StoreInspectResult.Missing>(confirmStore.inspect())

        // Reset and run against committed graph
        JournalConfirmationGrandfather.resetForTest()
        val cleanGraph = createGraph()
        cleanGraph.installOrReplace(testHome(cert = "sha256:cert-a"), testCred(), DirectEndpoint("10.0.0.1", 7657), true)
        JournalConfirmationGrandfather.grandfather(cleanGraph, confirmStore)

        val inspected = assertIs<StoreInspectResult.Ready<JournalConfirmation>>(confirmStore.inspect())
        assertEquals("sha256:cert-a", inspected.value.confirmed)
        assertFalse(confirmFile.readText().contains("settled"))
    }
}
