// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.pl.transport.conscrypt

import app.solstone.core.identity.ClientCredential
import app.solstone.core.identity.ClientCredentialStore
import app.solstone.core.identity.IdentityStore
import app.solstone.core.identity.JournalMark
import app.solstone.core.identity.JournalMarkIcon
import app.solstone.core.identity.JournalMarkPresentation
import app.solstone.core.identity.JournalMarkRecord
import app.solstone.core.identity.JournalMarkStore
import app.solstone.core.identity.PairingGraphSnapshot
import app.solstone.core.model.PairedHome
import app.solstone.core.pl.DirectEndpoint
import app.solstone.core.pl.EndpointStore
import app.solstone.core.pl.JournalIdentityRefreshCoordinator
import app.solstone.core.pl.journalMarkFromCaPem
import app.solstone.core.pl.journalMarkFromJid
import app.solstone.core.pl.toJson
import app.solstone.testing.JournalLoopbackStandIn
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A pair link pins the key it names, so a forged link pins the forger's. These run the real direct
 * path: a TLS 1.3 peer on loopback, a link pinned to that peer's own CA, and the public entry point.
 */
class DirectPairIdentityBindingTest {
    private val peers = mutableListOf<JournalLoopbackStandIn>()

    private val credentials = object : ClientCredentialStore {
        var value: ClientCredential? = null
        override fun save(credential: ClientCredential) { value = credential }
        override fun load(): ClientCredential? = value
        override fun clear() { value = null }
    }
    private val identity = object : IdentityStore {
        var value: PairedHome? = null
        override fun save(home: PairedHome) { value = home }
        override fun load(): PairedHome? = value
        override fun clear() { value = null }
    }
    private val endpoints = object : EndpointStore {
        var value: DirectEndpoint? = null
        override fun save(endpoint: DirectEndpoint) { value = endpoint }
        override fun load(): DirectEndpoint? = value
        override fun clear() { value = null }
    }
    private val markStore = object : JournalMarkStore {
        var value: JournalMarkRecord? = null
        override fun load(): JournalMarkRecord? = value
        override fun save(record: JournalMarkRecord) { value = record }
        override fun clear() { value = null }
    }
    private val publisher = FakePairingPublisher(identity, credentials, endpoints)

    @AfterTest
    fun tearDown() {
        peers.forEach { it.close() }
    }

    private fun peer() = JournalLoopbackStandIn().also { peers += it }

    private fun pair(link: String) = pairAndProbe(
        pairLink = link,
        deviceLabel = "test phone",
        credentialStore = credentials,
        identityStore = identity,
        endpointStore = endpoints,
        publisher = publisher,
        confirmation = FakeJournalConfirmationStore(),
    )

    @Test
    fun aPeerWithAValidPinClaimingAnotherJournalsIdAndServingItsMarkIsRefused() {
        val owners = peer()
        val forger = peer()
        // Everything a forger can get without the owner's key: the owner's id, from any door
        // handshake, and a copy of the owner's real mark to serve.
        forger.claimedInstanceId = owners.instanceId
        forger.identityResponseBody = servedIdentity(owners.instanceId, journalMarkFromJid(owners.instanceId))

        assertFailsWith<SSLException> { pair(forger.pairLink()) }

        // Nothing stored.
        assertIs<PairingGraphSnapshot.Absent>(publisher.currentSnapshot())
        assertNull(credentials.value)
        assertNull(identity.value)
        assertNull(endpoints.value)
        // Nothing sent past the pair request itself, and so no ask.
        assertEquals(listOf("/app/network/pair"), forger.recordedRequests.map { it.path.substringBefore('?') })
    }

    // The owner's CA certificate is public and really does sign itself, so a link can pin its hash
    // and a forger can present and name it. All the forger can't do is hold its key: only the
    // signature on the forger's own leaf gives that away, and only the handshake checks it.
    @Test
    fun aPeerPresentingACopyOfTheOwnersCaUnderItsPinIsRefusedAtTheHandshake() {
        val owners = peer()
        val forger = JournalLoopbackStandIn(impersonatedCa = owners.caCert).also { peers += it }
        forger.claimedInstanceId = owners.instanceId

        assertFailsWith<DirectPairNotVerifiedException> { pair(forger.pairLink()) }

        assertIs<PairingGraphSnapshot.Absent>(publisher.currentSnapshot())
        assertNull(credentials.value)
        assertEquals(emptyList(), forger.recordedRequests.map { it.path })
    }

    @Test
    fun aGenuinePeerPairsAndItsMarkIsComputedFromThePinnedCaNotServed() {
        val journal = peer()
        val coordinator = JournalIdentityRefreshCoordinator(store = markStore, publisher = publisher)
        val computed = CountDownLatch(1)
        val remove = coordinator.addListener { if (it is JournalMarkPresentation.Identified) computed.countDown() }
        // A served spec that disagrees, to show the route isn't what the mark comes from.
        journal.identityResponseBody = servedIdentity(journal.instanceId, servedMark)

        val result = pair(journal.pairLink())
        coordinator.onMarkRequested()

        assertEquals(200, result.pairStatus)
        assertEquals(journal.instanceId, identity.value?.instanceId)
        assertTrue(computed.await(30, TimeUnit.SECONDS), "presented ${coordinator.currentPresentation()}")
        assertEquals(
            JournalMarkPresentation.Identified(journalMarkFromCaPem(journal.caPem)),
            coordinator.currentPresentation(),
        )
        assertEquals(journalMarkFromJid(journal.instanceId), markStore.value?.mark)
        assertTrue(journal.recordedRequests.none { it.path.startsWith("/app/network/api/identity") })
        remove()
        coordinator.close()
    }

    private val servedMark = JournalMark(
        icon1 = JournalMarkIcon("piano", "<path d=\"M0 0\"/>", "blue", "#3b82f6", 45),
        icon2 = JournalMarkIcon("key", "<path d=\"M0 0\"/>", "purple", "#a855f7", 0),
        words = listOf("served", "mark"),
    )

    private fun servedIdentity(instanceId: String, mark: JournalMark): String = toJson(
        mapOf(
            "committed" to true,
            "instance_id" to instanceId,
            "mark" to mapOf(
                "icon1" to icon(mark.icon1),
                "icon2" to icon(mark.icon2),
                "words" to mark.words,
            ),
        ),
    )

    private fun icon(icon: JournalMarkIcon) = mapOf(
        "name" to icon.name,
        "svg" to icon.svg,
        "color" to mapOf("name" to icon.colorName, "hex" to icon.colorHex),
        "rot" to icon.rot,
    )
}
