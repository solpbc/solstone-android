// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.identity.file

import app.solstone.core.identity.AtomicFileWriter
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
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class FilePairingGraphRelayAccessFailureTest {
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
    fun identitySaveThrowsRestoresPriorCommittedPairing() {
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

        val installResult = graph.installOrReplace(
            home = testHome(),
            credential = testCred(),
            directEndpoint = DirectEndpoint("10.0.0.1", 7657),
            isDirectAssociated = true,
        )
        assertIs<GraphMutationResult.Applied>(installResult)

        val directLease = graph.acquireDirectLease()
        assertNotNull(directLease)

        val preIdentityBytes = identFile.readBytes()
        val preMarkerBytes = commitFile.readBytes()
        val preSnapshot = graph.currentSnapshot()
        assertIs<PairingGraphSnapshot.Committed>(preSnapshot)

        protector.failProtect = true

        val result = graph.updateRelayAccess(
            expectedPairing = preSnapshot.pairing,
            relayOrigin = preSnapshot.home.relayOrigin!!,
            deviceToken = "token-2",
            expiresAt = "2031-01-01T00:00:00Z",
        )

        assertIs<GraphMutationResult.PersistenceFailed>(result)

        assertTrue(identFile.exists(), "identity file must exist")
        assertTrue(credFile.exists(), "credential file must exist")
        assertTrue(epFile.exists(), "endpoint file must exist")

        assertContentEquals(preIdentityBytes, identFile.readBytes(), "identity bytes must match pre-call bytes")
        assertContentEquals(preMarkerBytes, commitFile.readBytes(), "marker bytes must match pre-call bytes")

        val currentSnapshot = graph.currentSnapshot()
        assertIs<PairingGraphSnapshot.Committed>(currentSnapshot)
        assertEquals(preSnapshot.home, currentSnapshot.home)
        assertEquals("token-1", currentSnapshot.home.deviceToken)
        assertEquals(preSnapshot.revisions, currentSnapshot.revisions)

        assertTrue(graph.validateLease(directLease), "pre-call direct lease must remain valid")

        protector.failProtect = false
        val freshGraph = FilePairingGraph(
            identityFile = identFile,
            credentialFile = credFile,
            endpointFile = epFile,
            commitMarkerFile = commitFile,
            protector = protector,
        )
        val freshSnapshot = freshGraph.currentSnapshot()
        assertIs<PairingGraphSnapshot.Committed>(freshSnapshot)
        assertEquals(preSnapshot.home, freshSnapshot.home)
        assertEquals(preSnapshot.pairing, freshSnapshot.pairing)
        assertTrue(freshSnapshot.directAssociated)
    }

    @Test
    fun readBackMismatchRestoresPriorCommittedPairing() {
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

        val installResult = graph.installOrReplace(
            home = testHome(),
            credential = testCred(),
            directEndpoint = DirectEndpoint("10.0.0.1", 7657),
            isDirectAssociated = true,
        )
        assertIs<GraphMutationResult.Applied>(installResult)

        val directLease = graph.acquireDirectLease()
        assertNotNull(directLease)

        val preIdentityBytes = identFile.readBytes()
        val preMarkerBytes = commitFile.readBytes()
        val preSnapshot = graph.currentSnapshot()
        assertIs<PairingGraphSnapshot.Committed>(preSnapshot)

        var corruptedOnce = false
        protector.unprotectTransform = { bytes ->
            if (!corruptedOnce) {
                corruptedOnce = true
                "not-valid-tsv".toByteArray(Charsets.UTF_8)
            } else {
                bytes
            }
        }

        val result = graph.updateRelayAccess(
            expectedPairing = preSnapshot.pairing,
            relayOrigin = preSnapshot.home.relayOrigin!!,
            deviceToken = "token-2",
            expiresAt = "2031-01-01T00:00:00Z",
        )

        assertIs<GraphMutationResult.PersistenceFailed>(result)

        assertTrue(identFile.exists(), "identity file must exist")
        assertTrue(credFile.exists(), "credential file must exist")
        assertTrue(epFile.exists(), "endpoint file must exist")

        assertContentEquals(preIdentityBytes, identFile.readBytes(), "identity bytes must match pre-call bytes")
        assertContentEquals(preMarkerBytes, commitFile.readBytes(), "marker bytes must match pre-call bytes")

        val currentSnapshot = graph.currentSnapshot()
        assertIs<PairingGraphSnapshot.Committed>(currentSnapshot)
        assertEquals(preSnapshot.home, currentSnapshot.home)
        assertEquals("token-1", currentSnapshot.home.deviceToken)
        assertEquals(preSnapshot.revisions, currentSnapshot.revisions)

        assertTrue(graph.validateLease(directLease), "pre-call direct lease must remain valid")

        val freshGraph = FilePairingGraph(
            identityFile = identFile,
            credentialFile = credFile,
            endpointFile = epFile,
            commitMarkerFile = commitFile,
            protector = protector,
        )
        val freshSnapshot = freshGraph.currentSnapshot()
        assertIs<PairingGraphSnapshot.Committed>(freshSnapshot)
        assertEquals(preSnapshot.home, freshSnapshot.home)
        assertEquals(preSnapshot.pairing, freshSnapshot.pairing)
        assertTrue(freshSnapshot.directAssociated)
    }

    @Test
    fun plantedIdentityBakRestoresPriorCommittedPairing() {
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

        val installResult = graph.installOrReplace(
            home = testHome(),
            credential = testCred(),
            directEndpoint = DirectEndpoint("10.0.0.1", 7657),
            isDirectAssociated = true,
        )
        assertIs<GraphMutationResult.Applied>(installResult)

        val directLease = graph.acquireDirectLease()
        assertNotNull(directLease)

        val preIdentityBytes = identFile.readBytes()
        val preMarkerBytes = commitFile.readBytes()
        val preSnapshot = graph.currentSnapshot()
        assertIs<PairingGraphSnapshot.Committed>(preSnapshot)

        // Plant an obsolete backup file before updateRelayAccess
        val bakFile = File(temp.root, "${identFile.name}.bak")
        bakFile.writeBytes("corrupted-planted-backup".toByteArray(Charsets.UTF_8))

        protector.failProtect = true

        val result = graph.updateRelayAccess(
            expectedPairing = preSnapshot.pairing,
            relayOrigin = preSnapshot.home.relayOrigin!!,
            deviceToken = "token-2",
            expiresAt = "2031-01-01T00:00:00Z",
        )

        assertIs<GraphMutationResult.PersistenceFailed>(result)

        assertTrue(identFile.exists(), "identity file must exist")
        assertContentEquals(preIdentityBytes, identFile.readBytes(), "identity bytes must match pre-call bytes")
        assertContentEquals(preMarkerBytes, commitFile.readBytes(), "marker bytes must match pre-call bytes")

        val currentSnapshot = graph.currentSnapshot()
        assertIs<PairingGraphSnapshot.Committed>(currentSnapshot)
        assertEquals(preSnapshot.home, currentSnapshot.home)
        assertEquals("token-1", currentSnapshot.home.deviceToken)
        assertTrue(graph.validateLease(directLease), "pre-call direct lease must remain valid")
    }

    @Test
    fun stepInjectionsBeforeMarkerDecisionRestorePriorCommitted() {
        val stepsToTest = listOf(
            DurableTxnStep.STAGING_WRITE,
            DurableTxnStep.RENAME_REPLACE,
            DurableTxnStep.READ_BACK,
            DurableTxnStep.DURABLE_COMMIT_DECISION,
        )

        for (targetStep in stepsToTest) {
            val subfolder = temp.newFolder()
            val identFile = File(subfolder, "identity.tsv")
            val credFile = File(subfolder, "credential.pem")
            val epFile = File(subfolder, "endpoint.txt")
            val commitFile = File(subfolder, "pairing.commit")
            val protector = SpySecretProtector()

            var failOnStep: DurableTxnStep? = null

            val graph = FilePairingGraph(
                identityFile = identFile,
                credentialFile = credFile,
                endpointFile = epFile,
                commitMarkerFile = commitFile,
                protector = protector,
                stepHook = { step, _ ->
                    if (step == failOnStep) {
                        throw RuntimeException("injected failure at $step")
                    }
                },
            )

            val installResult = graph.installOrReplace(
                home = testHome(),
                credential = testCred(),
                directEndpoint = DirectEndpoint("10.0.0.1", 7657),
                isDirectAssociated = true,
            )
            assertIs<GraphMutationResult.Applied>(installResult)

            val directLease = graph.acquireDirectLease()
            assertNotNull(directLease)

            val preIdentityBytes = identFile.readBytes()
            val preMarkerBytes = commitFile.readBytes()
            val preSnapshot = graph.currentSnapshot()
            assertIs<PairingGraphSnapshot.Committed>(preSnapshot)

            failOnStep = targetStep

            val result = graph.updateRelayAccess(
                expectedPairing = preSnapshot.pairing,
                relayOrigin = preSnapshot.home.relayOrigin!!,
                deviceToken = "token-2",
                expiresAt = "2031-01-01T00:00:00Z",
            )

            assertIs<GraphMutationResult.PersistenceFailed>(result, "Expected PersistenceFailed on $targetStep")
            assertTrue(identFile.exists(), "identity file must exist on $targetStep")
            assertContentEquals(preIdentityBytes, identFile.readBytes(), "identity bytes must match pre-call on $targetStep")
            assertContentEquals(preMarkerBytes, commitFile.readBytes(), "marker bytes must match pre-call on $targetStep")

            val currentSnapshot = graph.currentSnapshot()
            assertIs<PairingGraphSnapshot.Committed>(currentSnapshot)
            assertEquals("token-1", currentSnapshot.home.deviceToken)
            assertTrue(graph.validateLease(directLease), "lease must be valid on $targetStep")
        }
    }

    @Test
    fun stepInjectionAtCleanupAppliesCommittedPairing() {
        val identFile = File(temp.root, "identity.tsv")
        val credFile = File(temp.root, "credential.pem")
        val epFile = File(temp.root, "endpoint.txt")
        val commitFile = File(temp.root, "pairing.commit")
        val protector = SpySecretProtector()

        var failOnStep: DurableTxnStep? = null

        val graph = FilePairingGraph(
            identityFile = identFile,
            credentialFile = credFile,
            endpointFile = epFile,
            commitMarkerFile = commitFile,
            protector = protector,
            stepHook = { step, _ ->
                if (step == failOnStep) {
                    throw RuntimeException("injected failure at $step")
                }
            },
        )

        val installResult = graph.installOrReplace(
            home = testHome(),
            credential = testCred(),
            directEndpoint = DirectEndpoint("10.0.0.1", 7657),
            isDirectAssociated = true,
        )
        assertIs<GraphMutationResult.Applied>(installResult)

        val preSnapshot = graph.currentSnapshot()
        assertIs<PairingGraphSnapshot.Committed>(preSnapshot)

        failOnStep = DurableTxnStep.CLEANUP

        val result = graph.updateRelayAccess(
            expectedPairing = preSnapshot.pairing,
            relayOrigin = preSnapshot.home.relayOrigin!!,
            deviceToken = "token-2",
            expiresAt = "2031-01-01T00:00:00Z",
        )

        assertIs<GraphMutationResult.Applied>(result)
        val currentSnapshot = graph.currentSnapshot()
        assertIs<PairingGraphSnapshot.Committed>(currentSnapshot)
        assertEquals("token-2", currentSnapshot.home.deviceToken)
        assertEquals("token-2", result.snapshot.home.deviceToken)
    }

    @Test
    fun atomicFileWriterThrowingOnIdentitySaveRestoresPriorCommittedPairing() {
        val identFile = File(temp.root, "identity.tsv")
        val credFile = File(temp.root, "credential.pem")
        val epFile = File(temp.root, "endpoint.txt")
        val commitFile = File(temp.root, "pairing.commit")
        val protector = SpySecretProtector()

        var failWriter = false
        val writer = AtomicFileWriter { target, bytes ->
            if (failWriter && target == identFile) {
                throw IOException("disk full on identity write")
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
        )

        val installResult = graph.installOrReplace(
            home = testHome(),
            credential = testCred(),
            directEndpoint = DirectEndpoint("10.0.0.1", 7657),
            isDirectAssociated = true,
        )
        assertIs<GraphMutationResult.Applied>(installResult)

        val directLease = graph.acquireDirectLease()
        assertNotNull(directLease)

        val preIdentityBytes = identFile.readBytes()
        val preMarkerBytes = commitFile.readBytes()
        val preSnapshot = graph.currentSnapshot()
        assertIs<PairingGraphSnapshot.Committed>(preSnapshot)

        failWriter = true

        val result = graph.updateRelayAccess(
            expectedPairing = preSnapshot.pairing,
            relayOrigin = preSnapshot.home.relayOrigin!!,
            deviceToken = "token-2",
            expiresAt = "2031-01-01T00:00:00Z",
        )

        assertIs<GraphMutationResult.PersistenceFailed>(result)

        assertTrue(identFile.exists(), "identity file must exist")
        assertContentEquals(preIdentityBytes, identFile.readBytes(), "identity bytes must match pre-call bytes")
        assertContentEquals(preMarkerBytes, commitFile.readBytes(), "marker bytes must match pre-call bytes")

        val currentSnapshot = graph.currentSnapshot()
        assertIs<PairingGraphSnapshot.Committed>(currentSnapshot)
        assertEquals("token-1", currentSnapshot.home.deviceToken)
        assertTrue(graph.validateLease(directLease), "pre-call direct lease must remain valid")
    }
}
