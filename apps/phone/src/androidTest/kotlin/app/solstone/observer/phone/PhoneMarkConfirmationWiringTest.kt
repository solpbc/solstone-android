// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.solstone.core.identity.ClientCredential
import app.solstone.core.identity.GraphMutationResult
import app.solstone.core.identity.JournalConfirmationPolicy
import app.solstone.core.model.IdentityState
import app.solstone.core.model.PairedHome
import app.solstone.platform.work.syncStores
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class PhoneMarkConfirmationWiringTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        resetObserverRuntime()
        resetPersistence(context)
        PhoneJournalTestHooks.reset()
        PhoneShellActivity.promptedConfirmationThisProcess = false
    }

    @After
    fun tearDown() {
        resetObserverRuntime()
        PhoneJournalTestHooks.reset()
        PhoneShellActivity.promptedConfirmationThisProcess = false
        runCatching { syncStores(context).publisher.forget() }
    }

    @Test
    fun unconfirmedPairingIsAwaitingUntilConfirmOnTheSharedStore() {
        assertTrue(JournalConfirmationPolicy.consults)
        val stores = syncStores(context)
        val publisher = stores.publisher
        publisher.forget()

        val fingerprint = "sha256:wiring-fingerprint"
        val home = PairedHome(
            instanceId = "home-wiring",
            homeLabel = "Home",
            relayOrigin = "https://link.solstone.app",
            caChainFingerprint = "sha256:ca-1",
            clientCertFingerprint = fingerprint,
            observerHandle = "obs",
            deviceToken = "token-1",
            expiresAt = "2030-01-01T00:00:00Z",
            state = IdentityState.PAIRED,
        )
        val cred = ClientCredential(
            privateKeyPem = "-----BEGIN PRIVATE KEY-----\nkey\n-----END PRIVATE KEY-----\n",
            clientCertPem = "-----BEGIN CERTIFICATE-----\ncert\n-----END CERTIFICATE-----\n",
            caChainPem = listOf("-----BEGIN CERTIFICATE-----\nca\n-----END CERTIFICATE-----\n"),
        )
        val installResult = publisher.installOrReplace(home, cred, null, false)
        assertTrue(installResult is GraphMutationResult.Applied)

        assertTrue(phoneAwaitingMarkConfirmation(context))

        val first = syncStores(context).journalConfirmationStore
        val counter = AtomicInteger(0)
        val initialLatch = CountDownLatch(1)
        val furtherLatch = CountDownLatch(1)
        val removeListener = first.addListener {
            val count = counter.incrementAndGet()
            if (count == 1) {
                initialLatch.countDown()
            } else {
                furtherLatch.countDown()
            }
        }
        try {
            assertTrue(initialLatch.await(5, TimeUnit.SECONDS))
            val second = syncStores(context).journalConfirmationStore
            assertSame(first, second)

            second.confirm(fingerprint)
            assertTrue(furtherLatch.await(5, TimeUnit.SECONDS))
            assertFalse(phoneAwaitingMarkConfirmation(context))
        } finally {
            removeListener()
        }
    }

    @Test
    fun alreadyConfirmedStartReturnsFalse() {
        assertTrue(JournalConfirmationPolicy.consults)
        val stores = syncStores(context)
        val publisher = stores.publisher
        publisher.forget()

        val fingerprint = "sha256:already-confirmed"
        val home = PairedHome(
            instanceId = "home-already-confirmed",
            homeLabel = "Home",
            relayOrigin = "https://link.solstone.app",
            caChainFingerprint = "sha256:ca-1",
            clientCertFingerprint = fingerprint,
            observerHandle = "obs",
            deviceToken = "token-1",
            expiresAt = "2030-01-01T00:00:00Z",
            state = IdentityState.PAIRED,
        )
        val cred = ClientCredential(
            privateKeyPem = "-----BEGIN PRIVATE KEY-----\nkey\n-----END PRIVATE KEY-----\n",
            clientCertPem = "-----BEGIN CERTIFICATE-----\ncert\n-----END CERTIFICATE-----\n",
            caChainPem = listOf("-----BEGIN CERTIFICATE-----\nca\n-----END CERTIFICATE-----\n"),
        )
        val installResult = publisher.installOrReplace(home, cred, null, false)
        assertTrue(installResult is GraphMutationResult.Applied)

        stores.journalConfirmationStore.confirm(fingerprint)
        assertFalse(phoneAwaitingMarkConfirmation(context))
    }
}
