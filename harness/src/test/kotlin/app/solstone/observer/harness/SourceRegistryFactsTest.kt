// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.harness

import app.solstone.core.model.ReasonCode
import app.solstone.core.model.SilencedFact
import app.solstone.core.model.SourceState
import app.solstone.core.sources.EmissionSink
import app.solstone.core.sources.SourceCondition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SourceRegistryFactsTest {
    @Test
    fun storageFullMarksEveryDesiredOnSourceRow() {
        val registry = sourceRegistry(
            f = fixture(snapshot = snapshot(storageOk = false)),
            registrations = listOf(
                SourceRegistration("audio", FakeSourceEngine(conditionValue = runningCondition())),
                SourceRegistration("location", FakeSourceEngine(conditionValue = runningCondition())),
            ),
        )

        val rows = registry.snapshot().sources

        assertTrue(rows.all { it.state == SourceState.NEEDS_ATTENTION })
        assertTrue(rows.all { it.reason == ReasonCode.STORAGE_FULL })
    }

    @Test
    fun locationPermissionFaultMarksObserverAndLocationRow() {
        val f = fixture(
            permissionStatus = grantedPermissions().copy(
                fineLocationGranted = false,
                coarseLocationGranted = false,
            ),
            snapshot = snapshot(),
        )
        f.desiredStore.setDesiredOn(true)
        val registry = sourceRegistry(
            f = f,
            registrations = listOf(
                SourceRegistration(
                    sourceId = "location",
                    engine = FakeSourceEngine(conditionValue = unavailableCondition()),
                    requiredPermissionsGranted = { it.fineLocationGranted || it.coarseLocationGranted },
                ),
            ),
        )

        val readModel = registry.snapshot()

        assertEquals(ReasonCode.PERMISSION_REVOKED, readModel.observer.reason)
        assertEquals(SourceState.NEEDS_ATTENTION, readModel.sources.single().state)
        assertEquals(ReasonCode.PERMISSION_REVOKED, readModel.sources.single().reason)
    }

    @Test
    fun locationProviderDisabledUsesProviderSilentWhenPermissionsAreGranted() {
        val f = fixture(snapshot = snapshot())
        f.desiredStore.setDesiredOn(true)
        val registry = sourceRegistry(
            f = f,
            registrations = listOf(
                SourceRegistration(
                    sourceId = "location",
                    engine = FakeSourceEngine(conditionValue = unavailableCondition()),
                    requiredPermissionsGranted = { it.fineLocationGranted || it.coarseLocationGranted },
                ),
            ),
        )

        val row = registry.snapshot().sources.single()

        assertEquals(SourceState.NEEDS_ATTENTION, row.state)
        assertEquals(ReasonCode.PROVIDER_SILENT, row.reason)
    }

    @Test
    fun notificationsPermissionDenialAppearsOnObserverWithoutSmearingToSources() {
        val f = fixture(
            permissionStatus = grantedPermissions().copy(notificationsGranted = false),
            snapshot = snapshot(),
        )
        f.desiredStore.setDesiredOn(true)
        val registry = sourceRegistry(
            f = f,
            registrations = listOf(
                SourceRegistration(
                    sourceId = "audio",
                    engine = FakeSourceEngine(conditionValue = runningCondition()),
                    requiredPermissionsGranted = { it.microphoneGranted },
                ),
            ),
        )

        val readModel = registry.snapshot()

        assertEquals(ReasonCode.PERMISSION_REVOKED, readModel.observer.reason)
        assertTrue(readModel.sources.none { it.reason == ReasonCode.PERMISSION_REVOKED })
    }

    @Test
    fun notificationsPermissionDenialDoesNotVanishWithoutAnOwningSource() {
        val f = fixture(
            permissionStatus = grantedPermissions().copy(notificationsGranted = false),
            snapshot = snapshot(),
        )
        f.desiredStore.setDesiredOn(true)
        val registry = sourceRegistry(f = f, registrations = emptyList())

        assertEquals(ReasonCode.PERMISSION_REVOKED, registry.snapshot().observer.reason)
    }

    @Test
    fun startedSourceThatStopsReportsRebooted() {
        val engine = FakeSourceEngine(conditionValue = runningCondition())
        val registry = sourceRegistry(
            registrations = listOf(SourceRegistration("audio", engine)),
        )
        registry.engines.single().start(EmissionSink { })
        engine.conditionValue = engine.conditionValue.copy(running = false)

        val row = registry.snapshot().sources.single()

        assertEquals(SourceState.NEEDS_ATTENTION, row.state)
        assertEquals(ReasonCode.REBOOTED, row.reason)
    }

    private fun snapshot(storageOk: Boolean = true) =
        SourceRuntimeSnapshot(
            engineRunning = true,
            providerEmitting = true,
            storageOk = storageOk,
            silenced = SilencedFact.NOT_SILENCED,
            engineStartIssued = true,
        )

    private fun runningCondition() =
        SourceCondition(
            desiredOn = true,
            running = true,
            available = true,
            needsAttention = false,
            paused = false,
            silenced = SilencedFact.NOT_SILENCED,
        )

    private fun unavailableCondition() =
        runningCondition().copy(available = false, needsAttention = true)
}
