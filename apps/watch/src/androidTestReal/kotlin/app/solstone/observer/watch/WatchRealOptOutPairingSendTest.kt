// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.watch

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.solstone.core.model.IdentityState
import app.solstone.core.observer.INGEST_PATH
import app.solstone.core.observer.SEGMENTS_PATH
import app.solstone.core.pl.RelayPairLink
import app.solstone.core.sources.WATCH_STREAM
import app.solstone.observer.harness.RealPairProbe
import app.solstone.observer.harness.RealRelayPairProbe
import app.solstone.platform.persistence.room.SolstonePersistenceDatabase
import app.solstone.platform.persistence.room.openSolstonePersistenceDatabase
import app.solstone.platform.work.SyncScheduler
import app.solstone.platform.work.plStoreDir
import app.solstone.platform.work.syncStores
import app.solstone.testing.JournalLoopbackStandIn
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class WatchRealOptOutPairingSendTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var standIn: JournalLoopbackStandIn
    private lateinit var spoolDir: File
    private lateinit var db: SolstonePersistenceDatabase

    @Before
    fun setUp() {
        standIn = JournalLoopbackStandIn()
        spoolDir = File(context.filesDir, "spool").apply { mkdirs() }
        db = openSolstonePersistenceDatabase(context)
        plStoreDir(context).deleteRecursively()
        plStoreDir(context).mkdirs()
    }

    @After
    fun tearDown() {
        standIn.close()
    }

    @Test
    fun realDirectPairAndWorkManagerSyncSendsWithoutConfirmation() {
        seedWatchSegments(db, spoolDir, WATCH_STREAM, standIn.instanceId)
        val stores = syncStores(context)
        val pairProbe = RealPairProbe(
            credentialStore = stores.credentialStore,
            identityStore = stores.identityStore,
            endpointStore = stores.endpointStore,
            journalVersionStore = stores.journalVersionStore,
            coordinator = stores.journalVersionCoordinator,
            mutator = stores.identityMutator,
            relayAccessCoordinator = stores.relayAccessCoordinator,
            journalMarkStore = stores.journalMarkStore,
            journalIdentityCoordinator = stores.journalIdentityCoordinator,
            publisher = stores.publisher,
            confirmation = stores.journalConfirmationStore,
        )

        val probeResult = pairProbe.pairAndProbe(standIn.pairLink(), "solstone watch")
        assertEquals(200, probeResult.pairStatus)

        SyncScheduler.enqueueNow(context, WATCH_STREAM)
        waitForWatchSegmentsEvicted(db)

        assertTrue(standIn.recordedRequests.any { it.method == "GET" && it.path.startsWith(SEGMENTS_PATH) })
        assertTrue(standIn.recordedRequests.any { it.method == "POST" && it.path == INGEST_PATH })
    }

    @Test
    fun directStatusProbeThrowsAfterCommitPairingPersistsAndSyncSends() {
        standIn.dropStatusProbe = true
        seedWatchSegments(db, spoolDir, WATCH_STREAM, standIn.instanceId)
        val stores = syncStores(context)
        val pairProbe = RealPairProbe(
            credentialStore = stores.credentialStore,
            identityStore = stores.identityStore,
            endpointStore = stores.endpointStore,
            journalVersionStore = stores.journalVersionStore,
            coordinator = stores.journalVersionCoordinator,
            mutator = stores.identityMutator,
            relayAccessCoordinator = stores.relayAccessCoordinator,
            journalMarkStore = stores.journalMarkStore,
            journalIdentityCoordinator = stores.journalIdentityCoordinator,
            publisher = stores.publisher,
            confirmation = stores.journalConfirmationStore,
        )

        try {
            pairProbe.pairAndProbe(standIn.pairLink(), "solstone watch")
        } catch (_: Exception) {
        }

        assertEquals(IdentityState.PAIRED, stores.identityMutator.current()?.state)

        standIn.dropStatusProbe = false
        SyncScheduler.enqueueNow(context, WATCH_STREAM)
        waitForWatchSegmentsEvicted(db)

        assertTrue(standIn.recordedRequests.any { it.method == "GET" && it.path.startsWith(SEGMENTS_PATH) })
        assertTrue(standIn.recordedRequests.any { it.method == "POST" && it.path == INGEST_PATH })
    }

    @Test
    fun relayEnrollFailsPairingPersistsAndSyncSends() {
        standIn.relayEnrollStatus = 503
        seedWatchSegments(db, spoolDir, WATCH_STREAM, standIn.instanceId)
        val stores = syncStores(context)
        val relayPairProbe = RealRelayPairProbe(
            credentialStore = stores.credentialStore,
            identityStore = stores.identityStore,
            journalVersionStore = stores.journalVersionStore,
            coordinator = stores.journalVersionCoordinator,
            mutator = stores.identityMutator,
            relayAccessCoordinator = stores.relayAccessCoordinator,
            endpointStore = stores.endpointStore,
            journalMarkStore = stores.journalMarkStore,
            journalIdentityCoordinator = stores.journalIdentityCoordinator,
            publisher = stores.publisher,
            confirmation = stores.journalConfirmationStore,
        )

        val relayLink = RelayPairLink(
            s = ByteArray(8) { 1 },
            caFpSpki = standIn.caPinSpki,
            relayOrigin = "https://127.0.0.1:${standIn.port}",
        )
        try {
            relayPairProbe.pairOverRelay(relayLink, "solstone watch")
        } catch (_: Exception) {
        }

        assertEquals(IdentityState.PAIRED, stores.identityMutator.current()?.state)

        SyncScheduler.enqueueNow(context, WATCH_STREAM)
        waitForWatchSegmentsEvicted(db)

        assertTrue(standIn.recordedRequests.any { it.method == "GET" && it.path.startsWith(SEGMENTS_PATH) })
        assertTrue(standIn.recordedRequests.any { it.method == "POST" && it.path == INGEST_PATH })
    }
}
