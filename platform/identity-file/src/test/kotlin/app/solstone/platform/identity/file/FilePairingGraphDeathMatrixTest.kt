// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.identity.file

import app.solstone.core.identity.ClientCredential
import app.solstone.core.identity.DurableTxnStep
import app.solstone.core.identity.GraphMutationResult
import app.solstone.core.identity.PairingGraphSnapshot
import app.solstone.core.model.DirectEndpoint
import app.solstone.core.model.IdentityState
import app.solstone.core.model.PairedHome
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FilePairingGraphDeathMatrixTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun testHome(id: String = "home-1", cert: String = "sha256:cert-1") = PairedHome(
        instanceId = id,
        homeLabel = "Home",
        relayOrigin = "https://link.solstone.app",
        caChainFingerprint = "sha256:ca-1",
        clientCertFingerprint = cert,
        observerHandle = "obs",
        deviceToken = "token-1",
        expiresAt = "2030-01-01T00:00:00Z",
        state = IdentityState.PAIRED,
    )

    private fun testCred(key: String = "key-1") = ClientCredential(
        privateKeyPem = "-----BEGIN PRIVATE KEY-----\n$key\n-----END PRIVATE KEY-----\n",
        clientCertPem = "-----BEGIN CERTIFICATE-----\ncert-1\n-----END CERTIFICATE-----\n",
        caChainPem = listOf("-----BEGIN CERTIFICATE-----\nca-1\n-----END CERTIFICATE-----\n"),
    )

    @Test
    fun initialDirectInterruptedAtStagingRestoresAbsent() {
        val identFile = File(temp.root, "identity.tsv")
        val credFile = File(temp.root, "credential.pem")
        val epFile = File(temp.root, "endpoint.txt")
        val commitFile = File(temp.root, "pairing.commit")
        val protector = SpySecretProtector()

        var crash = false
        val graph = FilePairingGraph(
            identityFile = identFile,
            credentialFile = credFile,
            endpointFile = epFile,
            commitMarkerFile = commitFile,
            protector = protector,
            stepHook = { step, _ ->
                if (step == DurableTxnStep.STAGING_WRITE && crash) {
                    throw RuntimeException("crash at staging write")
                }
            },
        )

        assertTrue(graph.currentSnapshot() is PairingGraphSnapshot.Absent)

        crash = true
        val result = graph.installOrReplace(
            home = testHome(),
            credential = testCred(),
            directEndpoint = DirectEndpoint("10.0.0.1", 7657),
            isDirectAssociated = true,
        )
        assertTrue(result is GraphMutationResult.PersistenceFailed)

        // Fresh publisher reconstructed on same directory
        val recovered = FilePairingGraph(
            identityFile = identFile,
            credentialFile = credFile,
            endpointFile = epFile,
            commitMarkerFile = commitFile,
            protector = protector,
        )
        assertTrue(recovered.currentSnapshot() is PairingGraphSnapshot.Absent)
        assertEquals(null, recovered.acquireDirectLease())
    }

    @Test
    fun initialDirectCompletedRestoresDirectCommitted() {
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

        val result = graph.installOrReplace(
            home = testHome(),
            credential = testCred(),
            directEndpoint = DirectEndpoint("10.0.0.1", 7657),
            isDirectAssociated = true,
        )
        assertTrue(result is GraphMutationResult.Applied)
        assertTrue((graph.currentSnapshot() as PairingGraphSnapshot.Committed).isDirectEligible)

        // Process restart
        val recovered = FilePairingGraph(
            identityFile = identFile,
            credentialFile = credFile,
            endpointFile = epFile,
            commitMarkerFile = commitFile,
            protector = protector,
        )
        val snap = recovered.currentSnapshot() as PairingGraphSnapshot.Committed
        assertEquals("home-1", snap.home.instanceId)
        assertTrue(snap.isDirectEligible)
        assertTrue(snap.isRelayEligible)
    }

    @Test
    fun sameInstanceNewCertInterruptedWithMixedBytesRestoresUncertain() {
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

        // Install original
        graph.installOrReplace(
            home = testHome(cert = "sha256:cert-1"),
            credential = testCred("key-1"),
            directEndpoint = DirectEndpoint("10.0.0.1", 7657),
            isDirectAssociated = true,
        )

        // Simulate crash right after credential overwrite but before identity and commit marker update
        FileClientCredentialStore(credFile, protector).save(testCred("key-2"))

        // Fresh publisher reconstructs
        val recovered = FilePairingGraph(
            identityFile = identFile,
            credentialFile = credFile,
            endpointFile = epFile,
            commitMarkerFile = commitFile,
            protector = protector,
        )
        // Checksums mismatch -> Uncertain
        assertTrue(recovered.currentSnapshot() is PairingGraphSnapshot.Uncertain)
        assertEquals(null, recovered.acquireDirectLease())
        assertEquals(null, recovered.acquireRelayLease())
    }

    @Test
    fun forgetCompletedRestoresAbsent() {
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

        graph.installOrReplace(
            home = testHome(),
            credential = testCred(),
            directEndpoint = DirectEndpoint("10.0.0.1", 7657),
            isDirectAssociated = true,
        )
        val forgetResult = graph.forget()
        assertTrue(forgetResult is GraphMutationResult.Cleared)
        assertTrue(graph.currentSnapshot() is PairingGraphSnapshot.Absent)

        val recovered = FilePairingGraph(
            identityFile = identFile,
            credentialFile = credFile,
            endpointFile = epFile,
            commitMarkerFile = commitFile,
            protector = protector,
        )
        assertTrue(recovered.currentSnapshot() is PairingGraphSnapshot.Absent)
    }

    @Test
    fun exceptionAfterDurableDecisionKeepsCommittedGraph() {
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
            stepHook = { step, _ ->
                if (step == DurableTxnStep.DURABLE_COMMIT_DECISION) error("simulated process boundary")
            },
        )

        val result = graph.installOrReplace(
            home = testHome(),
            credential = testCred(),
            directEndpoint = DirectEndpoint("10.0.0.1", 7657),
            isDirectAssociated = true,
        )

        assertTrue(result is GraphMutationResult.Applied)
        val recovered = FilePairingGraph(identFile, credFile, epFile, commitFile, protector)
        assertTrue((recovered.currentSnapshot() as PairingGraphSnapshot.Committed).isDirectEligible)
    }

    @Test
    fun malformedDecisionOrMissingCommittedEndpointIsUncertain() {
        val identFile = File(temp.root, "identity.tsv")
        val credFile = File(temp.root, "credential.pem")
        val epFile = File(temp.root, "endpoint.txt")
        val commitFile = File(temp.root, "pairing.commit")
        val protector = SpySecretProtector()
        val graph = FilePairingGraph(identFile, credFile, epFile, commitFile, protector)
        graph.installOrReplace(testHome(), testCred(), DirectEndpoint("10.0.0.1", 7657), true)

        epFile.delete()
        val missingEndpoint = FilePairingGraph(identFile, credFile, epFile, commitFile, protector)
        assertTrue(missingEndpoint.currentSnapshot() is PairingGraphSnapshot.Uncertain)

        commitFile.writeText("not-a-commit-marker")
        val malformedMarker = FilePairingGraph(identFile, credFile, epFile, commitFile, protector)
        assertTrue(malformedMarker.currentSnapshot() is PairingGraphSnapshot.Uncertain)
    }
}
