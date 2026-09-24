// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.identity.file

import app.solstone.core.identity.AtomicFileWriter
import app.solstone.core.identity.ClientCredential
import app.solstone.core.identity.DurableTxnHook
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
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FilePairingGraphFailureInvariantTest {
    @get:Rule val temp = TemporaryFolder()

    private val home = PairedHome(
        instanceId = "home-1", homeLabel = "Home", relayOrigin = "https://link.solstone.app",
        caChainFingerprint = "sha256:ca-1", clientCertFingerprint = "sha256:cert-1",
        observerHandle = "obs", deviceToken = "token-1", expiresAt = null, state = IdentityState.PAIRED,
    )
    private val credential = ClientCredential(
        privateKeyPem = "-----BEGIN PRIVATE KEY-----\nkey-1\n-----END PRIVATE KEY-----\n",
        clientCertPem = "-----BEGIN CERTIFICATE-----\ncert-1\n-----END CERTIFICATE-----\n",
        caChainPem = listOf("-----BEGIN CERTIFICATE-----\nca-1\n-----END CERTIFICATE-----\n"),
    )
    private val endpoint = DirectEndpoint("10.0.0.1", 7657)

    private inner class Fixture {
        val dir = temp.newFolder()
        val identity = File(dir, "identity.tsv")
        val credentials = File(dir, "credential.pem")
        val direct = File(dir, "endpoint.txt")
        val marker = File(dir, "pairing.commit")
        val protector = SpySecretProtector()
        fun graph(writer: AtomicFileWriter = AtomicFileWriter.Default, hook: DurableTxnHook? = null) =
            FilePairingGraph(identity, credentials, direct, marker, protector, writer, hook)
        fun seed(directEndpoint: DirectEndpoint? = endpoint) {
            assertTrue(graph().installOrReplace(home, credential, directEndpoint, true) is GraphMutationResult.Applied)
        }
        fun durableBytes() = listOf(identity, credentials, direct, marker).map { file ->
            if (file.exists()) file.readBytes().toList() else null
        }
        fun assertNoBackups() = assertFalse(dir.listFiles().orEmpty().any { it.name.endsWith(".bak") })
    }

    // Revisions and sequence numbers belong to one process, not the durable state.
    private fun durableState(graph: FilePairingGraph): List<Any?> {
        val snapshot = graph.currentSnapshot()
        return when (snapshot) {
            is PairingGraphSnapshot.Absent -> listOf("absent")
            is PairingGraphSnapshot.Uncertain -> listOf("uncertain", snapshot.reason)
            is PairingGraphSnapshot.Committed -> listOf(
                "committed", snapshot.home, snapshot.hasDirectEndpoint, snapshot.directAssociated,
                snapshot.relayLiveEligible, graph.acquireDirectLease()?.endpoint, graph.acquireRelayLease()?.deviceToken,
                graph.acquireDirectLease()?.credential ?: graph.acquireRelayLease()?.credential,
            )
        }
    }

    private fun mutate(graph: FilePairingGraph, operation: String): Any {
        val pairing = (graph.currentSnapshot() as? PairingGraphSnapshot.Committed)?.pairing
        return when (operation) {
            "install", "replace" -> graph.installOrReplace(
                home.copy(instanceId = "home-2", homeLabel = "New home", clientCertFingerprint = "sha256:cert-2"),
                credential.copy(
                    privateKeyPem = credential.privateKeyPem.replace("key-1", "key-2"),
                    clientCertPem = credential.clientCertPem.replace("cert-1", "cert-2"),
                ),
                DirectEndpoint("10.0.0.2", 7657),
                true,
            )
            "update" -> graph.updateRelayAccess(requireNotNull(pairing), "https://link.solstone.app", "new-token", null)
            "revoke" -> graph.revokeRelayAccess(requireNotNull(pairing))
            "forget" -> graph.forget()
            "associate" -> graph.associateDirectIfProven(requireNotNull(pairing), DirectEndpoint("10.0.0.2", 7657)) { true }
            else -> error(operation)
        }
    }

    private fun assertWriteFailures(operation: String, seededEndpoint: DirectEndpoint? = endpoint) {
        val control = Fixture()
        if (operation != "install") control.seed(seededEndpoint)
        var writes = 0
        val successful = control.graph(AtomicFileWriter { target, bytes ->
            writes++
            AtomicFileWriter.Default.write(target, bytes)
        })
        writes = 0
        mutate(successful, operation)
        val mutationWrites = writes
        assertTrue(mutationWrites > 0)
        for (failAt in 1..mutationWrites) {
            val fixture = Fixture()
            if (operation != "install") fixture.seed(seededEndpoint)
            var armed = false
            var attempts = 0
            val graph = fixture.graph(AtomicFileWriter { target, bytes ->
                if (armed && ++attempts == failAt) throw IOException("injected write failure")
                AtomicFileWriter.Default.write(target, bytes)
            })
            val prior = durableState(graph)
            val priorBytes = fixture.durableBytes()
            armed = true
            val result = mutate(graph, operation)
            assertTrue(attempts >= failAt, "$operation did not reach write $failAt")
            val inMemory = durableState(graph)
            val recovered = fixture.graph()
            if (operation == "revoke") {
                // A terminal relay denial must stay fenced for this process even if disk is unwritable.
                assertFalse((graph.currentSnapshot() as PairingGraphSnapshot.Committed).isRelayEligible)
                assertEquals(null, graph.acquireRelayLease())
                assertEquals(prior, durableState(recovered), "revoke write $failAt: durable pairing changed")
                assertEquals(home, (graph.currentSnapshot() as PairingGraphSnapshot.Committed).home)
                assertTrue((graph.currentSnapshot() as PairingGraphSnapshot.Committed).isDirectEligible)
            } else {
                assertEquals(inMemory, durableState(recovered), "$operation write $failAt: memory differs from restart")
                assertEquals(prior, inMemory, "$operation write $failAt: failed save changed pairing")
            }
            assertEquals(priorBytes, fixture.durableBytes(), "$operation write $failAt: failed save changed files")
            assertTrue(result is GraphMutationResult.PersistenceFailed || result == false)
            fixture.assertNoBackups()
        }
    }

    @Test fun installWriteFailuresPreserveAbsent() = assertWriteFailures("install")
    @Test fun replaceWriteFailuresPreservePairing() = assertWriteFailures("replace")
    @Test fun relayUpdateWriteFailuresPreservePairing() = assertWriteFailures("update")
    @Test fun relayRevokeWriteFailuresPreservePairing() = assertWriteFailures("revoke")
    @Test fun forgetWriteFailuresPreservePairing() = assertWriteFailures("forget")
    @Test fun associationWriteFailuresPreserveExistingEndpoint() = assertWriteFailures("associate")
    @Test fun associationWriteFailuresPreserveMissingEndpoint() = assertWriteFailures("associate", null)

    @Test fun revocationReadbackFailureRestoresIdentity() = assertReadbackFailure("revoke", "identity.tsv")
    @Test fun associationReadbackFailureRestoresEndpoint() = assertReadbackFailure("associate", "endpoint.txt")

    @Test
    fun failedReadbackHookDoesNotRollBackTwice() {
        val fixture = Fixture()
        fixture.seed()
        val prior = fixture.durableBytes()
        var corrupt = true
        val graph = fixture.graph(
            writer = AtomicFileWriter { target, bytes ->
                if (target == fixture.identity && corrupt) {
                    corrupt = false
                    AtomicFileWriter.Default.write(target, "corrupted".toByteArray())
                } else AtomicFileWriter.Default.write(target, bytes)
            },
            hook = DurableTxnHook { step, _ ->
                if (step == DurableTxnStep.READ_BACK) throw IOException("interrupted readback")
            },
        )
        assertTrue(mutate(graph, "replace") is GraphMutationResult.PersistenceFailed)
        assertEquals(prior, fixture.durableBytes())
        assertEquals(durableState(graph), durableState(fixture.graph()))
    }

    private fun assertReadbackFailure(operation: String, filename: String) {
        val fixture = Fixture()
        fixture.seed()
        val prior = fixture.durableBytes()
        var corrupt = true
        val graph = fixture.graph(AtomicFileWriter { target, bytes ->
            if (target.name == filename && corrupt) {
                corrupt = false
                AtomicFileWriter.Default.write(target, "corrupted".toByteArray())
            } else AtomicFileWriter.Default.write(target, bytes)
        })
        val result = mutate(graph, operation)
        assertTrue(result is GraphMutationResult.PersistenceFailed || result == false)
        assertEquals(prior, fixture.durableBytes())
        assertTrue(fixture.graph().currentSnapshot() is PairingGraphSnapshot.Committed)
    }

    @Test
    fun failedRollbackReportsUncertainInsteadOfPaired() {
        for (operation in listOf("revoke", "associate")) {
            val fixture = Fixture()
            fixture.seed()
            var failedMarker = false
            val graph = fixture.graph(AtomicFileWriter { target, bytes ->
                if (target == fixture.marker) {
                    failedMarker = true
                    throw IOException("marker unavailable")
                }
                if (failedMarker) throw IOException("rollback unavailable")
                AtomicFileWriter.Default.write(target, bytes)
            })
            mutate(graph, operation)
            assertTrue(graph.currentSnapshot() is PairingGraphSnapshot.Uncertain, operation)
            assertEquals(durableState(graph), durableState(fixture.graph()), operation)
        }
    }

    @Test
    fun recoveryCanRetryAfterRestoreWriteFails() {
        val fixture = Fixture()
        fixture.seed()
        val before = durableState(fixture.graph())
        val crashing = fixture.graph(hook = DurableTxnHook { step, _ ->
            if (step == DurableTxnStep.RENAME_REPLACE) throw ProcessDeath()
        })
        assertFailsWith<ProcessDeath> { mutate(crashing, "replace") }
        val unavailable = fixture.graph(AtomicFileWriter { _, _ -> throw IOException("disk unavailable") })
        assertTrue(unavailable.currentSnapshot() is PairingGraphSnapshot.Uncertain)
        assertTrue(File(fixture.dir, "identity.tsv.bak").exists())
        assertEquals(before, durableState(fixture.graph()))
    }

    @Test
    fun interruptedMutationAfterFailedRevokeKeepsValidDurablePairing() {
        for (operation in listOf("replace", "forget")) {
            val fixture = Fixture()
            fixture.seed()
            val before = durableState(fixture.graph())
            var failBackup = true
            val graph = fixture.graph(
                writer = AtomicFileWriter { target, bytes ->
                    if (target.name.endsWith(".bak") && failBackup) {
                        failBackup = false
                        throw IOException("backup unavailable")
                    }
                    AtomicFileWriter.Default.write(target, bytes)
                },
                hook = DurableTxnHook { step, _ -> if (step == DurableTxnStep.STAGING_WRITE) throw ProcessDeath() },
            )
            assertTrue(mutate(graph, "revoke") is GraphMutationResult.PersistenceFailed)
            assertFalse((graph.currentSnapshot() as PairingGraphSnapshot.Committed).isRelayEligible)
            assertFailsWith<ProcessDeath> { mutate(graph, operation) }
            assertEquals(before, durableState(fixture.graph()), operation)
        }
    }

    @Test
    fun failedInstallFromUncertainReflectsTheRolledBackDisk() {
        val fixture = Fixture()
        fixture.seed()
        fixture.identity.writeText("damaged")
        val graph = fixture.graph(hook = DurableTxnHook { step, _ ->
            if (step == DurableTxnStep.STAGING_WRITE) throw IOException("interrupted install")
        })
        assertTrue(graph.currentSnapshot() is PairingGraphSnapshot.Uncertain)
        assertTrue(mutate(graph, "install") is GraphMutationResult.PersistenceFailed)
        assertEquals(durableState(graph), durableState(fixture.graph()))
        assertTrue(graph.currentSnapshot() is PairingGraphSnapshot.Absent)
    }

    @Test
    fun failedInFlightRollbackRetainsRecoveryInsteadOfThrowing() {
        for (operation in listOf("replace", "forget")) {
            val fixture = Fixture()
            fixture.seed()
            val before = durableState(fixture.graph())
            var unavailable = false
            val graph = fixture.graph(
                writer = AtomicFileWriter { target, bytes ->
                    if (unavailable) throw IOException("disk unavailable")
                    AtomicFileWriter.Default.write(target, bytes)
                },
                hook = DurableTxnHook { step, _ ->
                    if (step == DurableTxnStep.STAGING_WRITE) {
                        unavailable = true
                        throw IOException("interrupted mutation")
                    }
                },
            )
            assertTrue(mutate(graph, operation) is GraphMutationResult.DurabilityUncertain)
            assertTrue(graph.currentSnapshot() is PairingGraphSnapshot.Uncertain)
            assertTrue(File(fixture.dir, "identity.tsv.bak").exists())
            assertEquals(before, durableState(fixture.graph()))
        }
    }

    // Error deliberately bypasses catch(Exception), leaving exactly the crash-time disk image.
    private class ProcessDeath : Error()

    @Test fun restartRecoversEveryInstallStep() = assertRestartSteps("install")
    @Test fun restartRecoversEveryReplaceStep() = assertRestartSteps("replace")
    @Test fun restartRecoversEveryForgetStep() = assertRestartSteps("forget")

    private fun assertRestartSteps(operation: String) {
        val visited = mutableSetOf<DurableTxnStep>()
        val control = Fixture()
        if (operation != "install") control.seed()
        mutate(control.graph(hook = DurableTxnHook { step, _ -> visited.add(step) }), operation)
        assertTrue(visited.isNotEmpty())
        for (step in visited) {
            val fixture = Fixture()
            if (operation != "install") fixture.seed()
            val before = durableState(fixture.graph())
            val crashing = fixture.graph(hook = DurableTxnHook { at, _ -> if (at == step) throw ProcessDeath() })
            assertFailsWith<ProcessDeath>("$operation at $step") { mutate(crashing, operation) }
            val recovered = fixture.graph()
            val committed = step == DurableTxnStep.DURABLE_COMMIT_DECISION || step == DurableTxnStep.CLEANUP ||
                (operation == "forget" && step == DurableTxnStep.RENAME_REPLACE)
            assertEquals(if (committed) durableState(control.graph()) else before, durableState(recovered), "$operation at $step")
            fixture.assertNoBackups()
        }
    }
}
