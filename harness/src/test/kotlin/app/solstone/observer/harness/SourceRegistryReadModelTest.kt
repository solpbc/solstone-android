// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.harness

import app.solstone.core.model.ReasonCode
import app.solstone.core.model.SilencedFact
import app.solstone.core.model.SourceState
import app.solstone.core.sources.SourceCondition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SourceRegistryReadModelTest {
    @Test
    fun snapshotStateComesFromReducerAndIdsFromRegistration() {
        val engine = FakeSourceEngine()
        val registry = sourceRegistry(
            registrations = listOf(SourceRegistration("audio", engine)),
        )

        val row = registry.snapshot().sources.single()
        assertEquals("audio", row.sourceId)
        assertEquals(SourceState.SETTING_UP, row.state)
        assertEquals(ReasonCode.NONE, row.reason)
        assertEquals(SourceWish.On, row.wish)
    }

    @Test
    fun wishOffIsOffWithNoReason() {
        val engine = FakeSourceEngine()
        val registry = sourceRegistry(
            registrations = listOf(SourceRegistration("audio", engine)),
        )

        registry.setWish("audio", SourceWish.Off)
        val row = registry.snapshot().sources.single()

        assertEquals(SourceWish.Off, row.wish)
        assertEquals(SourceState.OFF, row.state)
        assertEquals(ReasonCode.NONE, row.reason)
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
                silenced = SilencedFact.UNKNOWN,
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
    fun silencedFactMapsPerSourceStateWithoutChangingRunning() {
        assertEquals(SourceState.ON, sourceState(SilencedFact.NOT_SILENCED))
        assertEquals(SourceState.PAUSED, sourceState(SilencedFact.SILENCED))
        assertEquals(SourceState.ON, sourceState(SilencedFact.UNKNOWN))
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
        val audio = snapshot.sources.single { it.sourceId == "audio" }
        val location = snapshot.sources.single { it.sourceId == "location" }
        assertEquals(SourceWish.Off, location.wish)
        assertEquals(SourceState.OFF, location.state)
        assertEquals(SourceWish.On, audio.wish)
        assertTrue(audio.state != SourceState.OFF)
        snapshot.sources.forEach { row ->
            assertEquals(row.wish == SourceWish.Off, row.state == SourceState.OFF)
            assertEquals(ReasonCode.NONE, row.reason)
        }
    }

    private fun sourceState(silenced: SilencedFact): SourceState =
        sourceRegistry(
            registrations = listOf(
                SourceRegistration(
                    "audio",
                    FakeSourceEngine(conditionValue = condition(silenced)),
                ),
            ),
        ).snapshot().sources.single().state

    private fun condition(silenced: SilencedFact) =
        SourceCondition(
            desiredOn = true,
            running = true,
            available = true,
            needsAttention = false,
            paused = false,
            silenced = silenced,
        )
}
