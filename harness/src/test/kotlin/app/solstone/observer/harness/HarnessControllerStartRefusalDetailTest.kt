// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.harness

import app.solstone.core.model.ReasonCode
import app.solstone.core.model.SilencedFact
import app.solstone.core.model.SourceState
import app.solstone.core.sources.SourceCondition
import app.solstone.platform.fgs.CaptureForegroundType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HarnessControllerStartRefusalDetailTest {

    @Test
    fun startWithoutVisibleOwnerSetsRefusalAndReportsForegroundStartNotAllowed() {
        val f = fixture(
            visibleCaptureAuthority = VisibleCaptureAuthority { false },
            snapshot = SourceRuntimeSnapshot(
                engineRunning = false,
                providerEmitting = true,
                storageOk = true,
                silenced = SilencedFact.NOT_SILENCED,
                engineStartIssued = false,
            ),
        )
        val audioEngine = FakeSourceEngine(
            conditionValue = SourceCondition(
                desiredOn = true,
                running = false,
                available = true,
                needsAttention = false,
                paused = false,
                silenced = SilencedFact.NOT_SILENCED,
            )
        )
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
        registry.setWish("audio", SourceWish.On)

        val started = f.controller.start()
        assertEquals(false, started)

        assertTrue(f.controller.lastStartRefused)

        val sources = registry.snapshot().sources
        val audioRow = sources.first { it.sourceId == "audio" }
        assertEquals(SourceState.NEEDS_ATTENTION, audioRow.state)
        assertEquals(ReasonCode.FOREGROUND_START_NOT_ALLOWED, audioRow.reason)
    }

    @Test
    fun reconcileWithoutVisibleOwnerSetsRefusalAndReportsForegroundStartNotAllowed() {
        val f = fixture(
            visibleCaptureAuthority = VisibleCaptureAuthority { false },
            snapshot = SourceRuntimeSnapshot(
                engineRunning = false,
                providerEmitting = true,
                storageOk = true,
                silenced = SilencedFact.NOT_SILENCED,
                engineStartIssued = false,
            ),
        )
        val audioEngine = FakeSourceEngine(
            conditionValue = SourceCondition(
                desiredOn = true,
                running = false,
                available = true,
                needsAttention = false,
                paused = false,
                silenced = SilencedFact.NOT_SILENCED,
            )
        )
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
        registry.setWish("audio", SourceWish.On)

        f.controller.ensureObserving()

        assertTrue(f.controller.lastStartRefused)
        val sources = registry.snapshot().sources
        val audioRow = sources.first { it.sourceId == "audio" }
        assertEquals(SourceState.NEEDS_ATTENTION, audioRow.state)
        assertEquals(ReasonCode.FOREGROUND_START_NOT_ALLOWED, audioRow.reason)
    }

    @Test
    fun controllerStopClearsStartRefused() {
        val f = fixture(
            visibleCaptureAuthority = VisibleCaptureAuthority { false },
        )
        f.controller.recordStartRefusal()
        assertTrue(f.controller.lastStartRefused)

        f.controller.stop()
        assertEquals(false, f.controller.lastStartRefused)
    }
}
