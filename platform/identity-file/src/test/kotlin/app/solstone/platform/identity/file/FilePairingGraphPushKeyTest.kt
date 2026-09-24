// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.identity.file

import app.solstone.core.identity.AtomicFileWriter
import app.solstone.core.identity.ClientCredential
import app.solstone.core.identity.GraphMutationResult
import app.solstone.core.identity.ObtainResult
import app.solstone.core.identity.PairingGeneration
import app.solstone.core.identity.PairingGraphSnapshot
import app.solstone.core.model.DirectEndpoint
import app.solstone.core.model.IdentityState
import app.solstone.core.model.PairedHome
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FilePairingGraphPushKeyTest {
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
    fun obtainPushKeyGeneratesPersistsAndReturnsConsistentBytes() {
        val identFile = File(temp.root, "identity.tsv")
        val credFile = File(temp.root, "credential.pem")
        val epFile = File(temp.root, "endpoint.txt")
        val commitFile = File(temp.root, "pairing.commit")
        val pushKeyFile = File(temp.root, "push-key.bin")
        val protector = SpySecretProtector()
        val pushProtector = SpySecretProtector()

        val graph = FilePairingGraph(
            identityFile = identFile,
            credentialFile = credFile,
            endpointFile = epFile,
            commitMarkerFile = commitFile,
            protector = protector,
            pushKeyFile = pushKeyFile,
            pushKeyProtector = pushProtector,
        )

        val installResult = graph.installOrReplace(
            home = testHome(),
            credential = testCred(),
            directEndpoint = DirectEndpoint("10.0.0.1", 7657),
            isDirectAssociated = true,
        )
        assertIs<GraphMutationResult.Applied>(installResult)
        val snap = graph.currentSnapshot()
        assertIs<PairingGraphSnapshot.Committed>(snap)
        val generation = snap.pairing

        assertFalse(pushKeyFile.exists(), "Push key file must not exist before first obtain")
        assertNull(graph.readPushKey(generation), "readPushKey must return null before obtain")

        val result1 = graph.obtainPushKey(generation)
        assertIs<ObtainResult.Obtained>(result1)
        assertEquals(32, result1.bytes.size)
        assertTrue(pushKeyFile.exists(), "Push key file must exist after obtain")

        val readBytes = graph.readPushKey(generation)
        assertNotNull(readBytes)
        assertContentEquals(result1.bytes, readBytes)

        val result2 = graph.obtainPushKey(generation)
        assertIs<ObtainResult.Obtained>(result2)
        assertContentEquals(result1.bytes, result2.bytes, "Second obtain must return identical bytes")

        // Fresh graph on same files
        val freshGraph = FilePairingGraph(
            identityFile = identFile,
            credentialFile = credFile,
            endpointFile = epFile,
            commitMarkerFile = commitFile,
            protector = protector,
            pushKeyFile = pushKeyFile,
            pushKeyProtector = pushProtector,
        )
        val freshRead = freshGraph.readPushKey(generation)
        assertNotNull(freshRead)
        assertContentEquals(result1.bytes, freshRead)
    }

    @Test
    fun generationMismatchRefusesOrReturnsNull() {
        val identFile = File(temp.root, "identity.tsv")
        val credFile = File(temp.root, "credential.pem")
        val epFile = File(temp.root, "endpoint.txt")
        val commitFile = File(temp.root, "pairing.commit")
        val pushKeyFile = File(temp.root, "push-key.bin")
        val protector = SpySecretProtector()
        val pushProtector = SpySecretProtector()

        val graph = FilePairingGraph(
            identityFile = identFile,
            credentialFile = credFile,
            endpointFile = epFile,
            commitMarkerFile = commitFile,
            protector = protector,
            pushKeyFile = pushKeyFile,
            pushKeyProtector = pushProtector,
        )

        val foreignGen = PairingGeneration("foreign-home", "sha256:foreign-cert")

        // Absent snapshot
        assertEquals(ObtainResult.Refused, graph.obtainPushKey(foreignGen))
        assertNull(graph.readPushKey(foreignGen))

        // Paired snapshot
        graph.installOrReplace(
            home = testHome("home-1"),
            credential = testCred(),
            directEndpoint = null,
            isDirectAssociated = false,
        )
        val home1Gen = (graph.currentSnapshot() as PairingGraphSnapshot.Committed).pairing

        val obtain1 = graph.obtainPushKey(home1Gen)
        assertIs<ObtainResult.Obtained>(obtain1)

        // Querying with foreign generation
        assertEquals(ObtainResult.Refused, graph.obtainPushKey(foreignGen))
        assertNull(graph.readPushKey(foreignGen))

        // Re-pair to home-2
        graph.installOrReplace(
            home = testHome("home-2", cert = "sha256:cert-2"),
            credential = testCred("key-2"),
            directEndpoint = null,
            isDirectAssociated = false,
        )
        val home2Gen = (graph.currentSnapshot() as PairingGraphSnapshot.Committed).pairing

        // Old generation read/obtain on home-2 snapshot
        assertEquals(ObtainResult.Refused, graph.obtainPushKey(home1Gen))
        assertNull(graph.readPushKey(home1Gen))

        // Disk still has home-1 push key until home-2 obtains
        assertNull(graph.readPushKey(home2Gen), "readPushKey must return null when disk holds previous generation")

        // Obtain on home-2 generates new key
        val obtain2 = graph.obtainPushKey(home2Gen)
        assertIs<ObtainResult.Obtained>(obtain2)
        assertFalse(obtain1.bytes.contentEquals(obtain2.bytes), "New pairing generation must have new push key")
        assertContentEquals(obtain2.bytes, graph.readPushKey(home2Gen))
    }

    @Test
    fun protectorErrorsAreHandledGracefully() {
        val identFile = File(temp.root, "identity.tsv")
        val credFile = File(temp.root, "credential.pem")
        val epFile = File(temp.root, "endpoint.txt")
        val commitFile = File(temp.root, "pairing.commit")
        val pushKeyFile = File(temp.root, "push-key.bin")
        val protector = SpySecretProtector()
        val pushProtector = SpySecretProtector()

        val graph = FilePairingGraph(
            identityFile = identFile,
            credentialFile = credFile,
            endpointFile = epFile,
            commitMarkerFile = commitFile,
            protector = protector,
            pushKeyFile = pushKeyFile,
            pushKeyProtector = pushProtector,
        )

        graph.installOrReplace(
            home = testHome(),
            credential = testCred(),
            directEndpoint = null,
            isDirectAssociated = false,
        )
        val generation = (graph.currentSnapshot() as PairingGraphSnapshot.Committed).pairing

        // Failure during protect
        pushProtector.failProtect = true
        val failedObtain = graph.obtainPushKey(generation)
        assertIs<ObtainResult.Failed>(failedObtain)
        assertFalse(pushKeyFile.exists())

        // Successful protect
        pushProtector.failProtect = false
        val obtained = graph.obtainPushKey(generation)
        assertIs<ObtainResult.Obtained>(obtained)

        // Failure during unprotect on readPushKey returns null instead of throwing
        pushProtector.failUnprotect = true
        assertNull(graph.readPushKey(generation))

        // When unprotect fails during obtainPushKey, obtainPushKey falls back to minting
        pushProtector.failUnprotect = false
        val reObtain = graph.obtainPushKey(generation)
        assertIs<ObtainResult.Obtained>(reObtain)
        assertContentEquals(obtained.bytes, reObtain.bytes)
    }

    @Test
    fun atomicFileWriterErrorReturnsFailed() {
        val identFile = File(temp.root, "identity.tsv")
        val credFile = File(temp.root, "credential.pem")
        val epFile = File(temp.root, "endpoint.txt")
        val commitFile = File(temp.root, "pairing.commit")
        val pushKeyFile = File(temp.root, "push-key.bin")
        val protector = SpySecretProtector()
        val pushProtector = SpySecretProtector()

        var failWriter = false
        val writer = AtomicFileWriter { target, bytes ->
            if (failWriter && target == pushKeyFile) {
                throw IOException("disk full on push key write")
            }
            AtomicFileWriter.Default.write(target, bytes)
        }

        val graph = FilePairingGraph(
            identityFile = identFile,
            credentialFile = credFile,
            endpointFile = epFile,
            commitMarkerFile = commitFile,
            protector = protector,
            fileWriter = writer,
            pushKeyFile = pushKeyFile,
            pushKeyProtector = pushProtector,
        )

        graph.installOrReplace(
            home = testHome(),
            credential = testCred(),
            directEndpoint = null,
            isDirectAssociated = false,
        )
        val generation = (graph.currentSnapshot() as PairingGraphSnapshot.Committed).pairing

        failWriter = true
        val result = graph.obtainPushKey(generation)
        assertIs<ObtainResult.Failed>(result)
        assertIs<IOException>(result.cause)
    }

    @Test
    fun nullConstructorParamsValidation() {
        val identFile = File(temp.root, "identity.tsv")
        val credFile = File(temp.root, "credential.pem")
        val epFile = File(temp.root, "endpoint.txt")
        val commitFile = File(temp.root, "pairing.commit")
        val pushKeyFile = File(temp.root, "push-key.bin")
        val protector = SpySecretProtector()
        val pushProtector = SpySecretProtector()

        assertFailsWith<IllegalArgumentException> {
            FilePairingGraph(
                identityFile = identFile,
                credentialFile = credFile,
                endpointFile = epFile,
                commitMarkerFile = commitFile,
                protector = protector,
                pushKeyFile = pushKeyFile,
                pushKeyProtector = null,
            )
        }

        assertFailsWith<IllegalArgumentException> {
            FilePairingGraph(
                identityFile = identFile,
                credentialFile = credFile,
                endpointFile = epFile,
                commitMarkerFile = commitFile,
                protector = protector,
                pushKeyFile = null,
                pushKeyProtector = pushProtector,
            )
        }

        // Both null
        val noPushGraph = FilePairingGraph(
            identityFile = identFile,
            credentialFile = credFile,
            endpointFile = epFile,
            commitMarkerFile = commitFile,
            protector = protector,
            pushKeyFile = null,
            pushKeyProtector = null,
        )
        noPushGraph.installOrReplace(
            home = testHome(),
            credential = testCred(),
            directEndpoint = null,
            isDirectAssociated = false,
        )
        val gen = (noPushGraph.currentSnapshot() as PairingGraphSnapshot.Committed).pairing
        assertNull(noPushGraph.readPushKey(gen))
        val result = noPushGraph.obtainPushKey(gen)
        assertIs<ObtainResult.Failed>(result)
        assertIs<IllegalStateException>(result.cause)
    }

    @Test
    fun forgetDeletesPushKeyFile() {
        val identFile = File(temp.root, "identity.tsv")
        val credFile = File(temp.root, "credential.pem")
        val epFile = File(temp.root, "endpoint.txt")
        val commitFile = File(temp.root, "pairing.commit")
        val pushKeyFile = File(temp.root, "push-key.bin")
        val protector = SpySecretProtector()
        val pushProtector = SpySecretProtector()

        val graph = FilePairingGraph(
            identityFile = identFile,
            credentialFile = credFile,
            endpointFile = epFile,
            commitMarkerFile = commitFile,
            protector = protector,
            pushKeyFile = pushKeyFile,
            pushKeyProtector = pushProtector,
        )

        graph.installOrReplace(
            home = testHome(),
            credential = testCred(),
            directEndpoint = null,
            isDirectAssociated = false,
        )
        val gen = (graph.currentSnapshot() as PairingGraphSnapshot.Committed).pairing
        val obtain = graph.obtainPushKey(gen)
        assertIs<ObtainResult.Obtained>(obtain)
        assertTrue(pushKeyFile.exists())

        val forgetResult = graph.forget()
        assertIs<GraphMutationResult.Cleared>(forgetResult)
        assertFalse(pushKeyFile.exists(), "Push key file must be deleted on forget")
        assertNull(graph.readPushKey(gen))
    }
}
