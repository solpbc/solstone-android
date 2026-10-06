// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.identity.file

import app.solstone.core.identity.ClientCredential
import app.solstone.core.identity.DurableTxnStep
import app.solstone.core.identity.GraphMutationResult
import app.solstone.core.identity.JournalConfirmation
import app.solstone.core.identity.PairingGraphSnapshot
import app.solstone.core.identity.PairingProvenance
import app.solstone.core.identity.StoreInspectResult
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

    private fun testCred(key: String = "key-1", cert: String = "cert-1") = ClientCredential(
        privateKeyPem = "-----BEGIN PRIVATE KEY-----\n$key\n-----END PRIVATE KEY-----\n",
        clientCertPem = "-----BEGIN CERTIFICATE-----\n$cert\n-----END CERTIFICATE-----\n",
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
    fun freshDirectAndRelayProvenanceSurvivesCommitBeforeCallbackProcessDeath() {
        for (route in listOf("direct", "relay")) {
            val dir = File(temp.root, route).apply { mkdirs() }
            val identFile = File(dir, "identity.tsv")
            val credFile = File(dir, "credential.pem")
            val epFile = File(dir, "endpoint.txt")
            val commitFile = File(dir, "pairing.commit")
            val protector = SpySecretProtector()
            val graph = FilePairingGraph(
                identityFile = identFile,
                credentialFile = credFile,
                endpointFile = epFile,
                commitMarkerFile = commitFile,
                protector = protector,
                stepHook = { step, _ ->
                    if (step == DurableTxnStep.DURABLE_COMMIT_DECISION) error("process died before callback")
                },
            )
            val direct = route == "direct"
            val result = graph.installOrReplace(
                home = testHome(),
                credential = testCred(),
                directEndpoint = if (direct) DirectEndpoint("10.0.0.1", 7657) else null,
                isDirectAssociated = direct,
                provenance = PairingProvenance.FRESH_LINK,
            )
            assertTrue(result is GraphMutationResult.Applied)
            val recovered = FilePairingGraph(
                identityFile = identFile,
                credentialFile = credFile,
                endpointFile = epFile,
                commitMarkerFile = commitFile,
                protector = protector,
            ).currentSnapshot() as PairingGraphSnapshot.Committed
            assertEquals(PairingProvenance.FRESH_LINK, recovered.provenance)
            assertEquals(direct, recovered.isDirectEligible)
            assertTrue(recovered.isRelayEligible)
        }
    }

    @Test
    fun missingProvenanceMetadataAdoptsSilentlyAsLegacyAndCorruptMetadataIsUncertain() {
        val identFile = File(temp.root, "identity.tsv")
        val credFile = File(temp.root, "credential.pem")
        val epFile = File(temp.root, "endpoint.txt")
        val commitFile = File(temp.root, "pairing.commit")
        val protector = SpySecretProtector()
        val graph = FilePairingGraph(identFile, credFile, epFile, commitFile, protector)
        graph.installOrReplace(
            home = testHome(),
            credential = testCred(),
            directEndpoint = DirectEndpoint("10.0.0.1", 7657),
            isDirectAssociated = true,
            provenance = PairingProvenance.FRESH_LINK,
        )
        commitFile.writeText(commitFile.readText().replace("pairingProvenance\tFRESH_LINK\n", ""))
        val legacy = FilePairingGraph(identFile, credFile, epFile, commitFile, protector).currentSnapshot()
        assertEquals(PairingProvenance.UNKNOWN_LEGACY, (legacy as PairingGraphSnapshot.Committed).provenance)

        commitFile.writeText(commitFile.readText().replace("status\tCOMMITTED", "status\tCOMMITTED\npairingProvenance\tNOT_A_PROVENANCE"))
        val corrupt = FilePairingGraph(identFile, credFile, epFile, commitFile, protector).currentSnapshot()
        assertTrue(corrupt is PairingGraphSnapshot.Uncertain)
    }

    @Test
    fun sameGenerationRouteUpdatePreservesFreshProvenanceAndReplacementRollbackRestoresPriorProvenance() {
        val identFile = File(temp.root, "identity.tsv")
        val credFile = File(temp.root, "credential.pem")
        val epFile = File(temp.root, "endpoint.txt")
        val commitFile = File(temp.root, "pairing.commit")
        val protector = SpySecretProtector()
        val graph = FilePairingGraph(identFile, credFile, epFile, commitFile, protector)
        graph.installOrReplace(
            home = testHome(),
            credential = testCred(),
            directEndpoint = DirectEndpoint("10.0.0.1", 7657),
            isDirectAssociated = true,
            provenance = PairingProvenance.FRESH_LINK,
        )
        val generation = (graph.currentSnapshot() as PairingGraphSnapshot.Committed).pairing
        assertTrue(graph.updateRelayAccess(generation, "https://relay.example.invalid", "token-updated", "2030-02-01T00:00:00Z") is GraphMutationResult.Applied)
        assertEquals(PairingProvenance.FRESH_LINK, (graph.currentSnapshot() as PairingGraphSnapshot.Committed).provenance)

        val crashGraph = FilePairingGraph(
            identFile,
            credFile,
            epFile,
            commitFile,
            protector,
            stepHook = { step, op ->
                if (op == "REPLACE" && step == DurableTxnStep.RENAME_REPLACE) error("crash before commit marker")
            },
        )
        val replacement = crashGraph.installOrReplace(
            home = testHome(id = "home-2", cert = "sha256:cert-2"),
            credential = testCred("key-2", "cert-2"),
            directEndpoint = null,
            isDirectAssociated = false,
            provenance = PairingProvenance.FRESH_LINK,
        )
        assertTrue(replacement is GraphMutationResult.PersistenceFailed)
        val restored = FilePairingGraph(identFile, credFile, epFile, commitFile, protector).currentSnapshot()
        assertEquals(generation, (restored as PairingGraphSnapshot.Committed).pairing)
        assertEquals(PairingProvenance.FRESH_LINK, restored.provenance)
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

    @Test
    fun repairAcrossDeathMatrixStepsLeavesConfirmationUntouchedAndRollbackOrCommitConsistent() {
        val identFile = File(temp.root, "identity.tsv")
        val credFile = File(temp.root, "credential.pem")
        val epFile = File(temp.root, "endpoint.txt")
        val commitFile = File(temp.root, "pairing.commit")
        val confirmFile = File(temp.root, "journal_confirmation.json")
        val protector = SpySecretProtector()

        for (crashStep in DurableTxnStep.values()) {
            val dir = temp.newFolder()
            val identFile = File(dir, "identity.tsv")
            val credFile = File(dir, "credential.pem")
            val epFile = File(dir, "endpoint.txt")
            val commitFile = File(dir, "pairing.commit")
            val confirmFile = File(dir, "journal_confirmation.json")

            val confirmStore = FileJournalConfirmationStore(confirmFile)
            val initialGraph = FilePairingGraph(identFile, credFile, epFile, commitFile, protector)
            initialGraph.installOrReplace(
                home = testHome(id = "home-A", cert = "sha256:cert-a"),
                credential = testCred("key-A", "cert-a"),
                directEndpoint = DirectEndpoint("10.0.0.1", 7657),
                isDirectAssociated = true,
            )
            confirmStore.confirm("sha256:cert-a")
            val confirmationBytesBefore = confirmFile.readBytes()

            var crashed = false
            val graph = FilePairingGraph(
                identityFile = identFile,
                credentialFile = credFile,
                endpointFile = epFile,
                commitMarkerFile = commitFile,
                protector = protector,
                stepHook = { step, _ ->
                    assertEquals(confirmationBytesBefore.toList(), confirmFile.readBytes().toList())
                    if (step == crashStep && !crashed) {
                        crashed = true
                        error("simulated crash at $step")
                    }
                },
            )

            val result = runCatching {
                graph.installOrReplace(
                    home = testHome(id = "home-B", cert = "sha256:cert-b"),
                    credential = testCred("key-B", "cert-b"),
                    directEndpoint = DirectEndpoint("10.0.0.2", 7657),
                    isDirectAssociated = true,
                )
            }

            // Confirmation file bytes were never touched
            assertEquals(confirmationBytesBefore.toList(), confirmFile.readBytes().toList())

            val recovered = FilePairingGraph(identFile, credFile, epFile, commitFile, protector)
            val snap = recovered.currentSnapshot()
            val inspect = confirmStore.inspect() as StoreInspectResult.Ready

            if (crashStep.ordinal < DurableTxnStep.DURABLE_COMMIT_DECISION.ordinal) {
                // Rolled back to A
                assertTrue(snap is PairingGraphSnapshot.Committed)
                assertEquals("home-A", snap.home.instanceId)
                assertEquals("sha256:cert-a", snap.home.clientCertFingerprint)
                // A remains confirmed
                assertEquals(snap.home.clientCertFingerprint, inspect.value.confirmed)
            } else {
                // Committed to B
                assertTrue(snap is PairingGraphSnapshot.Committed)
                assertEquals("home-B", snap.home.instanceId)
                assertEquals("sha256:cert-b", snap.home.clientCertFingerprint)
                // B is unconfirmed because confirmation file still holds A
                assertEquals("sha256:cert-a", inspect.value.confirmed)
                kotlin.test.assertNotEquals(snap.home.clientCertFingerprint, inspect.value.confirmed)
            }
        }
    }

    @Test
    fun graphWritersLeaveConfirmationFileUntouched() {
        val identFile = File(temp.root, "identity.tsv")
        val credFile = File(temp.root, "credential.pem")
        val epFile = File(temp.root, "endpoint.txt")
        val commitFile = File(temp.root, "pairing.commit")
        val confirmFile = File(temp.root, "journal_confirmation.json")
        val protector = SpySecretProtector()

        val confirmStore = FileJournalConfirmationStore(confirmFile)
        val graph = FilePairingGraph(identFile, credFile, epFile, commitFile, protector)
        graph.installOrReplace(
            home = testHome(id = "home-1", cert = "sha256:cert-1"),
            credential = testCred("key-1"),
            directEndpoint = DirectEndpoint("10.0.0.1", 7657),
            isDirectAssociated = true,
        )
        confirmStore.confirm("sha256:cert-1")
        val initialBytes = confirmFile.readBytes()

        val pairingGen = (graph.currentSnapshot() as PairingGraphSnapshot.Committed).pairing

        // updateRelayAccess
        graph.updateRelayAccess(pairingGen, "https://relay.solstone.app", "token-2", "2030-01-01T00:00:00Z")
        assertEquals(initialBytes.toList(), confirmFile.readBytes().toList())

        // associateDirectIfProven
        graph.associateDirectIfProven(pairingGen, DirectEndpoint("10.0.0.2", 7657)) { true }
        assertEquals(initialBytes.toList(), confirmFile.readBytes().toList())

        // revokeRelayAccess
        graph.revokeRelayAccess(pairingGen)
        assertEquals(initialBytes.toList(), confirmFile.readBytes().toList())

        // forget
        graph.forget()
        assertEquals(initialBytes.toList(), confirmFile.readBytes().toList())
    }

    @Test
    fun forgetRollbackKeepsPairingAndConfirmationIntact() {
        val identFile = File(temp.root, "identity.tsv")
        val credFile = File(temp.root, "credential.pem")
        val epFile = File(temp.root, "endpoint.txt")
        val commitFile = File(temp.root, "pairing.commit")
        val confirmFile = File(temp.root, "journal_confirmation.json")
        val protector = SpySecretProtector()

        val confirmStore = FileJournalConfirmationStore(confirmFile)
        var crash = false
        val graph = FilePairingGraph(
            identityFile = identFile,
            credentialFile = credFile,
            endpointFile = epFile,
            commitMarkerFile = commitFile,
            protector = protector,
            stepHook = { step, _ ->
                if (step == DurableTxnStep.STAGING_WRITE && crash) {
                    error("simulated crash during forget staging")
                }
            },
        )
        graph.installOrReplace(
            home = testHome(id = "home-1", cert = "sha256:cert-1"),
            credential = testCred("key-1"),
            directEndpoint = DirectEndpoint("10.0.0.1", 7657),
            isDirectAssociated = true,
        )
        confirmStore.confirm("sha256:cert-1")
        val bytesBefore = confirmFile.readBytes()

        crash = true
        val result = graph.forget()
        assertTrue(result is GraphMutationResult.PersistenceFailed)
        assertEquals(bytesBefore.toList(), confirmFile.readBytes().toList())

        val recovered = FilePairingGraph(identFile, credFile, epFile, commitFile, protector)
        val snap = recovered.currentSnapshot() as PairingGraphSnapshot.Committed
        assertEquals("home-1", snap.home.instanceId)
        val inspect = confirmStore.inspect() as StoreInspectResult.Ready
        assertEquals("sha256:cert-1", inspect.value.confirmed)
    }

    @Test
    fun readBackFailureIdentityCorruptedRollsBackToPriorPairingAndConfirmationIntact() {
        val identFile = File(temp.root, "identity.tsv")
        val credFile = File(temp.root, "credential.pem")
        val epFile = File(temp.root, "endpoint.txt")
        val commitFile = File(temp.root, "pairing.commit")
        val confirmFile = File(temp.root, "journal_confirmation.json")
        val protector = SpySecretProtector()

        val confirmStore = FileJournalConfirmationStore(confirmFile)
        var corruptOnRename = false
        val graph = FilePairingGraph(
            identityFile = identFile,
            credentialFile = credFile,
            endpointFile = epFile,
            commitMarkerFile = commitFile,
            protector = protector,
            stepHook = { step, _ ->
                if (step == DurableTxnStep.RENAME_REPLACE && corruptOnRename) {
                    identFile.writeBytes(byteArrayOf(0, 1, 2, 3))
                }
            },
        )
        graph.installOrReplace(
            home = testHome(id = "home-A", cert = "sha256:cert-a"),
            credential = testCred("key-A", "cert-a"),
            directEndpoint = DirectEndpoint("10.0.0.1", 7657),
            isDirectAssociated = true,
        )
        confirmStore.confirm("sha256:cert-a")
        val bytesBefore = confirmFile.readBytes()

        corruptOnRename = true
        val result = graph.installOrReplace(
            home = testHome(id = "home-B", cert = "sha256:cert-b"),
            credential = testCred("key-B", "cert-b"),
            directEndpoint = DirectEndpoint("10.0.0.2", 7657),
            isDirectAssociated = true,
        )
        assertTrue(result is GraphMutationResult.PersistenceFailed)
        assertEquals(bytesBefore.toList(), confirmFile.readBytes().toList())

        val recovered = FilePairingGraph(identFile, credFile, epFile, commitFile, protector)
        val snap = recovered.currentSnapshot() as PairingGraphSnapshot.Committed
        assertEquals("home-A", snap.home.instanceId)
        assertEquals("sha256:cert-a", snap.home.clientCertFingerprint)
        val inspect = confirmStore.inspect() as StoreInspectResult.Ready
        assertEquals("sha256:cert-a", inspect.value.confirmed)
    }

    @Test
    fun legacyAdoptLeavesConfirmationFileBytesUnchanged() {
        val root = temp.newFolder()
        val identFile = File(root, "identity.tsv")
        val credFile = File(root, "credential.pem")
        val epFile = File(root, "endpoint.txt")
        val commitFile = File(root, "pairing.commit")
        val confirmFile = File(root, "journal_confirmation.json")
        val protector = SpySecretProtector()

        // Create initial graph to stage valid files without commit marker
        val tempGraph = FilePairingGraph(identFile, credFile, epFile, commitFile, protector)
        tempGraph.installOrReplace(
            home = testHome(id = "home-1", cert = "sha256:cert-1"),
            credential = testCred("key-1"),
            directEndpoint = DirectEndpoint("10.0.0.1", 7657),
            isDirectAssociated = true,
        )
        // Remove commit file to simulate legacy state
        commitFile.delete()
        assertFalse(commitFile.exists())

        val confirmStore = FileJournalConfirmationStore(confirmFile)
        confirmStore.confirm("sha256:cert-1")
        val bytesBefore = confirmFile.readBytes()

        // Construct graph which will adopt legacy files
        val graph = FilePairingGraph(identFile, credFile, epFile, commitFile, protector)
        assertTrue(commitFile.exists())
        assertEquals(bytesBefore.toList(), confirmFile.readBytes().toList())

        val snap = graph.currentSnapshot() as PairingGraphSnapshot.Committed
        assertEquals("home-1", snap.home.instanceId)
        assertEquals("sha256:cert-1", snap.home.clientCertFingerprint)
    }

    @Test
    fun forgetUnconfirmedRollbackLeavesPairingAndUnconfirmedStateIntact() {
        val root = temp.newFolder()
        val identFile = File(root, "identity.tsv")
        val credFile = File(root, "credential.pem")
        val epFile = File(root, "endpoint.txt")
        val commitFile = File(root, "pairing.commit")
        val confirmFile = File(root, "journal_confirmation.json")
        val protector = SpySecretProtector()

        val confirmStore = FileJournalConfirmationStore(confirmFile)
        var crash = false
        val graph = FilePairingGraph(
            identityFile = identFile,
            credentialFile = credFile,
            endpointFile = epFile,
            commitMarkerFile = commitFile,
            protector = protector,
            stepHook = { step, _ ->
                if (step == DurableTxnStep.STAGING_WRITE && crash) {
                    error("crash during forget staging")
                }
            },
        )
        graph.installOrReplace(
            home = testHome(id = "home-1", cert = "sha256:cert-1"),
            credential = testCred("key-1"),
            directEndpoint = DirectEndpoint("10.0.0.1", 7657),
            isDirectAssociated = true,
        )
        // Confirmation file holds unconfirmed cert
        confirmStore.confirm("sha256:unconfirmed-cert")
        val bytesBefore = confirmFile.readBytes()

        crash = true
        val result = graph.forget()
        assertTrue(result is GraphMutationResult.PersistenceFailed)
        assertEquals(bytesBefore.toList(), confirmFile.readBytes().toList())

        val recovered = FilePairingGraph(identFile, credFile, epFile, commitFile, protector)
        val snap = recovered.currentSnapshot() as PairingGraphSnapshot.Committed
        assertEquals("home-1", snap.home.instanceId)
        assertEquals("sha256:cert-1", snap.home.clientCertFingerprint)
        val inspect = confirmStore.inspect() as StoreInspectResult.Ready
        assertEquals("sha256:unconfirmed-cert", inspect.value.confirmed)
        kotlin.test.assertNotEquals(snap.home.clientCertFingerprint, inspect.value.confirmed)
    }
}
