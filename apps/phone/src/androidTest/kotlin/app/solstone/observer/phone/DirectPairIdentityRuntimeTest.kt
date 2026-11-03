// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.solstone.core.identity.JournalMarkPresentation
import app.solstone.core.identity.PairingGraphSnapshot
import app.solstone.core.pl.journalMarkFromCaPem
import app.solstone.platform.pl.transport.conscrypt.pairAndProbe
import app.solstone.platform.work.syncStores
import app.solstone.testing.JournalLoopbackStandIn
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The direct pairing's identity check on the app's own TLS stack and stores, with a loopback peer. */
@RunWith(AndroidJUnit4::class)
class DirectPairIdentityRuntimeTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val peers = mutableListOf<JournalLoopbackStandIn>()

    @Before
    fun setUp() {
        resetObserverRuntime()
        resetPersistence(context)
    }

    @After
    fun tearDown() {
        peers.forEach { it.close() }
        resetObserverRuntime()
        runCatching { syncStores(context).publisher.forget() }
    }

    private fun peer() = JournalLoopbackStandIn().also { peers += it }

    private fun pair(link: String) = syncStores(context).let { stores ->
        pairAndProbe(
            pairLink = link,
            deviceLabel = "test phone",
            credentialStore = stores.credentialStore,
            identityStore = stores.identityStore,
            endpointStore = stores.endpointStore,
            journalMarkStore = stores.journalMarkStore,
            journalIdentityCoordinator = stores.journalIdentityCoordinator,
            publisher = stores.publisher,
            confirmation = stores.journalConfirmationStore,
        )
    }

    @Test
    fun aPeerClaimingAnotherJournalsIdIsRefusedAndNothingIsKept() {
        val owners = peer()
        val forger = peer()
        forger.claimedInstanceId = owners.instanceId

        assertThrows(SSLException::class.java) { pair(forger.pairLink()) }

        val stores = syncStores(context)
        assertTrue(stores.publisher.currentSnapshot() is PairingGraphSnapshot.Absent)
        assertNull(stores.credentialStore.load())
        assertEquals(listOf("/app/network/pair"), forger.recordedRequests.map { it.path.substringBefore('?') })
    }

    @Test
    fun aGenuinePeerPairsAndItsMarkComesFromThePinnedCa() {
        val journal = peer()

        assertEquals(200, pair(journal.pairLink()).pairStatus)

        val coordinator = syncStores(context).journalIdentityCoordinator
        val expected = JournalMarkPresentation.Identified(journalMarkFromCaPem(journal.caPem))
        val presented = CountDownLatch(1)
        val remove = coordinator.addListener { if (it == expected) presented.countDown() }
        assertTrue("presented ${coordinator.currentPresentation()}", presented.await(60, TimeUnit.SECONDS))
        remove()
        assertTrue(journal.recordedRequests.none { it.path == "/app/network/api/identity" })
    }
}
