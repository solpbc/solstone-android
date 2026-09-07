// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.harness

import app.solstone.core.model.ReasonCode
import app.solstone.core.model.SilencedFact
import app.solstone.core.model.SourceState
import app.solstone.core.sources.SourceCondition
import app.solstone.platform.fgs.CaptureForegroundType
import app.solstone.platform.fgs.ObserverForegroundService
import org.junit.After
import kotlin.test.Test
import kotlin.test.assertEquals

class HeldCaptureForegroundTypeTest {
    @After
    fun tearDown() {
        ObserverForegroundService.heldCaptureForegroundTypes = null
    }

    @Test
    fun sourceIsOnWhenServiceNotHoldingTypes() {
        ObserverForegroundService.heldCaptureForegroundTypes = null
        val f = fixture(snapshot = testSnapshot())
        f.desiredStore.setDesiredOn(true)
        val audioEngine = FakeSourceEngine(conditionValue = runningCondition())
        val registry = sourceRegistry(
            f = f,
            registrations = listOf(
                SourceRegistration(
                    sourceId = "audio",
                    engine = audioEngine,
                    requiredPermissionsGranted = { it.microphoneGranted },
                    captureForegroundType = CaptureForegroundType.MICROPHONE,
                ),
            ),
        )

        val sources = registry.snapshot().sources
        val audioRow = sources.first { it.sourceId == "audio" }
        assertEquals(SourceState.ON, audioRow.state)
        assertEquals(ReasonCode.NONE, audioRow.reason)
    }

    @Test
    fun sourceIsOnWhenSourceTypeIsInHeldSet() {
        ObserverForegroundService.heldCaptureForegroundTypes = setOf(CaptureForegroundType.MICROPHONE)
        val f = fixture(snapshot = testSnapshot())
        f.desiredStore.setDesiredOn(true)
        val audioEngine = FakeSourceEngine(conditionValue = runningCondition())
        val registry = sourceRegistry(
            f = f,
            registrations = listOf(
                SourceRegistration(
                    sourceId = "audio",
                    engine = audioEngine,
                    requiredPermissionsGranted = { it.microphoneGranted },
                    captureForegroundType = CaptureForegroundType.MICROPHONE,
                ),
            ),
        )

        val sources = registry.snapshot().sources
        val audioRow = sources.first { it.sourceId == "audio" }
        assertEquals(SourceState.ON, audioRow.state)
        assertEquals(ReasonCode.NONE, audioRow.reason)
    }

    @Test
    fun sourceNeedsAttentionForegroundTypeNotHeldWhenSourceTypeNotInHeldSetAndPermissionGranted() {
        ObserverForegroundService.heldCaptureForegroundTypes = setOf(CaptureForegroundType.MICROPHONE)
        val f = fixture(snapshot = testSnapshot())
        f.desiredStore.setDesiredOn(true)
        val locationEngine = FakeSourceEngine(conditionValue = runningCondition())
        val registry = sourceRegistry(
            f = f,
            registrations = listOf(
                SourceRegistration(
                    sourceId = "location",
                    engine = locationEngine,
                    requiredPermissionsGranted = { it.locationGranted },
                    captureForegroundType = CaptureForegroundType.LOCATION,
                ),
            ),
        )

        val sources = registry.snapshot().sources
        val locationRow = sources.first { it.sourceId == "location" }
        assertEquals(SourceState.NEEDS_ATTENTION, locationRow.state)
        assertEquals(ReasonCode.FOREGROUND_TYPE_NOT_HELD, locationRow.reason)
    }

    @Test
    fun permissionRevokedTakesPrecedenceOverForegroundTypeNotHeld() {
        ObserverForegroundService.heldCaptureForegroundTypes = setOf(CaptureForegroundType.MICROPHONE)
        val f = fixture(
            permissionStatus = grantedPermissions().copy(locationGranted = false),
            snapshot = testSnapshot(),
        )
        f.desiredStore.setDesiredOn(true)
        val locationEngine = FakeSourceEngine(conditionValue = runningCondition())
        val registry = sourceRegistry(
            f = f,
            registrations = listOf(
                SourceRegistration(
                    sourceId = "location",
                    engine = locationEngine,
                    requiredPermissionsGranted = { it.locationGranted },
                    captureForegroundType = CaptureForegroundType.LOCATION,
                ),
            ),
        )

        val sources = registry.snapshot().sources
        val locationRow = sources.first { it.sourceId == "location" }
        assertEquals(SourceState.NEEDS_ATTENTION, locationRow.state)
        assertEquals(ReasonCode.PERMISSION_REVOKED, locationRow.reason)
    }

    @Test
    fun sourceWithoutCaptureForegroundTypeIsNotBlockedByHeldTypes() {
        ObserverForegroundService.heldCaptureForegroundTypes = setOf(CaptureForegroundType.MICROPHONE)
        val f = fixture(snapshot = testSnapshot())
        f.desiredStore.setDesiredOn(true)
        val metaEngine = FakeSourceEngine(conditionValue = runningCondition())
        val registry = sourceRegistry(
            f = f,
            registrations = listOf(
                SourceRegistration(
                    sourceId = "metadata",
                    engine = metaEngine,
                    captureForegroundType = null,
                ),
            ),
        )

        val sources = registry.snapshot().sources
        val metaRow = sources.first { it.sourceId == "metadata" }
        assertEquals(SourceState.ON, metaRow.state)
        assertEquals(ReasonCode.NONE, metaRow.reason)
    }

    private fun testSnapshot(storageOk: Boolean = true) =
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
}
