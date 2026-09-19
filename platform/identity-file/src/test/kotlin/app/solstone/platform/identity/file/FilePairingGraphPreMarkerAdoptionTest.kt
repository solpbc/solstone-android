// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.identity.file

import app.solstone.core.identity.ClientCredential
import app.solstone.core.identity.PairingGraphSnapshot
import app.solstone.core.model.IdentityState
import app.solstone.core.model.PairedHome
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FilePairingGraphPreMarkerAdoptionTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun testHome() = PairedHome(
        instanceId = "home-1",
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
    fun unreadableIdentityAdoptedAsUncertainNeverAbsent() {
        val identFile = File(temp.root, "identity.tsv")
        val credFile = File(temp.root, "credential.pem")
        val epFile = File(temp.root, "endpoint.txt")
        val commitFile = File(temp.root, "pairing.commit")
        val protector = SpySecretProtector()

        // Create corrupt/unreadable identity file with no commit marker
        identFile.writeBytes(byteArrayOf(0x01, 0x02, 0x03, 0x04))

        val graph = FilePairingGraph(
            identityFile = identFile,
            credentialFile = credFile,
            endpointFile = epFile,
            commitMarkerFile = commitFile,
            protector = protector,
        )

        assertTrue(graph.currentSnapshot() is PairingGraphSnapshot.Uncertain)
        assertTrue(commitFile.exists())
        assertEquals(CommitMarkerStatus.UNCERTAIN, PairingCommitMarker.parse(commitFile)?.status)
    }

    @Test
    fun legacyEndpointAdoptedAsDirectIneligibleUntilProven() {
        val identFile = File(temp.root, "identity.tsv")
        val credFile = File(temp.root, "credential.pem")
        val epFile = File(temp.root, "endpoint.txt")
        val commitFile = File(temp.root, "pairing.commit")
        val protector = SpySecretProtector()

        FileIdentityStore(identFile, protector).save(testHome())
        FileClientCredentialStore(credFile, protector).save(testCred())
        epFile.writeText("10.0.0.5\n7657\n")

        val graph = FilePairingGraph(
            identityFile = identFile,
            credentialFile = credFile,
            endpointFile = epFile,
            commitMarkerFile = commitFile,
            protector = protector,
        )

        val snap = graph.currentSnapshot() as PairingGraphSnapshot.Committed
        assertEquals("home-1", snap.home.instanceId)
        assertTrue(snap.hasDirectEndpoint)
        assertFalse(snap.directAssociated)
        assertFalse(snap.isDirectEligible)
        assertTrue(snap.isRelayEligible)
        assertEquals(null, graph.acquireDirectLease())
    }

    @Test
    fun provenDirectProofUpdatesAssociationAndEnablesDirectRoute() {
        val identFile = File(temp.root, "identity.tsv")
        val credFile = File(temp.root, "credential.pem")
        val epFile = File(temp.root, "endpoint.txt")
        val commitFile = File(temp.root, "pairing.commit")
        val protector = SpySecretProtector()

        FileIdentityStore(identFile, protector).save(testHome())
        FileClientCredentialStore(credFile, protector).save(testCred())
        epFile.writeText("10.0.0.5\n7657\n")

        val graph = FilePairingGraph(
            identityFile = identFile,
            credentialFile = credFile,
            endpointFile = epFile,
            commitMarkerFile = commitFile,
            protector = protector,
        )

        val initialSnap = graph.currentSnapshot() as PairingGraphSnapshot.Committed
        val proven = graph.associateDirectIfProven(
            expectedPairing = initialSnap.pairing,
            endpoint = app.solstone.core.model.DirectEndpoint("10.0.0.5", 7657),
            proof = { true },
        )
        assertTrue(proven)

        val updatedSnap = graph.currentSnapshot() as PairingGraphSnapshot.Committed
        assertTrue(updatedSnap.directAssociated)
        assertTrue(updatedSnap.isDirectEligible)
        assertEquals(app.solstone.core.model.DirectEndpoint("10.0.0.5", 7657), graph.acquireDirectLease()?.endpoint)
    }
}
