// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone

import android.content.Context
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.solstone.core.identity.ClientCredential
import app.solstone.core.identity.GraphMutationResult
import app.solstone.core.identity.PairingGraphSnapshot
import app.solstone.core.model.IdentityState
import app.solstone.core.model.PairedHome
import app.solstone.observer.formfactor.phone.PhonePairingEvent
import app.solstone.observer.formfactor.phone.PhonePairingSession
import app.solstone.observer.scaffold.ObserverApplication
import app.solstone.platform.work.confirmCurrentJournal
import app.solstone.platform.work.syncStores
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhonePairingActionRuntimeTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val model = PhonePairingViewModel()
    private val modelStore = ViewModelStore().apply { put("pairing", model) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val stores get() = syncStores(context)
    private val controller by lazy { (context.applicationContext as ObserverApplication).runtime.container().controller }

    @Before fun setUp() {
        resetObserverRuntime()
        resetPersistence(context)
        assertTrue(stores.publisher.forget() is GraphMutationResult.Cleared)
        stores.journalConfirmationStore.settle()
    }

    @After fun tearDown() = runBlocking {
        withContext(Dispatchers.Main) { modelStore.clear() }
        scope.cancel()
        assertTrue(stores.publisher.forget() is GraphMutationResult.Cleared)
        stores.journalConfirmationStore.settle()
        resetObserverRuntime()
    }

    private fun install(id: String): PairingGraphSnapshot.Committed {
        val fingerprint = "sha256:$id"
        val result = stores.publisher.installOrReplace(
            PairedHome(id, id, "https://link.solstone.app", "sha256:ca", fingerprint,
                "phone", "test-$id", null, IdentityState.PAIRED),
            ClientCredential(
                "-----BEGIN PRIVATE KEY-----\nkey\n-----END PRIVATE KEY-----\n",
                "-----BEGIN CERTIFICATE-----\ncert\n-----END CERTIFICATE-----\n",
                listOf("-----BEGIN CERTIFICATE-----\nca\n-----END CERTIFICATE-----\n"),
            ), null, false,
        )
        assertTrue(result is GraphMutationResult.Applied)
        return stores.publisher.currentSnapshot() as PairingGraphSnapshot.Committed
    }

    @Test fun confirmationAndMismatchHaveOneAdmissionSlotInBothOrders() = runBlocking {
        for (first in PhonePairingViewModel.ActionKind.entries) {
            val a = install(first.name)
            withContext(Dispatchers.Main) {
                model.dispatch(PhonePairingEvent.Dismiss, controller, stores)
                model.dispatch(PhonePairingEvent.OpenMark, controller, stores)
            }
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val pending = scope.async {
                model.runOwnerAction(first, a.pairing, controller, stores) {
                    entered.complete(Unit)
                    release.await()
                    false
                }
            }
            try {
                withTimeout(10_000) { entered.await() }
                val other = PhonePairingViewModel.ActionKind.entries.first { it != first }
                val admitted = model.runOwnerAction(other, a.pairing, controller, stores) {
                    error("second action must not reach IO")
                }
                assertFalse(admitted)
            } finally {
                release.complete(Unit)
                withTimeout(10_000) { pending.await() }
            }
        }
    }

    @Test fun completedCleanupReconcilesBackAndReopenedHeldMark() = runBlocking {
        val a = install("a")
        withContext(Dispatchers.Main) { model.dispatch(PhonePairingEvent.OpenMark, controller, stores) }
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val pending = scope.async {
            model.runOwnerAction(PhonePairingViewModel.ActionKind.MISMATCH, a.pairing, controller, stores) {
                entered.complete(Unit)
                release.await()
                stores.publisher.forget() is GraphMutationResult.Cleared
            }
        }
        try {
            withTimeout(10_000) { entered.await() }
            withContext(Dispatchers.Main) {
                model.dispatch(PhonePairingEvent.Dismiss, controller, stores)
                model.dispatch(PhonePairingEvent.OpenMark, controller, stores)
            }
        } finally {
            release.complete(Unit)
        }
        assertTrue(withTimeout(10_000) { pending.await() })
        withContext(Dispatchers.Main) {
            val session = model.pairingState.session
            assertTrue(session is PhonePairingSession.Held && session.disconnected)
        }
    }

    @Test fun delayedConfirmationCompletionCannotDismissLaterHeldJournal() = runBlocking {
        val a = install("a")
        withContext(Dispatchers.Main) { model.dispatch(PhonePairingEvent.OpenMark, controller, stores) }
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val pending = scope.async {
            model.runOwnerAction(PhonePairingViewModel.ActionKind.CONFIRM, a.pairing, controller, stores) {
                assertTrue(confirmCurrentJournal(stores.publisher, stores.journalConfirmationStore, a.home.clientCertFingerprint))
                entered.complete(Unit)
                release.await()
                true
            }
        }
        try {
            withTimeout(10_000) { entered.await() }
            install("b")
            withContext(Dispatchers.Main) {
                model.dispatch(PhonePairingEvent.Dismiss, controller, stores)
                model.dispatch(PhonePairingEvent.OpenMark, controller, stores)
            }
        } finally {
            release.complete(Unit)
        }
        assertTrue(withTimeout(10_000) { pending.await() })
        withContext(Dispatchers.Main) { assertTrue(model.pairingState.session is PhonePairingSession.Held) }
    }
}
