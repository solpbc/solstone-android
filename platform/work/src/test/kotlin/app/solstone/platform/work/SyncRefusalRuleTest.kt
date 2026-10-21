// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.work

import app.solstone.core.identity.ClientCredential
import app.solstone.core.identity.JournalConfirmationPolicy
import app.solstone.core.identity.PairingGeneration
import app.solstone.core.identity.PairingGraphSnapshot
import app.solstone.core.model.DirectEndpoint
import app.solstone.core.model.IdentityState
import app.solstone.core.model.PairedHome
import app.solstone.core.model.QueueState
import app.solstone.core.observer.INGEST_PATH
import app.solstone.core.observer.SEGMENTS_PATH
import app.solstone.core.pl.ClientReportedDescription
import app.solstone.core.pl.HandshakeFailure
import app.solstone.core.pl.HandshakeGuard
import app.solstone.core.pl.HandshakeRefusalTracker
import app.solstone.core.pl.HandshakeStop
import app.solstone.core.pl.HttpResponse
import app.solstone.core.pl.JournalIdentityRefreshCoordinator
import app.solstone.core.pl.JournalRefusalException
import app.solstone.core.pl.JournalVersionRefreshCoordinator
import app.solstone.core.pl.PlHttpClient
import app.solstone.core.pl.REFUSAL_LIMIT
import app.solstone.core.pl.REFUSAL_SPACING_MILLIS
import app.solstone.core.pl.RelayAccessRefreshCoordinator
import app.solstone.core.pl.UnknownJournalException
import app.solstone.core.sources.MAIN_STREAM
import app.solstone.platform.identity.file.FileClientCredentialStore
import app.solstone.platform.identity.file.FileEndpointStore
import app.solstone.platform.identity.file.FileIdentityStore
import app.solstone.platform.identity.file.FileJournalConfirmationStore
import app.solstone.platform.identity.file.FileJournalMarkStore
import app.solstone.platform.identity.file.FileJournalVersionStore
import app.solstone.platform.identity.file.FilePairingGraph
import app.solstone.platform.identity.file.FilePairingRefusalStore
import app.solstone.platform.identity.file.FileRefusalRecordStore
import app.solstone.platform.identity.file.SecretProtector
import java.io.Closeable
import java.io.File
import java.net.ConnectException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The refusal rule across a whole sync run: every leg of one attempt, the shared tracker, the stop
 * written as the revoked identity, and what a relaunch sees. Stores are the real file-backed ones.
 */
class SyncRefusalRuleTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val protector = object : SecretProtector {
        override fun protect(plaintext: ByteArray) = plaintext
        override fun unprotect(wrapped: ByteArray) = wrapped
    }
    private val diag = CopyOnWriteArrayList<String>()
    private var now = 1_700_000_000_000L
    private lateinit var dir: File
    private lateinit var spool: File

    @Before
    fun setUp() {
        workerLog = { _, _, _ -> }
        JournalConfirmationPolicy.consults = true
        JournalConfirmationGrandfather.resetForTest()
        DialDiagnostics.sink = { diag += it }
        dir = temp.newFolder("pl")
        spool = temp.newFolder("spool")
    }

    @After
    fun tearDown() {
        DialDiagnostics.sink = null
        workerLog = { _, _, _ -> }
        JournalConfirmationGrandfather.resetForTest()
    }

    // Falsified by dialling on after a verdict: the relay would be tried for a LAN refusal (and the
    // refusal counted again, or hidden), or the second saved address dialled.
    @Test
    fun aLanRefusalOf80DecidesTheAttemptIsCountedAndTheRelayIsNotDialled() {
        val (stores, _) = paired("sha256:cert-a", listOf(LAN_1, LAN_2))
        val opened = CopyOnWriteArrayList<SyncTransport>()
        val outcome = run(stores) { transport ->
            opened += transport
            when (transport) {
                is SyncTransport.Direct -> Journal(statusFailure = JournalRefusalException(HandshakeFailure.TLS_REFUSED, 80))
                is SyncTransport.Relay -> Journal()
            }
        }
        assertEquals(SyncOutcome.RETRY, outcome)
        assertEquals(listOf<SyncTransport>(SyncTransport.Direct(direct(LAN_1))), opened)
        assertEquals(1, refusals("sha256:cert-a"))
        assertEquals(IdentityState.PAIRED, state(stores))
        assertFalse(refusedFor(stores, "sha256:cert-a"))
    }

    // Falsified by letting an unknown journal end the attempt (the relay never tried) or by losing
    // the sighting once the relay answered.
    @Test
    fun aLanUnknownJournalWithAWorkingRelayReachesTheRelayAndRecordsTheSighting() {
        val (stores, graph) = paired("sha256:cert-a", listOf(LAN_1))
        confirmCurrentJournal(graph, stores.journalConfirmationStore, "sha256:cert-a")
        val drain = queued()
        val opened = CopyOnWriteArrayList<SyncTransport>()
        val outcome = run(stores, drain) { transport ->
            opened += transport
            when (transport) {
                is SyncTransport.Direct -> throw UnknownJournalException("${LAN_1.host}:${LAN_1.port}", CLAIM)
                is SyncTransport.Relay -> Journal(drains = true)
            }
        }
        assertEquals(SyncOutcome.SUCCESS, outcome)
        assertTrue(opened.last() is SyncTransport.Relay, "$opened")
        assertEquals(QueueState.EVICTED, drain.row(SEGMENT).state)
        assertTrue(diag.contains("kind=dial host=10.0.0.1 port=7657 outcome=unknown-journal claimed-journal=$CLAIM"), "$diag")
        assertEquals(0, refusals("sha256:cert-a"))
    }

    // Falsified by recording the sighting without where it answered, or only when a later leg works.
    @Test
    fun aLanUnknownJournalWithAnUnreachableRelayRecordsTheSightingWithItsAddress() {
        val (stores, _) = paired("sha256:cert-a", listOf(LAN_1))
        val outcome = run(stores) { transport ->
            when (transport) {
                is SyncTransport.Direct -> throw UnknownJournalException("${LAN_1.host}:${LAN_1.port}", CLAIM)
                is SyncTransport.Relay -> throw ConnectException("relay unreachable")
            }
        }
        assertEquals(SyncOutcome.RETRY, outcome)
        assertTrue(diag.contains("kind=dial host=10.0.0.1 port=7657 outcome=unknown-journal claimed-journal=$CLAIM"), "$diag")
        assertEquals(0, refusals("sha256:cert-a"))
        assertEquals(IdentityState.PAIRED, state(stores))
    }

    // A peer presenting the paired CA as its own leaf claims nothing, and the relay is still tried.
    // Falsified by naming the paired journal as the claim, or by stopping at that leg.
    @Test
    fun aLanPeerPresentingThePairedCaAsItsOwnLeafClaimsNothingAndTheRelayIsTried() {
        val (stores, _) = paired("sha256:cert-a", listOf(LAN_1))
        val opened = CopyOnWriteArrayList<SyncTransport>()
        run(stores) { transport ->
            opened += transport
            when (transport) {
                is SyncTransport.Direct -> throw UnknownJournalException("${LAN_1.host}:${LAN_1.port}", null)
                is SyncTransport.Relay -> Journal()
            }
        }
        assertTrue(opened.any { it is SyncTransport.Relay }, "$opened")
        assertTrue(diag.contains("kind=dial host=10.0.0.1 port=7657 outcome=unknown-journal claimed-journal=none"), "$diag")
    }

    // A 403 is the journal's answer: it clears the TLS count but is not a TLS stop, and it never
    // clears the refused state. Falsified by leaving the count (acceptance not recorded), by
    // clearing the refused state, or by writing the revoked identity for an HTTP answer.
    @Test
    fun a403AfterSixCountedRefusalsClearsTheCountButLeavesTheRefusedStateAndIdentity() {
        val (stores, _) = paired("sha256:cert-a", listOf(LAN_1))
        repeat(REFUSAL_LIMIT - 1) {
            run(stores) { Journal(statusFailure = JournalRefusalException(HandshakeFailure.TLS_REFUSED, 80)) }
            now += REFUSAL_SPACING_MILLIS
        }
        assertEquals(REFUSAL_LIMIT - 1, refusals("sha256:cert-a"))
        stores.pairingRefusalStore!!.markRefused(PairingGeneration(HOME_A, "sha256:cert-a"))

        run(stores) { Journal(status = 403, statusBody = """{"error":"pl_revoked"}""") }

        assertEquals(0, refusals("sha256:cert-a"))
        assertNull(tracker().stop("sha256:cert-a"))
        assertTrue(refusedFor(stores, "sha256:cert-a"))
        assertEquals(IdentityState.PAIRED, state(stores))
    }

    // The seventh counted refusal stops the credential and writes the revoked identity. Falsified
    // by a refusal that marks the device refused but keeps dialling (the old behaviour).
    @Test
    fun theSeventhCountedRefusalStopsAndTheStopIsTheRevokedIdentity() {
        val (stores, _) = paired("sha256:cert-a", listOf(LAN_1))
        repeat(REFUSAL_LIMIT) { index ->
            val outcome = run(stores) { Journal(statusFailure = JournalRefusalException(HandshakeFailure.TLS_REFUSED, 80)) }
            assertEquals(if (index < REFUSAL_LIMIT - 1) SyncOutcome.RETRY else SyncOutcome.FAILURE, outcome)
            now += REFUSAL_SPACING_MILLIS
        }
        assertEquals(HandshakeStop.REFUSALS_EXHAUSTED, tracker().stop("sha256:cert-a"))
        assertEquals(IdentityState.REVOKED, state(stores))
        assertTrue(refusedFor(stores, "sha256:cert-a"))
    }

    // Falsified by counting 46: twenty of them would stop a device whose journal only failed to
    // read its pairing records.
    @Test
    fun certificateUnknownNeverStops() {
        val (stores, _) = paired("sha256:cert-a", listOf(LAN_1))
        repeat(REFUSAL_LIMIT * 3) {
            assertEquals(SyncOutcome.RETRY, run(stores) { Journal(statusFailure = JournalRefusalException(HandshakeFailure.TLS_CERTIFICATE_UNKNOWN, 46)) })
            now += REFUSAL_SPACING_MILLIS
        }
        assertNull(tracker().stop("sha256:cert-a"))
        assertEquals(IdentityState.PAIRED, state(stores))
        assertFalse(refusedFor(stores, "sha256:cert-a"))
    }

    // After a 49 the credential is never dialled again, by this run or a relaunched one, until a
    // re-pair; and the queued segment is kept and delivered to whichever journal is paired next.
    // Falsified by a stop that lives in memory, by discarding queued data with the credential, or
    // by binding queued data to the journal that refused it.
    @Test
    fun aStopSurvivesARelaunchAndQueuedDataDrainsAfterARepairToADifferentJournal() {
        val (stores, _) = paired("sha256:cert-a", listOf(LAN_1))
        val drain = queued()
        assertEquals(
            SyncOutcome.FAILURE,
            run(stores, drain) { Journal(statusFailure = JournalRefusalException(HandshakeFailure.TLS_ACCESS_DENIED, 49)) },
        )
        assertEquals(IdentityState.REVOKED, state(stores))
        assertEquals(QueueState.SEALED, drain.row(SEGMENT).state)

        // A relaunch: new stores, tracker and guard over the same files.
        val relaunched = stores(graph())
        var dialled = 0
        assertEquals(SyncOutcome.FAILURE, run(relaunched, drain) { dialled++; Journal() })
        assertEquals(0, dialled)
        assertTrue(relaunched.handshakeGuard!!.stopped(PairingGeneration(HOME_A, "sha256:cert-a")))

        // A re-pair to a different journal: the queued segment drains there.
        val graph = relaunched.publisher as FilePairingGraph
        graph.installOrReplace(home(HOME_B, "sha256:cert-b"), credential("cert-b"), direct(LAN_2), true)
        confirmCurrentJournal(graph, relaunched.journalConfirmationStore, "sha256:cert-b")
        val outcome = run(relaunched, drain) { Journal(drains = true) }
        assertEquals(SyncOutcome.SUCCESS, outcome)
        assertEquals(QueueState.EVICTED, drain.row(SEGMENT).state)
        assertEquals(HOME_B, drain.row(SEGMENT).homeInstanceId)
    }

    private fun run(stores: SyncStores, drain: FakeDrainStore = queued(), open: (SyncTransport) -> PlHttpClient): SyncOutcome {
        check(SyncDrainGate.tryAcquire()) { "gate was not free" }
        SyncDrainGate.release()
        return completeSyncRun(
            openStores = { stores },
            spoolDir = spool,
            drainStore = drain,
            openClient = { transport, _ -> open(transport) },
            localDescriptionProvider = { ClientReportedDescription("Phone", "1.0", "Android", appId = "app.solstone.phone") },
        )
    }

    private fun paired(cert: String, endpoints: List<DirectEndpoint>): Pair<SyncStores, FilePairingGraph> {
        val graph = graph()
        graph.installOrReplace(home(HOME_A, cert), credential(cert.removePrefix("sha256:")), endpoints.first(), true, directEndpoints = endpoints)
        return stores(graph) to graph
    }

    private fun graph() = FilePairingGraph(
        identityFile = File(dir, "identity.tsv"),
        credentialFile = File(dir, "credential.pem"),
        endpointFile = File(dir, "endpoint.txt"),
        commitMarkerFile = File(dir, "pairing.commit"),
        protector = protector,
        pushKeyFile = File(dir, "push-key.bin"),
        pushKeyProtector = protector,
    )

    private fun tracker() = HandshakeRefusalTracker(FileRefusalRecordStore(File(dir, "handshake_refusal.json")), { now }, { now })

    private fun stores(graph: FilePairingGraph): SyncStores {
        val mutator = PublisherIdentityMutatorAdapter(graph)
        val versions = FileJournalVersionStore(File(dir, "journal_version.tsv"))
        val marks = FileJournalMarkStore(File(dir, "journal_mark.json"))
        val refusals = FilePairingRefusalStore(File(dir, "pairing_refusal.json"))
        return SyncStores(
            publisher = graph,
            pushKeys = graph,
            endpointStore = FileEndpointStore(File(dir, "endpoint.txt")),
            credentialStore = FileClientCredentialStore(File(dir, "credential.pem"), protector),
            identityStore = FileIdentityStore(File(dir, "identity.tsv"), protector),
            identityMutator = mutator,
            journalVersionStore = versions,
            journalVersionCoordinator = JournalVersionRefreshCoordinator(versions),
            relayAccessCoordinator = RelayAccessRefreshCoordinator(mutator),
            journalMarkStore = marks,
            journalIdentityCoordinator = JournalIdentityRefreshCoordinator(store = marks, publisher = graph),
            journalConfirmationStore = FileJournalConfirmationStore(File(dir, "journal_confirmation.json")),
            pairingRefusalStore = refusals,
            handshakeGuard = HandshakeGuard(tracker(), graph, refusals),
        )
    }

    private fun refusals(cert: String): Int = tracker().current(cert)?.refusals ?: 0

    private fun state(stores: SyncStores): IdentityState? =
        (stores.publisher.currentSnapshot() as? PairingGraphSnapshot.Committed)?.home?.state

    private fun refusedFor(stores: SyncStores, cert: String): Boolean =
        stores.pairingRefusalStore!!.refusedFor(PairingGeneration(HOME_A, cert))

    private fun queued(): FakeDrainStore {
        val segmentDir = File(File(File(spool, WORK_TEST_DAY), MAIN_STREAM), "a").apply { mkdirs() }
        File(segmentDir, "a.bin").writeBytes(byteArrayOf(1))
        File(segmentDir, "manifest").writeText(
            "solstone-bundle-manifest-v1\nday=$WORK_TEST_DAY\nsegment=a\nstartEpochMs=1\nendEpochMs=2\nzoneId=UTC\nutcOffsetSeconds=0\n" +
                "[files]\naudio\ta.bin\tsha-a\t1\tapplication/octet-stream\t1\t2\n[gaps]\n",
        )
        val row = segment(SEGMENT).copy(day = WORK_TEST_DAY, stream = MAIN_STREAM, segment = "a", dirSegment = "a")
        return FakeDrainStore(row, files = mapOf(SEGMENT to listOf(file(SEGMENT).copy(name = "a.bin", sha256 = "sha-a"))))
    }

    /** A journal that answers the status request, optionally drains one segment, or fails. */
    private class Journal(
        private val status: Int = 200,
        private val statusBody: String = "",
        private val statusFailure: Exception? = null,
        private val drains: Boolean = false,
    ) : PlHttpClient, Closeable {
        override fun request(method: String, path: String, headers: Map<String, String>, body: ByteArray?, maxResponseBytes: Int): HttpResponse =
            when {
                path == "/app/network/api/status" -> statusFailure?.let { throw it }
                    ?: HttpResponse(status, emptyMap(), statusBody.toByteArray())
                drains && path.startsWith(SEGMENTS_PATH) -> HttpResponse(
                    200,
                    emptyMap(),
                    """{"items":[{"key":"remote","observed":true,"files":[]}],"total":1,"protocol_version":3}""".toByteArray(),
                )
                drains && path == INGEST_PATH -> HttpResponse(
                    200,
                    emptyMap(),
                    """{"status":"ok","segment":"srv-a","file_descriptors":[{"submitted":"a.bin","written":"a.bin","size":1,"sha256":"sha-a","disposition":"written"}]}""".toByteArray(),
                )
                else -> HttpResponse(200, emptyMap(), ByteArray(0))
            }

        override fun close() = Unit
    }

    private companion object {
        const val HOME_A = "home-a"
        const val HOME_B = "home-b"
        const val CLAIM = "0b6c3d5e-1f2a-8b3c-9d4e-5f6a7b8c9d0e"
        const val SEGMENT = "$WORK_TEST_DAY/$MAIN_STREAM/a"
        val LAN_1 = DirectEndpoint("10.0.0.1", 7657)
        val LAN_2 = DirectEndpoint("10.0.0.2", 7657)

        fun direct(endpoint: DirectEndpoint) = app.solstone.core.pl.DirectEndpoint(endpoint.host, endpoint.port)

        fun home(instance: String, cert: String) = PairedHome(
            instanceId = instance,
            homeLabel = "Home",
            relayOrigin = "https://link.solstone.app",
            caChainFingerprint = "sha256:ca-$instance",
            clientCertFingerprint = cert,
            observerHandle = null,
            deviceToken = "token-1",
            expiresAt = "2030-01-01T00:00:00Z",
            state = IdentityState.PAIRED,
        )

        fun credential(body: String) = ClientCredential(
            privateKeyPem = "-----BEGIN PRIVATE KEY-----\nkey\n-----END PRIVATE KEY-----\n",
            clientCertPem = "-----BEGIN CERTIFICATE-----\n$body\n-----END CERTIFICATE-----\n",
            caChainPem = listOf("-----BEGIN CERTIFICATE-----\nca\n-----END CERTIFICATE-----\n"),
        )
    }
}
