// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.solstone.platform.pl.transport.conscrypt.pairAndProbe
import app.solstone.platform.work.syncStores
import app.solstone.testing.JournalLoopbackStandIn
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** A redraw with no process behind it (a reboot, an app update) reads what it says from the stores. */
@RunWith(AndroidJUnit4::class)
class PhoneWidgetColdFactsRuntimeTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val journal = JournalLoopbackStandIn()

    @Before
    fun setUp() {
        resetObserverRuntime()
        resetPersistence(context)
    }

    @After
    fun tearDown() {
        journal.close()
        resetObserverRuntime()
        runCatching { syncStores(context).publisher.forget() }
    }

    @Test
    fun itStillAsksForTheMarkAndToPairAgainAndOtherwiseSaysNothing() {
        val stores = syncStores(context)
        pairAndProbe(
            pairLink = journal.pairLink(),
            deviceLabel = "test phone",
            credentialStore = stores.credentialStore,
            identityStore = stores.identityStore,
            endpointStore = stores.endpointStore,
            journalMarkStore = stores.journalMarkStore,
            journalIdentityCoordinator = stores.journalIdentityCoordinator,
            publisher = stores.publisher,
            confirmation = stores.journalConfirmationStore,
        )
        val application = context.applicationContext as PhoneApplication
        assertNull(application.runtime.containerIfInitialized)

        assertEquals("confirm the mark", application.widgetModel().syncText)

        val pairing = stores.identityMutator.currentPairingGeneration()!!
        stores.journalConfirmationStore.confirm(pairing.clientCertFingerprint)
        assertNull(application.widgetModel().syncText)

        stores.pairingRefusalStore!!.markRefused(pairing)
        assertEquals("pair again", application.widgetModel().syncText)
    }
}
