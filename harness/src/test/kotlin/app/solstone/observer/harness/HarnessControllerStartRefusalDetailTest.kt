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

    /**
     * Another app taking the microphone (a screen recording with audio) reads `paused`, which is
     * short of `on`, so the background status poll reaches the start gate — and the visibility check
     * blocks it. With the service already up, that is not a refusal: intake is running.
     */
    @Test
    fun backgroundReconcileWhileServiceLiveDoesNotRecordRefusal() {
        val f = fixture(
            visibleCaptureAuthority = VisibleCaptureAuthority { false },
            desiredStore = FakeDesiredObservingStore(initial = true),
            plStatusProbe = PlStatusProbe { HarnessPlStatus.Reachable(200) },
            snapshot = SourceRuntimeSnapshot(
                engineRunning = true,
                providerEmitting = true,
                storageOk = true,
                silenced = SilencedFact.SILENCED,
            ),
            foregroundServiceLive = { true },
        )
        val registry = sourceRegistry(f = f, registrations = listOf(liveAudio()))

        f.controller.reconcile(ObserverStartMode.Rehydrate)

        assertEquals(false, f.controller.lastStartRefused)
        val audioRow = registry.snapshot().sources.first { it.sourceId == "audio" }
        assertEquals(ReasonCode.NONE, audioRow.reason)
    }

    @Test
    fun refusalRecordedBeforeServiceCameUpClearsOnNextReconcile() {
        var live = false
        val f = fixture(
            visibleCaptureAuthority = VisibleCaptureAuthority { false },
            desiredStore = FakeDesiredObservingStore(initial = true),
            plStatusProbe = PlStatusProbe { HarnessPlStatus.Reachable(200) },
            foregroundServiceLive = { live },
        )
        val registry = sourceRegistry(f = f, registrations = listOf(liveAudio()))
        f.controller.recordStartRefusal()
        f.controller.reconcile(ObserverStartMode.Rehydrate)
        assertTrue(f.controller.lastStartRefused)

        live = true
        f.controller.reconcile(ObserverStartMode.Rehydrate)

        assertEquals(false, f.controller.lastStartRefused)
        val audioRow = registry.snapshot().sources.first { it.sourceId == "audio" }
        assertEquals(SourceState.ON, audioRow.state)
    }

    private fun liveAudio() = SourceRegistration(
        sourceId = "audio",
        engine = FakeSourceEngine(
            conditionValue = SourceCondition(
                desiredOn = true,
                running = true,
                available = true,
                needsAttention = false,
                paused = false,
                silenced = SilencedFact.NOT_SILENCED,
            )
        ),
        requiredPermissionsGranted = { it.microphoneGranted },
        captureForegroundType = CaptureForegroundType.MICROPHONE,
    )

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
