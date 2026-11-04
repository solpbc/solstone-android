// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.harness

import app.solstone.core.identity.ClientCredential
import app.solstone.core.identity.GraphMutationResult
import app.solstone.core.identity.PairingGeneration
import app.solstone.core.identity.PairingGraphSnapshot
import app.solstone.core.model.DirectEndpoint
import app.solstone.core.model.IdentityState
import app.solstone.core.model.PairedHome
import app.solstone.platform.identity.file.FileClientCredentialStore
import app.solstone.platform.identity.file.FilePairingGraph
import app.solstone.platform.identity.file.SecretProtector
import java.io.File
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** A pairing attempt that doesn't finish leaves the phone with the pairing it started with. */
class ReplacementRestoreTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val protector = object : SecretProtector {
        override fun protect(plaintext: ByteArray) = plaintext
        override fun unprotect(wrapped: ByteArray) = wrapped
    }

    private val graph by lazy {
        FilePairingGraph(
            identityFile = File(temp.root, "identity.tsv"),
            credentialFile = File(temp.root, "credential.pem"),
            endpointFile = File(temp.root, "endpoint.txt"),
            commitMarkerFile = File(temp.root, "pairing.commit"),
            protector = protector,
        ).also { assertIs<GraphMutationResult.Applied>(it.install("a")) }
    }

    private fun home(id: String) =
        PairedHome(id, id, null, "sha256:ca", "sha256:cert-$id", null, null, null, IdentityState.PAIRED)

    private fun credential(id: String) = ClientCredential(
        "-----BEGIN PRIVATE KEY-----\nkey-$id\n-----END PRIVATE KEY-----\n",
        "-----BEGIN CERTIFICATE-----\ncert-$id\n-----END CERTIFICATE-----\n",
        listOf("-----BEGIN CERTIFICATE-----\nca-$id\n-----END CERTIFICATE-----\n"),
    )

    private fun FilePairingGraph.install(id: String) =
        installOrReplace(home(id), credential(id), DirectEndpoint("10.0.0.2", 7657), true)

    private fun storedCredential() = FileClientCredentialStore(File(temp.root, "credential.pem"), protector).load()

    private fun committed() = assertIs<PairingGraphSnapshot.Committed>(graph.currentSnapshot()).pairing

    private val a = PairingGeneration("a", "sha256:cert-a")
    private val b = PairingGeneration("b", "sha256:cert-b")

    private fun replacing(after: () -> HarnessPairProbeResult) = fixture(
        publisher = graph,
        plStatusProbe = PlStatusProbe {
            HarnessPlStatus.PairedButUnreachable("x", "10.0.0.2:7657", UnknownJournalAnswer("10.0.0.2:7657"))
        },
        pairProbe = PairProbe { _, _ ->
            graph.holdPriorFor(b)
            assertIs<GraphMutationResult.Applied>(graph.install("b"))
            after()
        },
    )

    private fun probeResult(status: Int) = HarnessPairProbeResult(true, 200, status, "", "b", "10.0.0.2", 7657)

    // Falsified by a status probe that failed after the install leaving the new credential in place:
    // the phone would be left paired to a journal nobody confirmed, with the prior credential gone.
    @Test
    fun aProbeThatThrewAfterTheInstallPutsThePriorPairingBack() {
        val f = replacing { throw IOException("no route to host") }

        f.controller.dispatchPairLink(validPairLink())

        assertEquals(a, committed())
        assertEquals(credential("a"), storedCredential())
    }

    @Test
    fun aProbeAnsweredBadlyAfterTheInstallPutsThePriorPairingBack() {
        val f = replacing { probeResult(503) }

        f.controller.dispatchPairLink(validPairLink())

        assertEquals(a, committed())
        assertEquals(credential("a"), storedCredential())
    }

    @Test
    fun theUnclassifiedEntryPointPutsItBackToo() {
        val f = replacing { throw IOException("no route to host") }

        assertFailsWith<IOException> { f.controller.onScannedPairLink(validPairLink()) }

        assertEquals(a, committed())
        assertEquals(credential("a"), storedCredential())
    }

    // The prior pairing waits for the owner's answer, not just for the probe.
    @Test
    fun aGoodProbeKeepsTheNewPairingAndThePriorOneAside() {
        val f = replacing { probeResult(200) }

        f.controller.dispatchPairLink(validPairLink())

        assertEquals(b, committed())
        assertEquals(credential("b"), storedCredential())
        assertTrue(graph.canRestorePrior(b))
    }

    // Falsified by putting back a pairing the failed attempt never replaced.
    @Test
    fun anAttemptThatFailedBeforeInstallingChangesNothing() {
        val f = fixture(
            publisher = graph,
            plStatusProbe = PlStatusProbe {
                HarnessPlStatus.PairedButUnreachable("x", "10.0.0.2:7657", UnknownJournalAnswer("10.0.0.2:7657"))
            },
            pairProbe = PairProbe { _, _ -> throw IOException("no route to host") },
        )

        f.controller.dispatchPairLink(validPairLink())

        assertEquals(a, committed())
        assertEquals(credential("a"), storedCredential())
        assertFalse(graph.canRestorePrior(a))
    }
}
