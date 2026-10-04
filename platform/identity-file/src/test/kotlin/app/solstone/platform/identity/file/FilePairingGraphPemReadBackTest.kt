// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.identity.file

import app.solstone.core.identity.ClientCredential
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
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class FilePairingGraphPemReadBackTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun testHome(
        id: String = "home-1",
        label: String = "Home",
        certToken: String = "cert-1",
        caToken: String = "ca-1",
    ) = PairedHome(
        instanceId = id,
        homeLabel = label,
        relayOrigin = "https://link.solstone.app",
        caChainFingerprint = "sha256:$caToken",
        clientCertFingerprint = "sha256:$certToken",
        observerHandle = "obs",
        deviceToken = "token-$id",
        expiresAt = null,
        state = IdentityState.PAIRED,
    )

    private fun mixedCredential(
        keyToken: String = "key-1",
        certToken: String = "cert-1",
        ca1Token: String = "ca-1a",
        ca2Token: String = "ca-1b",
    ) = ClientCredential(
        privateKeyPem = "-----BEGIN PRIVATE KEY-----\n$keyToken-AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA\n-----END PRIVATE KEY-----\n",
        clientCertPem = "-----BEGIN CERTIFICATE-----\r\n$certToken\r\n-----END CERTIFICATE-----\r\n",
        caChainPem = listOf(
            "-----BEGIN CERTIFICATE-----\r\n$ca1Token\r\n-----END CERTIFICATE-----\r\n",
            "-----BEGIN CERTIFICATE-----\r\n$ca2Token\r\n-----END CERTIFICATE-----\r\n",
        ),
    )

    private fun lfCredential(
        keyToken: String = "key-1",
        certToken: String = "cert-1",
        caToken: String = "ca-1",
    ) = ClientCredential(
        privateKeyPem = "-----BEGIN PRIVATE KEY-----\n$keyToken\n-----END PRIVATE KEY-----\n",
        clientCertPem = "-----BEGIN CERTIFICATE-----\n$certToken\n-----END CERTIFICATE-----\n",
        caChainPem = listOf("-----BEGIN CERTIFICATE-----\n$caToken\n-----END CERTIFICATE-----\n"),
    )

    private inner class Fixture {
        val dir = temp.newFolder()
        val identity = File(dir, "identity.tsv")
        val credentials = File(dir, "credential.pem")
        val direct = File(dir, "endpoint.txt")
        val marker = File(dir, "pairing.commit")
        val protector = SpySecretProtector()

        fun graph() = FilePairingGraph(identity, credentials, direct, marker, protector)
        fun assertNoBackups() = assertFalse(dir.listFiles().orEmpty().any { it.name.endsWith(".bak") })
    }

    @Test
    fun freshInstallMixedCredentialAppliesAndSurvivesRestart() {
        val f = Fixture()
        val graph = f.graph()
        val home = testHome(id = "home-1", certToken = "cert-1", caToken = "ca-1")
        val credential = mixedCredential(keyToken = "key-1", certToken = "cert-1", ca1Token = "ca-1a", ca2Token = "ca-1b")
        val endpoint = DirectEndpoint("10.0.0.1", 7657)

        val result = graph.installOrReplace(home, credential, endpoint, isDirectAssociated = true)
        assertIs<GraphMutationResult.Applied>(result)

        f.assertNoBackups()

        val restarted = f.graph()
        val snap = restarted.currentSnapshot()
        assertIs<PairingGraphSnapshot.Committed>(snap)
        assertEquals(home, snap.home)

        val directLease = restarted.acquireDirectLease()
        assertNotNull(directLease)
        assertEquals(credential, directLease.credential)
        assertEquals(endpoint, directLease.endpoint)
    }

    @Test
    fun replaceMixedCredentialAppliesAndSurvivesRestart() {
        val f = Fixture()
        val graph = f.graph()
        val home1 = testHome(id = "home-1", certToken = "cert-1", caToken = "ca-1")
        val cred1 = mixedCredential(keyToken = "key-1", certToken = "cert-1", ca1Token = "ca-1a", ca2Token = "ca-1b")
        val ep1 = DirectEndpoint("10.0.0.1", 7657)

        val res1 = graph.installOrReplace(home1, cred1, ep1, isDirectAssociated = true)
        assertIs<GraphMutationResult.Applied>(res1)

        val home2 = testHome(id = "home-2", label = "Home 2", certToken = "cert-2", caToken = "ca-2")
        val cred2 = mixedCredential(keyToken = "key-2", certToken = "cert-2", ca1Token = "ca-2a", ca2Token = "ca-2b")
        val ep2 = DirectEndpoint("10.0.0.2", 7657)

        val res2 = graph.installOrReplace(home2, cred2, ep2, isDirectAssociated = true)
        assertIs<GraphMutationResult.Applied>(res2)

        f.assertNoBackups()

        val restarted = f.graph()
        val snap = restarted.currentSnapshot()
        assertIs<PairingGraphSnapshot.Committed>(snap)
        assertEquals(home2, snap.home)

        val directLease = restarted.acquireDirectLease()
        assertNotNull(directLease)
        assertEquals(cred2, directLease.credential)
        assertEquals(ep2, directLease.endpoint)
    }

    @Test
    fun freshInstallRollbackOnReadBackMismatchRestoresAbsent() {
        val f = Fixture()
        val graph = f.graph()
        val home = testHome(id = "home-1", certToken = "cert-1", caToken = "ca-1")
        val credential = lfCredential(keyToken = "key-1", certToken = "cert-1", caToken = "ca-1")
        val endpoint = DirectEndpoint("10.0.0.1", 7657)

        var mutated = false
        f.protector.unprotectTransform = { bytes ->
            val text = bytes.decodeToString()
            if (!text.contains("-----BEGIN ")) {
                bytes
            } else if (!mutated) {
                mutated = true
                text.replace("cert-1", "cert-1x").toByteArray(Charsets.UTF_8)
            } else {
                bytes
            }
        }

        val result = graph.installOrReplace(home, credential, endpoint, isDirectAssociated = true)
        assertIs<GraphMutationResult.PersistenceFailed>(result)

        f.protector.unprotectTransform = { it }

        val restarted = f.graph()
        assertIs<PairingGraphSnapshot.Absent>(restarted.currentSnapshot())
        assertNull(restarted.acquireDirectLease())
    }

    @Test
    fun replaceRollbackOnReadBackMismatchRestoresPriorCommitted() {
        val f = Fixture()
        val graph = f.graph()
        val home1 = testHome(id = "home-1", certToken = "cert-1", caToken = "ca-1")
        val cred1 = lfCredential(keyToken = "key-1", certToken = "cert-1", caToken = "ca-1")
        val ep1 = DirectEndpoint("10.0.0.1", 7657)

        val res1 = graph.installOrReplace(home1, cred1, ep1, isDirectAssociated = true)
        assertIs<GraphMutationResult.Applied>(res1)

        val home2 = testHome(id = "home-2", label = "Home 2", certToken = "cert-2", caToken = "ca-2")
        val cred2 = lfCredential(keyToken = "key-2", certToken = "cert-2", caToken = "ca-2")
        val ep2 = DirectEndpoint("10.0.0.2", 7657)

        var mutated = false
        f.protector.unprotectTransform = { bytes ->
            val text = bytes.decodeToString()
            if (!text.contains("-----BEGIN ")) {
                bytes
            } else if (!mutated) {
                mutated = true
                text.replace("cert-2", "cert-2x").toByteArray(Charsets.UTF_8)
            } else {
                bytes
            }
        }

        val res2 = graph.installOrReplace(home2, cred2, ep2, isDirectAssociated = true)
        assertIs<GraphMutationResult.PersistenceFailed>(res2)

        f.protector.unprotectTransform = { it }

        val restarted = f.graph()
        val snap = restarted.currentSnapshot()
        assertIs<PairingGraphSnapshot.Committed>(snap)
        assertEquals(home1, snap.home)

        val directLease = restarted.acquireDirectLease()
        assertNotNull(directLease)
        assertEquals(cred1, directLease.credential)
        assertEquals(ep1, directLease.endpoint)
    }
}
