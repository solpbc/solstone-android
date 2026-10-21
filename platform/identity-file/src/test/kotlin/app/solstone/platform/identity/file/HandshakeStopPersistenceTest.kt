// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.identity.file

import app.solstone.core.identity.ClientCredential
import app.solstone.core.identity.GraphMutationResult
import app.solstone.core.identity.PairingGeneration
import app.solstone.core.identity.PairingGraphSnapshot
import app.solstone.core.model.DirectEndpoint
import app.solstone.core.model.IdentityState
import app.solstone.core.model.PairedHome
import app.solstone.core.pl.HandshakeFailure
import app.solstone.core.pl.HandshakeGuard
import app.solstone.core.pl.HandshakeRefusalTracker
import app.solstone.core.pl.HandshakeStop
import app.solstone.core.pl.JournalRefusalException
import app.solstone.core.pl.REFUSAL_LIMIT
import app.solstone.core.pl.REFUSAL_SPACING_MILLIS
import app.solstone.core.pl.RefusalRecord
import app.solstone.core.pl.RefusalRecordRead
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.rules.TemporaryFolder

/**
 * The refusal record and the stop on disk, read back by a fresh "process": a new store, tracker,
 * pairing graph and guard over the same files.
 */
class HandshakeStopPersistenceTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val credential = "sha256:cert-1"
    private val t0 = 1_700_000_000_000L

    private fun recordFile() = File(temp.root, "handshake_refusal.json")

    /** A new process: nothing in memory, only what the file says. */
    private fun process(now: Long) = HandshakeRefusalTracker(FileRefusalRecordStore(recordFile()), { now }, { now })

    // Falsified by an in-memory-only count (a relaunch would start from zero and never stop) or by
    // a record that loses its time or credential.
    @Test
    fun theRecordHoldsCountTimeAndCredentialAcrossProcesses() {
        var now = t0
        repeat(REFUSAL_LIMIT - 1) {
            assertNull(process(now).failed(credential, HandshakeFailure.TLS_REFUSED))
            now += REFUSAL_SPACING_MILLIS
        }
        val stored = assertIs<RefusalRecordRead.Present>(FileRefusalRecordStore(recordFile()).read()).record
        assertEquals(RefusalRecord(credential, REFUSAL_LIMIT - 1, now - REFUSAL_SPACING_MILLIS, null), stored)

        val last = stored.lastCountedAtMillis!!
        assertNull(process(last + REFUSAL_SPACING_MILLIS - 1_000).failed(credential, HandshakeFailure.TLS_REFUSED))
        assertEquals(REFUSAL_LIMIT - 1, process(last).current(credential)?.refusals)
        assertEquals(HandshakeStop.REFUSALS_EXHAUSTED, process(last + REFUSAL_SPACING_MILLIS).failed(credential, HandshakeFailure.TLS_REFUSED))
        assertEquals(HandshakeStop.REFUSALS_EXHAUSTED, process(last + 1).stop(credential))
    }

    // Falsified by measuring a negative interval: after the clock moved back, refusals would never
    // count again until it caught up.
    @Test
    fun aFutureStoredTimeStillCounts() {
        FileRefusalRecordStore(recordFile()).write(RefusalRecord(credential, 3, t0 + 7L * 24 * 60 * 60 * 1000, null))
        process(t0).failed(credential, HandshakeFailure.TLS_REFUSED)
        assertEquals(RefusalRecord(credential, 4, t0, null), process(t0).current(credential))
    }

    // The rule's example on disk: stored 5 at T, the read fails, refusals at T+10 s and T+400 s,
    // then the read succeeds: 6. Falsified by treating a failed read as absent (it would start from
    // zero and overwrite the 5) or by dropping the outcomes kept while it failed.
    @Test
    fun aFailedFirstReadNeitherResetsNorOverwritesAndReplaysOnTheNextGoodRead() {
        val store = FileRefusalRecordStore(recordFile())
        store.write(RefusalRecord(credential, 5, t0, null))
        val good = recordFile().readBytes()
        recordFile().writeText("{\"version\":1,") // unreadable, but present
        var now = t0 + 10_000
        val tracker = HandshakeRefusalTracker(FileRefusalRecordStore(recordFile()), { now }, { now })
        assertIs<RefusalRecordRead.Failed>(store.read())
        assertNull(tracker.failed(credential, HandshakeFailure.TLS_REFUSED))
        now = t0 + 400_000
        assertNull(tracker.failed(credential, HandshakeFailure.TLS_REFUSED))
        assertEquals("{\"version\":1,", recordFile().readText(), "a record that could not be read is never written over")

        recordFile().writeBytes(good)
        assertEquals(RefusalRecord(credential, 6, t0 + 400_000, null), tracker.current(credential))
        assertEquals(6, process(now).current(credential)?.refusals)
    }

    // Falsified by losing an acceptance that arrived while the record could not be read.
    @Test
    fun aFailedReadThenAnAcceptanceThenAGoodReadStoresZero() {
        val store = FileRefusalRecordStore(recordFile())
        store.write(RefusalRecord(credential, 5, t0, null))
        val good = recordFile().readBytes()
        recordFile().writeText("garbage")
        val tracker = HandshakeRefusalTracker(FileRefusalRecordStore(recordFile()), { t0 + 600_000 }, { 0L })
        tracker.accepted(credential)
        recordFile().writeBytes(good)
        assertEquals(0, tracker.current(credential)?.refusals)
        assertEquals(0, assertIs<RefusalRecordRead.Present>(store.read()).record.refusals)
    }

    // A stop reads revoked in a fresh process, through the pairing graph's own boundary, and only a
    // new pairing ends it. Falsified by a stop held in memory only, or written as a field the
    // pairing record's other writers rewrite.
    @Test
    fun aStoppedCredentialReadsRevokedInAFreshProcessUntilANewPairing() {
        val graph = graph()
        install(graph, "sha256:cert-1")
        val pairing = PairingGeneration("home-1", "sha256:cert-1")
        val guard = HandshakeGuard(process(t0), graph, FilePairingRefusalStore(File(temp.root, "pairing_refusal.json")))
        val attempt = guard.begin(pairing)
        attempt.onRefused(JournalRefusalException(HandshakeFailure.TLS_ACCESS_DENIED, 49))
        assertEquals(HandshakeStop.TLS_ACCESS_DENIED, attempt.stop)

        val reopened = graph()
        val snapshot = assertIs<PairingGraphSnapshot.Committed>(reopened.currentSnapshot())
        assertEquals(IdentityState.REVOKED, snapshot.home.state)
        assertTrue(reopened.acquireDirectLeases().isEmpty())
        assertNull(reopened.acquireRelayLease())
        val freshGuard = HandshakeGuard(process(t0 + 1), reopened)
        assertTrue(freshGuard.stopped(pairing))
        assertTrue(FilePairingRefusalStore(File(temp.root, "pairing_refusal.json")).refusedFor(pairing))

        // Acceptance never clears a stop.
        process(t0 + 2).accepted("sha256:cert-1")
        assertEquals(HandshakeStop.TLS_ACCESS_DENIED, process(t0 + 3).stop("sha256:cert-1"))

        install(reopened, "sha256:cert-2")
        val repaired = PairingGeneration("home-1", "sha256:cert-2")
        assertEquals(IdentityState.PAIRED, (reopened.currentSnapshot() as PairingGraphSnapshot.Committed).home.state)
        assertTrue(!HandshakeGuard(process(t0 + 4), reopened).stopped(repaired))
    }

    // Falsified by a writer that rebuilds the home from an older snapshot: a relay-access update or
    // an address replace working from before the stop would write the paired state back.
    @Test
    fun writersWorkingFromASnapshotBeforeTheStopLeaveItRevoked() {
        val graph = graph()
        install(graph, "sha256:cert-1")
        val before = assertIs<PairingGraphSnapshot.Committed>(graph.currentSnapshot())
        assertIs<GraphMutationResult.Applied>(graph.revokePairing(before.pairing))

        graph.updateRelayAccess(before.pairing, "https://link.solstone.app", "token-2", null)
        assertIs<GraphMutationResult.Conflict>(graph.replaceDirectEndpoints(before, listOf(DirectEndpoint("10.0.0.9", 7657))))
        graph.associateDirectIfProven(before.pairing, DirectEndpoint("10.0.0.1", 7657)) { true }
        graph.revokeRelayAccess(before.pairing)

        assertEquals(IdentityState.REVOKED, (graph.currentSnapshot() as PairingGraphSnapshot.Committed).home.state)
        assertEquals(IdentityState.REVOKED, (graph().currentSnapshot() as PairingGraphSnapshot.Committed).home.state)
    }

    private fun graph() = FilePairingGraph(
        identityFile = File(temp.root, "identity.tsv"),
        credentialFile = File(temp.root, "credential.pem"),
        endpointFile = File(temp.root, "endpoint.txt"),
        commitMarkerFile = File(temp.root, "pairing.commit"),
        protector = SpySecretProtector(),
    )

    private fun install(graph: FilePairingGraph, cert: String) {
        val home = PairedHome(
            instanceId = "home-1",
            homeLabel = "Home",
            relayOrigin = "https://link.solstone.app",
            caChainFingerprint = "sha256:ca-1",
            clientCertFingerprint = cert,
            observerHandle = null,
            deviceToken = "token-1",
            expiresAt = null,
            state = IdentityState.PAIRED,
        )
        val body = cert.removePrefix("sha256:")
        val credential = ClientCredential(
            privateKeyPem = "-----BEGIN PRIVATE KEY-----\nkey\n-----END PRIVATE KEY-----\n",
            clientCertPem = "-----BEGIN CERTIFICATE-----\n$body\n-----END CERTIFICATE-----\n",
            caChainPem = listOf("-----BEGIN CERTIFICATE-----\nca-1\n-----END CERTIFICATE-----\n"),
        )
        assertIs<GraphMutationResult.Applied>(
            graph.installOrReplace(home, credential, DirectEndpoint("10.0.0.1", 7657), isDirectAssociated = true),
        )
    }
}
