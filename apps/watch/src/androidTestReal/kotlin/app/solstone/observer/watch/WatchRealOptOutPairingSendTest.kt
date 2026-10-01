// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.watch

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkInfo
import androidx.work.WorkManager
import app.solstone.core.model.IdentityState
import app.solstone.core.observer.INGEST_PATH
import app.solstone.core.observer.SEGMENTS_PATH
import app.solstone.core.pl.RelayPairLink
import app.solstone.core.sources.MAIN_STREAM
import app.solstone.core.sources.WATCH_STREAM
import app.solstone.observer.harness.RealPairProbe
import app.solstone.observer.harness.RealRelayPairProbe
import app.solstone.platform.persistence.room.SolstonePersistenceDatabase
import app.solstone.platform.persistence.room.openSolstonePersistenceDatabase
import app.solstone.platform.pl.transport.conscrypt.OkHttpHttpsPoster
import app.solstone.platform.pl.transport.conscrypt.OkHttpRelayPairDialer
import app.solstone.platform.work.SyncScheduler
import app.solstone.platform.work.plStoreDir
import app.solstone.platform.work.syncStores
import app.solstone.testing.JournalLoopbackStandIn
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

@RunWith(AndroidJUnit4::class)
class WatchRealOptOutPairingSendTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var standIn: JournalLoopbackStandIn
    private lateinit var spoolDir: File
    private lateinit var db: SolstonePersistenceDatabase

    @Before
    fun setUp() {
        standIn = JournalLoopbackStandIn()
        val wm = WorkManager.getInstance(context)
        val workNames = listOf("solstone-sync-now", "solstone-sync-after-confirm", "solstone-sync-periodic")
        workNames.forEach { wm.cancelUniqueWork(it) }
        val deadline = System.currentTimeMillis() + 10_000L
        while (System.currentTimeMillis() < deadline) {
            val anyRunning = workNames.any { name ->
                wm.getWorkInfosForUniqueWork(name).get().any { it.state == WorkInfo.State.RUNNING }
            }
            if (!anyRunning) break
            Thread.sleep(50)
        }
        val stillRunning = workNames.filter { name ->
            wm.getWorkInfosForUniqueWork(name).get().any { it.state == WorkInfo.State.RUNNING }
        }
        assertTrue("WorkManager works still RUNNING after cancellation timeout: $stillRunning", stillRunning.isEmpty())

        spoolDir = File(context.filesDir, "spool").apply {
            deleteRecursively()
            mkdirs()
        }
        db = openSolstonePersistenceDatabase(context)
        db.clearAllTables()
        plStoreDir(context).deleteRecursively()
        plStoreDir(context).mkdirs()
    }

    @After
    fun tearDown() {
        standIn.close()
        db.close()
    }

    private fun buildStandInOkHttpClient(caCert: X509Certificate): OkHttpClient {
        val trustStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setCertificateEntry("ca", caCert)
        }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
            init(trustStore)
        }
        val trustManager = tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(trustManager), SecureRandom())
        }
        return OkHttpClient.Builder()
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            .build()
    }

    @Test
    fun realDirectPairAndWorkManagerSyncSendsWithoutConfirmation() {
        seedWatchSegments(db, spoolDir, MAIN_STREAM, standIn.instanceId)
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
        seedWatchSegments(db, spoolDir, MAIN_STREAM, standIn.instanceId)
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
        seedWatchSegments(db, spoolDir, MAIN_STREAM, standIn.instanceId)
        val stores = syncStores(context)
        val standInClient = buildStandInOkHttpClient(standIn.caCert)
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
            poster = OkHttpHttpsPoster(standInClient),
            dialer = OkHttpRelayPairDialer(client = standInClient),
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
