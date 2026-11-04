// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.identity.file

import app.solstone.core.identity.ClientCredential
import app.solstone.core.identity.GraphMutationResult
import app.solstone.core.identity.PairingGeneration
import app.solstone.core.identity.PairingGraphSnapshot
import app.solstone.core.identity.PairingProvenance
import app.solstone.core.model.DirectEndpoint
import app.solstone.core.model.IdentityState
import app.solstone.core.model.PairedHome
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.rules.TemporaryFolder

/** A pairing replaced before the owner answered comes back whole, in this process or the next. */
class FilePairingGraphPriorTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val protector = object : SecretProtector {
        override fun protect(plaintext: ByteArray) = plaintext
        override fun unprotect(wrapped: ByteArray) = wrapped
    }

    private fun graph() = FilePairingGraph(
        identityFile = File(temp.root, "identity.tsv"),
        credentialFile = File(temp.root, "credential.pem"),
        endpointFile = File(temp.root, "endpoint.txt"),
        commitMarkerFile = File(temp.root, "pairing.commit"),
        protector = protector,
    )

    private fun storedCredential() = FileClientCredentialStore(File(temp.root, "credential.pem"), protector).load()

    private fun home(id: String) = PairedHome(
        id, id, "https://link.solstone.app", "sha256:ca-$id", "sha256:cert-$id", null, "token-$id", null, IdentityState.PAIRED,
    )

    private fun credential(id: String) = ClientCredential(
        "-----BEGIN PRIVATE KEY-----\nkey-$id\n-----END PRIVATE KEY-----\n",
        "-----BEGIN CERTIFICATE-----\ncert-$id\n-----END CERTIFICATE-----\n",
        listOf("-----BEGIN CERTIFICATE-----\nca-$id\n-----END CERTIFICATE-----\n"),
    )

    private fun generation(id: String) = PairingGeneration(id, "sha256:cert-$id")

    private fun FilePairingGraph.pair(id: String, endpoints: List<DirectEndpoint> = emptyList(), associated: Boolean = false) {
        assertIs<GraphMutationResult.Applied>(
            installOrReplace(
                home = home(id),
                credential = credential(id),
                directEndpoint = endpoints.firstOrNull(),
                isDirectAssociated = associated,
                provenance = PairingProvenance.FRESH_LINK,
                directEndpoints = endpoints,
            ),
        )
    }

    private fun FilePairingGraph.replaceWith(id: String) {
        holdPriorFor(generation(id))
        pair(id)
    }

    private fun FilePairingGraph.committed() = assertIs<PairingGraphSnapshot.Committed>(currentSnapshot())

    // Falsified by a no that loses the credential: the owner's journal, only away, would have them
    // back at not paired.
    @Test
    fun restoringPutsBackTheCredentialTheAddressesAndWhatTheyWere() {
        val graph = graph()
        val saved = listOf(DirectEndpoint("10.0.0.1", 7657), DirectEndpoint("10.0.0.2", 7657))
        graph.pair("a", saved, associated = true)
        val before = graph.committed()

        graph.replaceWith("b")
        assertEquals(credential("b"), storedCredential())
        assertTrue(graph.canRestorePrior(generation("b")))

        assertIs<GraphMutationResult.Applied>(graph.restorePrior(generation("b")))

        assertEquals(credential("a"), storedCredential())
        val after = graph.committed()
        assertEquals(before.home, after.home)
        assertEquals(before.directEndpoints, after.directEndpoints)
        assertEquals(before.directAssociated, after.directAssociated)
        assertEquals(before.provenance, after.provenance)
        assertEquals(before.relayLiveEligible, after.relayLiveEligible)
        assertFalse(graph.canRestorePrior(generation("a")))
    }

    // Falsified by keeping the prior pairing in memory: a process death mid-question would strand the
    // owner on the journal they are about to refuse.
    @Test
    fun aProcessThatDiedMidQuestionCanStillPutItBack() {
        graph().apply {
            pair("a")
            replaceWith("b")
        }

        val relaunched = graph()
        assertEquals(generation("b"), relaunched.committed().pairing)
        assertIs<GraphMutationResult.Applied>(relaunched.restorePrior(generation("b")))

        assertEquals(credential("a"), storedCredential())
        assertEquals(generation("a"), graph().committed().pairing)
    }

    // Falsified by a replacement revived as working: a journal that stopped accepting this phone comes
    // back as refused.
    @Test
    fun aRevokedPairingComesBackRevoked() {
        val graph = graph()
        graph.pair("a")
        assertIs<GraphMutationResult.Applied>(graph.revokePairing(generation("a")))
        graph.replaceWith("b")

        assertIs<GraphMutationResult.Applied>(graph.restorePrior(generation("b")))

        assertEquals(IdentityState.REVOKED, graph.committed().home.state)
        assertEquals(IdentityState.REVOKED, graph().committed().home.state)
        assertEquals(credential("a"), storedCredential())
    }

    // An unanswered replacement was never the owner's. What comes back is what they last had.
    @Test
    fun replacingAnUnansweredReplacementKeepsTheSamePriorPairing() {
        val graph = graph()
        graph.pair("a")
        graph.replaceWith("b")
        graph.replaceWith("c")

        assertIs<GraphMutationResult.Applied>(graph.restorePrior(generation("c")))

        assertEquals(generation("a"), graph.committed().pairing)
        assertEquals(credential("a"), storedCredential())
    }

    @Test
    fun answeringOrForgettingEndsIt() {
        val answered = graph()
        answered.pair("a")
        answered.replaceWith("b")
        answered.discardPrior(generation("b"))
        assertFalse(answered.canRestorePrior(generation("b")))
        assertIs<GraphMutationResult.Conflict>(answered.restorePrior(generation("b")))
        assertEquals(credential("b"), storedCredential())

        answered.replaceWith("c")
        assertIs<GraphMutationResult.Cleared>(answered.forget())
        assertFalse(temp.root.list()!!.any { it.endsWith(".prior") })
    }

    // The attempt kept the prior pairing aside and then failed before installing. Falsified by that
    // record being honoured, which would swap a working pairing for itself or for an older one.
    @Test
    fun aKeptPairingWhoseInstallNeverLandedIsIgnoredAndDropped() {
        val graph = graph()
        graph.pair("a")
        graph.holdPriorFor(generation("b"))

        assertFalse(graph.canRestorePrior(generation("a")))
        assertIs<GraphMutationResult.Conflict>(graph.restorePrior(generation("b")))
        assertEquals(generation("a"), graph.committed().pairing)

        graph()
        assertFalse(temp.root.list()!!.any { it.endsWith(".prior") })
    }

    @Test
    fun aPairingAgainstAnotherCommittedPairingRestoresNothing() {
        val graph = graph()
        graph.pair("a")
        graph.replaceWith("b")
        assertIs<GraphMutationResult.Conflict>(graph.restorePrior(generation("a")))
        assertEquals(generation("b"), graph.committed().pairing)
    }

    // Falsified by treating a passing keystore failure as no prior pairing: the no would fall through
    // to forgetting, and delete the owner's working pairing for good.
    @Test
    fun aKeptPairingThatCantBeReadRightNowFailsAndStaysForAnotherTry() {
        val graph = graph()
        graph.pair("a")
        graph.replaceWith("b")
        val kept = File(temp.root, "credential.pem.prior")
        val bytes = kept.readBytes()
        kept.writeText("not a credential")

        assertTrue(graph.canRestorePrior(generation("b")))
        assertIs<GraphMutationResult.PersistenceFailed>(graph.restorePrior(generation("b")))
        assertEquals(generation("b"), graph.committed().pairing)

        kept.writeBytes(bytes)
        assertIs<GraphMutationResult.Applied>(graph.restorePrior(generation("b")))
        assertEquals(credential("a"), storedCredential())
    }
}
