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
import kotlin.test.assertEquals
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

    private class RoutingFakeClient(
        private val handler: (method: String, path: String) -> HttpResponse,
    ) : PlHttpClient {
        override fun request(
            method: String,
            path: String,
            headers: Map<String, String>,
            body: ByteArray?,
            maxResponseBytes: Int,
        ): HttpResponse = handler(method, path)
    }

    @Test
    fun boundedJobTimeoutTransitionsToUnavailable() {
        val store = FakeMarkStore()
        val executor = Executors.newCachedThreadPool()
        // 50ms bound
        val coordinator = JournalIdentityRefreshCoordinator(
            store = store,
            executor = executor,
            boundMillis = 50L,
        )

        val notifiedUnavailable = CountDownLatch(1)
        coordinator.addListener {
            if (it is JournalMarkPresentation.Unavailable) {
                notifiedUnavailable.countDown()
            }
        }

        val releaseClient = CountDownLatch(1)
        val client = RoutingFakeClient { _, _ ->
            releaseClient.await(5, TimeUnit.SECONDS)
            HttpResponse(200, emptyMap(), ByteArray(0))
        }

        coordinator.onUsableConnection(
            instanceId = "inst-1",
            pairingMatches = { true },
            openClient = { client },
        )

        assertTrue(notifiedUnavailable.await(5, TimeUnit.SECONDS), "Timeout should set Unavailable")
        assertIs<JournalMarkPresentation.Unavailable>(coordinator.currentPresentation())

        releaseClient.countDown()
        coordinator.close()
        executor.shutdown()
    }

    @Test
    fun instanceIdMismatchSetsUnavailableAndDoesNotSaveMark() {
        val store = FakeMarkStore()
        val executor = Executors.newCachedThreadPool()
        val coordinator = JournalIdentityRefreshCoordinator(
            store = store,
            executor = executor,
        )

        val notifiedUnavailable = CountDownLatch(1)
        coordinator.addListener {
            if (it is JournalMarkPresentation.Unavailable) {
                notifiedUnavailable.countDown()
            }
        }

        val mismatchJson = """
            {
              "committed": true,
              "instance_id": "different-inst-id",
              "mark": {
                "icon1": { "name": "piano", "svg": "<path d=\"M0 0h24v24H0z\"/>", "color": { "name": "blue", "hex": "#3b82f6" }, "rot": 45 },
                "icon2": { "name": "key", "svg": "<path d=\"M0 0h24v24H0z\"/>", "color": { "name": "purple", "hex": "#a855f7" }, "rot": 0 },
                "words": ["liquefy", "smock"]
              }
            }
        """.trimIndent()

        val client = RoutingFakeClient { _, _ ->
            HttpResponse(200, emptyMap(), mismatchJson.toByteArray())
        }

        coordinator.onUsableConnection(
            instanceId = "inst-1",
            pairingMatches = { true },
            openClient = { client },
        )

        assertTrue(notifiedUnavailable.await(5, TimeUnit.SECONDS), "Instance ID mismatch should set Unavailable")
        assertIs<JournalMarkPresentation.Unavailable>(coordinator.currentPresentation())
        assertNull(store.savedRecord, "Mark must not be saved on instance_id mismatch")

        coordinator.close()
        executor.shutdown()
    }

    @Test
    fun uncommittedBodyWithNullInstanceIdStaysGeneric() {
        val store = FakeMarkStore()
        val executor = Executors.newCachedThreadPool()
        val coordinator = JournalIdentityRefreshCoordinator(
            store = store,
            executor = executor,
        )

        val notifiedGeneric = CountDownLatch(1)
        coordinator.addListener {
            if (it is JournalMarkPresentation.Generic) {
                notifiedGeneric.countDown()
            }
        }

        val uncommittedJson = """
            {
              "committed": false,
              "instance_id": null,
              "mark": null
            }
        """.trimIndent()

        val client = RoutingFakeClient { _, _ ->
            HttpResponse(200, emptyMap(), uncommittedJson.toByteArray())
        }

        coordinator.onUsableConnection(
            instanceId = "inst-1",
            pairingMatches = { true },
            openClient = { client },
        )

        assertTrue(notifiedGeneric.await(5, TimeUnit.SECONDS), "Uncommitted body with null instanceId should set Generic")
        assertIs<JournalMarkPresentation.Generic>(coordinator.currentPresentation())

        coordinator.close()
        executor.shutdown()
    }
}
