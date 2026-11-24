// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import app.solstone.core.sources.DELIVERY_STREAMS
import app.solstone.observer.scaffold.ObserverAppContainer
import app.solstone.platform.persistence.room.ImportCustodyStore
import app.solstone.platform.work.SyncScheduler
import app.solstone.platform.work.importStoreRoot
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RealFlavorOpportunisticSyncRuntimeTest {
    /**
     * AC5a red proof: before ACCESS_NETWORK_STATE, real opportunistic sync degraded during network callback registration.
     *
     * Gate: `make ci-device` runs this via class-filtered `:apps:phone:pixel5api35RealDebugAndroidTest`.
     */
    @Test
    fun realFlavorRegistersNetworkCallbackWithoutDegrading() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val container = ObserverAppContainer(context, phoneSpec)
        try {
            val opportunisticSync = container.flavor.opportunisticSync
            assertNotNull(opportunisticSync)

            opportunisticSync!!.start()

            assertFalse(opportunisticSync.isDegraded())
        } finally {
            container.close()
        }
    }

    @Test
    fun mainThreadPendingReadFailureAppearsInPhoneEventLog() {
        val application = ApplicationProvider.getApplicationContext<PhoneApplication>()
        val opportunisticSync = application.runtime.container().flavor.opportunisticSync
        assertNotNull(opportunisticSync)
        val marker = "kind=caught site=opportunistic-sync-pending-inspection-failed type=IllegalStateException"
        val before = PhoneDiagLog.installedSink()!!.readAll().split(marker).size

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            opportunisticSync!!.enqueueIfPending()
        }

        val after = PhoneDiagLog.installedSink()!!.readAll().split(marker).size
        assertTrue("pending Room failure should reach the event log", after > before)
    }

    @Test
    fun realFlavorNetworkRecoveryWakesWaitingImportAndClearsBackoff() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val workManager = WorkManager.getInstance(context)
        workManager.cancelUniqueWork(SyncScheduler.NOW_WORK_NAME).result.get(5, TimeUnit.SECONDS)
        workManager.pruneWork().result.get(5, TimeUnit.SECONDS)

        val container = ObserverAppContainer(context, phoneSpec)
        val db = container.persistenceDatabase()
        val importRoot = importStoreRoot(context).toPath()
        val store = ImportCustodyStore(importRoot, db.importDao())
        var createdId: String? = null
        try {
            assertEquals(0, DELIVERY_STREAMS.sumOf(db.segmentDao()::pendingCount))
            val id = requireNotNull(store.begin("file"))
            createdId = id
            val bytes = "synthetic-import".toByteArray()
            Files.write(importRoot.resolve(".staging").resolve(id).resolve("payload"), bytes)
            assertTrue(store.publish(id, "note.txt", "text/plain", bytes.size.toLong()))
            val now = System.currentTimeMillis()
            assertTrue(store.markRetry(id, "test-home", now))
            assertNotNull(db.importDao().byId(id)?.nextAttemptAt)
            assertEquals(1, db.importDao().byId(id)?.attemptCount)

            val opportunisticSync = requireNotNull(container.flavor.opportunisticSync)
            opportunisticSync.onNetworkLost()
            opportunisticSync.onUsableNetwork()

            val afterRecovery = requireNotNull(db.importDao().byId(id))
            assertNull("network recovery should clear import backoff delay", afterRecovery.nextAttemptAt)
            assertEquals(1, afterRecovery.attemptCount)

            val deadline = System.currentTimeMillis() + 5_000L
            var enqueued = false
            while (System.currentTimeMillis() < deadline) {
                val infos = workManager.getWorkInfosForUniqueWork(SyncScheduler.NOW_WORK_NAME).get(5, TimeUnit.SECONDS)
                if (infos.isNotEmpty()) {
                    enqueued = true
                    break
                }
                Thread.sleep(50L)
            }
            assertTrue("network recovery with only a waiting import should enqueue NOW sync work", enqueued)

            // Same-network repeat stays deduped; a second loss + recovery wakes again with the same count.
            assertTrue(store.markRetry(id, "test-home", now + 1_000L))
            opportunisticSync.onUsableNetwork()
            assertNotNull("steady network callback must stay deduped", db.importDao().byId(id)?.nextAttemptAt)

            opportunisticSync.onNetworkLost()
            opportunisticSync.onUsableNetwork()
            val afterSecondRecovery = requireNotNull(db.importDao().byId(id))
            assertNull("second network recovery should clear backoff again", afterSecondRecovery.nextAttemptAt)
            assertEquals(2, afterSecondRecovery.attemptCount)
        } finally {
            createdId?.let { store.remove(it) }
            container.close()
            workManager.cancelUniqueWork(SyncScheduler.NOW_WORK_NAME).result.get(5, TimeUnit.SECONDS)
            workManager.pruneWork().result.get(5, TimeUnit.SECONDS)
        }
    }
}
