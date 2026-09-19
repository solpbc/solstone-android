// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.identity.file

import app.solstone.core.identity.ClientCredential
import app.solstone.core.identity.PairingGraphSnapshot
import app.solstone.core.identity.PairingLease
import app.solstone.core.model.DirectEndpoint
import app.solstone.core.model.IdentityState
import app.solstone.core.model.PairedHome
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FilePairingGraphSubscriptionTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun testHome(id: String = "home-1") = PairedHome(
        instanceId = id,
        homeLabel = "Home",
        relayOrigin = "https://link.solstone.app",
        caChainFingerprint = "sha256:ca-1",
        clientCertFingerprint = "sha256:cert-1",
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

    @Test
    fun subscribeDeliversCurrentSnapshotAndSubsequentUpdates() {
        val identFile = File(temp.root, "identity.tsv")
        val credFile = File(temp.root, "credential.pem")
        val epFile = File(temp.root, "endpoint.txt")
        val commitFile = File(temp.root, "pairing.commit")
        val protector = SpySecretProtector()

        val graph = FilePairingGraph(
            identityFile = identFile,
            credentialFile = credFile,
            endpointFile = epFile,
            commitMarkerFile = commitFile,
            protector = protector,
        )

        val received = CopyOnWriteArrayList<PairingGraphSnapshot>()
        val delivery = CountDownLatch(2)
        val handle = graph.subscribe {
            received.add(it)
            delivery.countDown()
        }

        graph.installOrReplace(
            home = testHome(),
            credential = testCred(),
            directEndpoint = DirectEndpoint("10.0.0.1", 7657),
            isDirectAssociated = true,
        )

        assertTrue(delivery.await(2, TimeUnit.SECONDS))
        assertEquals(2, received.size)
        assertTrue(received[0] is PairingGraphSnapshot.Absent)
        assertTrue(received[1] is PairingGraphSnapshot.Committed)

        handle.cancel()
        graph.forget()

        Thread.sleep(50)
        assertEquals(2, received.size)
    }

    @Test
    fun blockedSubscriberDoesNotBlockMutationOrOtherSubscribers() {
        val graph = FilePairingGraph(
            identityFile = File(temp.root, "identity.tsv"),
            credentialFile = File(temp.root, "credential.pem"),
            endpointFile = File(temp.root, "endpoint.txt"),
            commitMarkerFile = File(temp.root, "pairing.commit"),
            protector = SpySecretProtector(),
        )
        val releaseBlocked = CountDownLatch(1)
        val blockedStarted = CountDownLatch(1)
        val blocked = graph.subscribe {
            blockedStarted.countDown()
            releaseBlocked.await(2, TimeUnit.SECONDS)
        }
        assertTrue(blockedStarted.await(2, TimeUnit.SECONDS))

        val responsiveDeliveries = CountDownLatch(2)
        val responsive = graph.subscribe { responsiveDeliveries.countDown() }
        val result = graph.installOrReplace(
            home = testHome(),
            credential = testCred(),
            directEndpoint = DirectEndpoint("10.0.0.1", 7657),
            isDirectAssociated = true,
        )

        assertTrue(result is app.solstone.core.identity.GraphMutationResult.Applied)
        assertTrue(responsiveDeliveries.await(2, TimeUnit.SECONDS))
        blocked.cancel()
        responsive.cancel()
        releaseBlocked.countDown()
    }

    @Test
    fun monotonicRevisionsNeverRollBackAcrossABA() {
        val identFile = File(temp.root, "identity.tsv")
        val credFile = File(temp.root, "credential.pem")
        val epFile = File(temp.root, "endpoint.txt")
        val commitFile = File(temp.root, "pairing.commit")
        val protector = SpySecretProtector()

        val graph = FilePairingGraph(
            identityFile = identFile,
            credentialFile = credFile,
            endpointFile = epFile,
            commitMarkerFile = commitFile,
            protector = protector,
        )

        // Install A
        graph.installOrReplace(testHome("A"), testCred(), DirectEndpoint("10.0.0.1", 7657), true)
        val snapA = graph.currentSnapshot() as PairingGraphSnapshot.Committed
        val leaseA = graph.acquireDirectLease()!!
        assertTrue(graph.validateLease(leaseA))

        // Forget A -> Absent
        graph.forget()
        assertFalse(graph.validateLease(leaseA))

        // Reinstall A (same parameters)
        graph.installOrReplace(testHome("A"), testCred(), DirectEndpoint("10.0.0.1", 7657), true)
        val snapA2 = graph.currentSnapshot() as PairingGraphSnapshot.Committed

        assertTrue(snapA2.sequenceNumber > snapA.sequenceNumber)
        assertTrue(snapA2.revisions.pairingRevision > snapA.revisions.pairingRevision)
        assertFalse(graph.validateLease(leaseA)) // Old lease A is fenced!

        val leaseA2 = graph.acquireDirectLease()!!
        assertTrue(graph.validateLease(leaseA2))
    }

    @Test
    fun directLeaseRemainsValidAcrossUnrelatedRelayRefresh() {
        val identFile = File(temp.root, "identity.tsv")
        val credFile = File(temp.root, "credential.pem")
        val epFile = File(temp.root, "endpoint.txt")
        val commitFile = File(temp.root, "pairing.commit")
        val protector = SpySecretProtector()

        val graph = FilePairingGraph(
            identityFile = identFile,
            credentialFile = credFile,
            endpointFile = epFile,
            commitMarkerFile = commitFile,
            protector = protector,
        )

        graph.installOrReplace(testHome("A"), testCred(), DirectEndpoint("10.0.0.1", 7657), true)
        val directLease = graph.acquireDirectLease()!!
        assertTrue(graph.validateLease(directLease))

        // Update relay access only
        val currentSnap = graph.currentSnapshot() as PairingGraphSnapshot.Committed
        graph.updateRelayAccess(currentSnap.pairing, "https://new.relay.app", "token-new", "2035-01-01T00:00:00Z")

        // Direct lease is still valid!
        assertTrue(graph.validateLease(directLease))
    }
}
