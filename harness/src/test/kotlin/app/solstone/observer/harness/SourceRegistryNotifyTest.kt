// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.harness

import kotlin.test.Test
import kotlin.test.assertEquals

class SourceRegistryNotifyTest {
    @Test
    fun togglePostsThroughMainPosterAndCloseStopsUnknownDoesNotNotify() {
        var posts = 0
        var deliveries = 0
        val main = MainPoster { task ->
            posts += 1
            task()
        }
        val engine = FakeSourceEngine()
        val registry = sourceRegistry(
            registrations = listOf(SourceRegistration("audio", engine)),
            main = main,
        )
        val subscription = registry.subscribe { deliveries += 1 }

        registry.setWish("audio", SourceWish.Off)
        assertEquals(1, posts)
        assertEquals(1, deliveries)

        subscription.close()
        subscription.close()
        registry.setWish("audio", SourceWish.On)
        assertEquals(1, posts)
        assertEquals(1, deliveries)

        registry.setWish("missing", SourceWish.On)
        assertEquals(1, posts)
        assertEquals(1, deliveries)
    }

    @Test
    fun refreshSubscribersNotifiesWithoutAWishChange() {
        var deliveries = 0
        val registry = sourceRegistry(
            registrations = listOf(SourceRegistration("audio", FakeSourceEngine())),
            main = MainPoster { task -> task() },
        )
        val subscription = registry.subscribe { deliveries += 1 }

        // Engines start, stop and become silenced with no wish change at all. Without this a reader
        // that subscribed once renders the state as of the last toggle forever.
        registry.refreshSubscribers()
        assertEquals(1, deliveries)

        registry.refreshSubscribers()
        assertEquals(2, deliveries)

        subscription.close()
        registry.refreshSubscribers()
        assertEquals(2, deliveries)
    }
}
