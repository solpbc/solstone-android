// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.work

import app.solstone.core.crypto.generateP256KeyPair
import app.solstone.core.crypto.jidFromCaPem
import app.solstone.core.crypto.pem
import app.solstone.core.identity.ClientCredential
import app.solstone.core.identity.GraphMutationResult
import app.solstone.core.identity.GraphRevisions
import app.solstone.core.identity.JournalConfirmationPolicy
import app.solstone.core.identity.PairingGeneration
import app.solstone.core.identity.PairingGraphSnapshot
import app.solstone.core.identity.PairingLease
import app.solstone.core.identity.PairingPublisher
import app.solstone.core.identity.SubscriptionHandle
import app.solstone.core.identity.awaitingMarkConfirmation
import app.solstone.core.identity.confirmedFor
import app.solstone.core.model.DirectEndpoint
import app.solstone.core.model.IdentityState
import app.solstone.core.model.PairedHome
import app.solstone.platform.identity.file.FileJournalConfirmationStore
import app.solstone.testing.JournalLoopbackStandIn
import java.io.File
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.rules.TemporaryFolder

/** What a start does to a stored pairing, given its id and the CA stored with it. */
class JournalIdNotItsCaHoldTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val fingerprint = "sha256:client"
    private val realCaPem = pem(
        "CERTIFICATE",
        JournalLoopbackStandIn.selfSignedCertificate(generateP256KeyPair(), "CA", isCa = true).encoded,
    )
    private val otherJournalsId = "f30ed159-ef46-8e9c-913f-e49f0fe7d201"

    private class Publisher(val home: PairedHome?, val caPem: String?) : PairingPublisher {
        override fun currentSnapshot(): PairingGraphSnapshot = home?.let {
            PairingGraphSnapshot.Committed(
                sequenceNumber = 1L,
                revisions = GraphRevisions(1L, 1L, 1L),
                home = it,
                hasDirectEndpoint = false,
                directAssociated = false,
                relayLiveEligible = false,
            )
        } ?: PairingGraphSnapshot.Absent(1L)
        override fun pinnedCaPem(expected: PairingGeneration): String? = caPem
        override fun subscribe(observer: (PairingGraphSnapshot) -> Unit) = SubscriptionHandle {}
        override fun <T> withMutationBoundary(block: () -> T): T = block()
        override fun acquireDirectLease(): PairingLease.Direct? = null
        override fun acquireRelayLease(): PairingLease.Relay? = null
        override fun validateLease(lease: PairingLease): Boolean = true
        override fun installOrReplace(
            home: PairedHome,
            credential: ClientCredential,
            directEndpoint: DirectEndpoint?,
            isDirectAssociated: Boolean,
            provenance: app.solstone.core.identity.PairingProvenance,
            directEndpoints: List<DirectEndpoint>,
        ) = GraphMutationResult.Conflict("unused")
        override fun replaceDirectEndpoints(expected: PairingGraphSnapshot.Committed, endpoints: List<DirectEndpoint>) =
            GraphMutationResult.Conflict("unused")
        override fun updateRelayAccess(
            expectedPairing: PairingGeneration,
            relayOrigin: String,
            deviceToken: String,
            expiresAt: String?,
        ) = GraphMutationResult.Conflict("unused")
        override fun revokeRelayAccess(expectedPairing: PairingGeneration) = GraphMutationResult.Conflict("unused")
        override fun forget() = GraphMutationResult.Conflict("unused")
        override fun associateDirectIfProven(
            expectedPairing: PairingGeneration,
            endpoint: DirectEndpoint,
            proof: () -> Boolean,
        ) = false
    }

    private fun home(instanceId: String) = PairedHome(
        instanceId = instanceId,
        homeLabel = "Home",
        relayOrigin = null,
        caChainFingerprint = "sha256:ca",
        clientCertFingerprint = fingerprint,
        observerHandle = null,
        deviceToken = null,
        expiresAt = null,
        state = IdentityState.PAIRED,
    )

    private val store by lazy { FileJournalConfirmationStore(File(temp.root, "journal_confirmation.json")) }

    @BeforeTest
    fun setUp() {
        JournalConfirmationPolicy.consults = true
    }

    @AfterTest
    fun tearDown() {
        JournalConfirmationPolicy.consults = true
    }

    private fun confirmed() = confirmedFor(true, store, fingerprint)

    @Test
    fun aConfirmedPairingWhoseIdIsNotItsCasGoesBackToHeld() {
        val publisher = Publisher(home(otherJournalsId), realCaPem)
        store.confirm(fingerprint)

        holdPairingWhoseIdIsNotItsCas(publisher, store)

        assertFalse(confirmed())
        assertTrue(awaitingMarkConfirmation(true, publisher.currentSnapshot(), store))
    }

    // The start path: an install from before the ask has no answer on file, so it's counted as
    // answered first, and then the same check applies.
    @Test
    fun theStartPathHoldsItIncludingAPairingFromBeforeTheAskExisted() {
        JournalConfirmationGrandfather.resetForTest()
        val publisher = Publisher(home(otherJournalsId), realCaPem)

        JournalConfirmationGrandfather.grandfather(publisher, store)

        assertFalse(confirmed())
        assertTrue(awaitingMarkConfirmation(true, publisher.currentSnapshot(), store))
        JournalConfirmationGrandfather.resetForTest()
    }

    @Test
    fun theStartPathLeavesAMatchingPairingFromBeforeTheAskExistedAnswered() {
        JournalConfirmationGrandfather.resetForTest()

        JournalConfirmationGrandfather.grandfather(Publisher(home(jidFromCaPem(realCaPem)), realCaPem), store)

        assertTrue(confirmed())
        JournalConfirmationGrandfather.resetForTest()
    }

    @Test
    fun itIsAskedOnlyOnceAcrossStartsOnceAnswered() {
        val publisher = Publisher(home(otherJournalsId), realCaPem)
        store.confirm(fingerprint)
        holdPairingWhoseIdIsNotItsCas(publisher, store)

        store.confirm(fingerprint)
        holdPairingWhoseIdIsNotItsCas(publisher, store)

        assertTrue(confirmed())
        val reopened = FileJournalConfirmationStore(File(temp.root, "journal_confirmation.json"))
        holdPairingWhoseIdIsNotItsCas(publisher, reopened)
        assertTrue(confirmedFor(true, reopened, fingerprint))
    }

    @Test
    fun walkingAwayKeepsItHeldAcrossStarts() {
        val publisher = Publisher(home(otherJournalsId), realCaPem)
        store.confirm(fingerprint)
        holdPairingWhoseIdIsNotItsCas(publisher, store)

        holdPairingWhoseIdIsNotItsCas(publisher, store)
        store.settle()

        assertFalse(confirmed())
    }

    @Test
    fun aMatchingIdIsNotAskedAgain() {
        val publisher = Publisher(home(jidFromCaPem(realCaPem)), realCaPem)
        store.confirm(fingerprint)

        holdPairingWhoseIdIsNotItsCas(publisher, store)

        assertTrue(confirmed())
    }

    @Test
    fun aPairingWithNoCaToReadIsNotHeld() {
        for (caPem in listOf(null, "not a certificate")) {
            store.confirm(fingerprint)

            holdPairingWhoseIdIsNotItsCas(Publisher(home(otherJournalsId), caPem), store)

            assertTrue(confirmed(), "CA $caPem")
        }
    }

    // A forged pairing could have pinned a key that has no journal id at all.
    @Test
    fun aCaWhoseKeyHasNoJournalIdIsHeld() {
        val p384 = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp384r1")) }.generateKeyPair()
        val p384Ca = pem("CERTIFICATE", JournalLoopbackStandIn.selfSignedCertificate(p384, "CA", isCa = true).encoded)
        store.confirm(fingerprint)

        holdPairingWhoseIdIsNotItsCas(Publisher(home(otherJournalsId), p384Ca), store)

        assertFalse(confirmed())
    }

    @Test
    fun aPairingAlreadyHeldAndAPairingOptedOutAreLeftAlone() {
        val publisher = Publisher(home(otherJournalsId), realCaPem)
        store.settle()
        holdPairingWhoseIdIsNotItsCas(publisher, store)
        assertEquals(null, confirmedFingerprint())

        store.confirm(fingerprint)
        JournalConfirmationPolicy.consults = false
        holdPairingWhoseIdIsNotItsCas(publisher, store)
        assertEquals(fingerprint, confirmedFingerprint())
    }

    private fun confirmedFingerprint(): String? =
        (store.inspect() as? app.solstone.core.identity.StoreInspectResult.Ready)?.value?.confirmed
}
