// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone

import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkManager
import app.solstone.observer.scaffold.ObserverActivity
import app.solstone.platform.work.SyncScheduler
import app.solstone.testing.validDirectPairLink
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ObserverSyncSchedulingRuntimeTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        resetObserverRuntime()
        resetPersistence(context)
        WorkManager.getInstance(context).cancelAllWork().result.get(5, TimeUnit.SECONDS)
    }

    @After
    fun tearDown() {
        resetObserverRuntime()
    }

    /**
     * AC2 red proof: startup previously did not schedule shared periodic sync from the app-scoped container.
     */
    @Test
    fun startupRegistersUniquePeriodicSyncWork() {
        ActivityScenario.launch(ObserverActivity::class.java).use {
            val container = waitForObserverContainer()
            waitUntil("mock periodic sync control") {
                container.flavor.syncControl?.enqueuePeriodicCalls == 1
            }
            val work = WorkManager.getInstance(context)
                .getWorkInfosForUniqueWork(SyncScheduler.PERIODIC_WORK_NAME)
                .get(5, TimeUnit.SECONDS)

            assertEquals(1, container.flavor.syncControl?.enqueuePeriodicCalls)
            assertEquals(1, work.size)
        }
    }

    /**
     * AC2 red proof: successful pairing only enqueues opportunistic sync when pending evidence exists.
     */
    @Test
    fun pairingSuccessEnqueuesOnlyWhenPendingEvidenceExists() {
        ActivityScenario.launch(ObserverActivity::class.java).use {
            val container = waitForObserverContainer()
            waitForRecovery(container)
            val sync = requireNotNull(container.flavor.syncControl)

            val initialEnqueueNowCalls = sync.enqueueNowCalls
            assertEquals(0, pendingEvidenceCount(context))
            container.controller.onScannedPairLink(validDirectPairLink())
            assertEquals(initialEnqueueNowCalls, sync.enqueueNowCalls)

            seedPendingEvidence(context)
            container.controller.onScannedPairLink(validDirectPairLink())

            assertEquals(initialEnqueueNowCalls + 1, sync.enqueueNowCalls)
        }
    }
}
