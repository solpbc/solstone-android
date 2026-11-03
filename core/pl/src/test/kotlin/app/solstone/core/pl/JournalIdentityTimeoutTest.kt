// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.pl

import app.solstone.core.identity.JournalMarkPresentation
import app.solstone.core.identity.JournalMarkRecord
import app.solstone.core.identity.JournalMarkStore
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JournalIdentityTimeoutTest {

    private class FakeMarkStore : JournalMarkStore {
        var savedRecord: JournalMarkRecord? = null

        override fun load(): JournalMarkRecord? = savedRecord
        override fun save(record: JournalMarkRecord) { savedRecord = record }
        override fun clear() { savedRecord = null }
    }

    // Without a pairing authority there is no pinned CA to read, so nothing derives and nothing is
    // kept. The mark reads as pending, and asking for it gets no ticket.
    @Test
    fun withoutAnAuthorityThereIsNothingToDeriveAndNothingIsSaved() {
        val store = FakeMarkStore()
        val executor = Executors.newCachedThreadPool()
        val coordinator = JournalIdentityRefreshCoordinator(
            store = store,
            executor = executor,
            boundMillis = 50L,
            deriveMark = { error("nothing to derive") },
        )
        val presented = CountDownLatch(1)
        val remove = coordinator.addListener { presented.countDown() }

        assertTrue(presented.await(5, TimeUnit.SECONDS))
        assertIs<JournalMarkPresentation.Loading>(coordinator.currentPresentation())
        assertNull(coordinator.onMarkRequested())
        assertNull(store.savedRecord)

        remove()
        coordinator.close()
        executor.shutdown()
    }
}
