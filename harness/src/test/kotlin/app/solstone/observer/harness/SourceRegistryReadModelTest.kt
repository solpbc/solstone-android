// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.harness

import app.solstone.core.model.ReasonCode
import app.solstone.core.model.SourceState
import app.solstone.core.sources.SourceCondition
import app.solstone.core.sources.mapSourceState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SourceRegistryReadModelTest {
    @Test
    fun snapshotStateComesFromMapSourceStateAndIdsFromRegistration() {
        val engine = FakeSourceEngine()
        val registry = sourceRegistry(
            registrations = listOf(SourceRegistration("audio", engine)),
        )

        val row = registry.snapshot().sources.single()
        assertEquals("audio", row.sourceId)
        assertEquals(mapSourceState(engine.condition().copy(desiredOn = true)), row.state)
        assertEquals(SourceWish.On, row.wish)
    }

    @Test
    fun wishOffIsOffWithDesiredOffReason() {
        val engine = FakeSourceEngine()
        val registry = sourceRegistry(
            registrations = listOf(SourceRegistration("audio", engine)),
        )

        registry.setWish("audio", SourceWish.Off)
        val row = registry.snapshot().sources.single()

        assertEquals(SourceWish.Off, row.wish)
        assertEquals(SourceState.OFF, row.state)
        assertEquals(ReasonCode.DESIRED_OFF, row.reason)
    }

    @Test
    fun permissionRevokedLivesOnObserverNeverOnSourceReason() {
        val f = fixture(permissionStatus = grantedPermissions().copy(cameraGranted = false))
        f.controller.ensureObserving()
        val engine = FakeSourceEngine()
        val registry = sourceRegistry(
            f = f,
            registrations = listOf(SourceRegistration("audio", engine)),
        )

        val snapshot = registry.snapshot()
        assertEquals(ReasonCode.PERMISSION_REVOKED, snapshot.observer.reason)
        assertTrue(snapshot.sources.none { it.reason == ReasonCode.PERMISSION_REVOKED })
    }

    @Test
    fun wishOnWhileNotRunningIsSettingUpAndObserverOff() {
        val f = fixture()
        val engine = FakeSourceEngine(
            conditionValue = SourceCondition(
                desiredOn = true,
                running = false,
                available = true,
                needsAttention = false,
                paused = false,
            ),
        )
        val registry = sourceRegistry(
            f = f,
            registrations = listOf(SourceRegistration("audio", engine)),
        )

        val snapshot = registry.snapshot()
        val row = snapshot.sources.single()

        assertEquals(false, f.controller.desiredOn)
        assertEquals(SourceWish.On, row.wish)
        assertEquals(SourceState.SETTING_UP, row.state)
        assertEquals(SourceState.OFF, snapshot.observer.state)
    }

    @Test
    fun mixedWishesProduceSelfConsistentRows() {
        val registry = sourceRegistry(
            registrations = listOf(
                SourceRegistration("audio", FakeSourceEngine()),
                SourceRegistration("location", FakeSourceEngine()),
            ),
        )
        registry.setWish("location", SourceWish.Off)

        val snapshot = registry.snapshot()
        assertEquals(2, snapshot.sources.size)
        snapshot.sources.forEach { row ->
            assertEquals(row.wish == SourceWish.Off, row.state == SourceState.OFF)
            assertEquals(row.wish == SourceWish.Off, row.reason == ReasonCode.DESIRED_OFF)
        }
    }
}
