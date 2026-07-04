// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.glasses

import app.solstone.core.diagnostics.DiagEvent
import app.solstone.core.diagnostics.formatDiagEvent
import app.solstone.core.identity.ClientCredential
import app.solstone.core.identity.ClientCredentialStore
import app.solstone.core.identity.IdentityStore
import app.solstone.core.model.PairedHome
import app.solstone.core.model.ReasonCode
import app.solstone.core.model.SourceState
import app.solstone.core.observer.CapturePipeline
import app.solstone.core.pl.DirectEndpoint
import app.solstone.core.pl.EndpointStore
import app.solstone.core.segment.Segmenter
import app.solstone.core.sources.ContinuousSourceEngine
import app.solstone.core.sources.EmissionSink
import app.solstone.core.sources.SourceCondition
import app.solstone.core.spool.PayloadBytesProvider
import app.solstone.core.spool.SealResult
import app.solstone.core.spool.SealState
import app.solstone.core.spool.SealedSegmentSink
import app.solstone.core.spool.SpoolWriter
import app.solstone.observer.harness.BundleExport
import app.solstone.observer.harness.DesiredObservingStore
import app.solstone.observer.harness.EvidenceReader
import app.solstone.observer.harness.HarnessController
import app.solstone.observer.harness.HarnessEvidenceSegment
import app.solstone.observer.harness.HarnessExportResult
import app.solstone.observer.harness.HarnessPairProbeResult
import app.solstone.observer.harness.HarnessPlStatus
import app.solstone.observer.harness.HarnessSyncState
import app.solstone.observer.harness.HeartbeatFreshness
import app.solstone.observer.harness.ObserverLifecycle
import app.solstone.observer.harness.ObserverStartMode
import app.solstone.observer.harness.PairProbe
import app.solstone.observer.harness.PlStatusProbe
import app.solstone.observer.harness.RelayPairProbe
import app.solstone.observer.harness.SourceRuntimeSnapshot
import app.solstone.observer.harness.SyncEnqueue
import app.solstone.observer.harness.AlwaysVisibleCaptureAuthority
import app.solstone.platform.camera.still.CameraLock
import app.solstone.platform.fgs.ObserverForegroundService
import app.solstone.platform.fgs.PermissionStatus
import app.solstone.platform.fgs.PermissionStatusReader
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GlassesDiagnosticPlumbingTest {
    @Test
    fun fgsLifecycleDispatchReachesConfiguredSink() {
        val lines = mutableListOf<String>()
        ObserverForegroundService.lifecycleDiag = { lines += it }
        try {
            ObserverForegroundService.dispatchLifecycle("fgs phase=start startId=7 flags=0")
        } finally {
            ObserverForegroundService.lifecycleDiag = null
        }

        assertEquals(listOf("fgs phase=start startId=7 flags=0"), lines)
    }

    @Test
    fun appSideMemoryPressureEmitWritesFormattedEvent() {
        val dir = Files.createTempDirectory("glasses-diag-app").toFile()
        val sink = GlassesDiagLog.install(dir)

        GlassesDiagLog.emit(DiagEvent.MemoryPressure.Trim(15))

        assertTrue(sink.readAll().contains("kind=mem trim=15"))
    }

    @Test
    fun pipelineStartAndStopReachStringSeam() {
        val lines = mutableListOf<String>()
        val pipeline = CapturePipeline(
            segmenter = Segmenter(ZoneId.of("UTC")),
            spoolWriter = NoopSpoolWriter(),
            sealedSink = NoopSealedSink(),
            payloadBytes = object : PayloadBytesProvider {
                override fun open(payload: app.solstone.core.segment.SegmentPayload) =
                    ByteArrayInputStream(ByteArray(0))
            },
            engines = listOf(NoopEngine()),
            nowProvider = { 1L },
            tickIntervalMs = 10_000L,
            diag = { lines += it },
        )

        pipeline.start()
        pipeline.stop()

        assertTrue("capture event=start" in lines)
        assertTrue("capture event=stop" in lines)
    }

    @Test
    fun reconcileBlockedEmitsSortedBlockers() {
        val lines = mutableListOf<String>()
        val controller = controller(diag = { lines += it })

        controller.reconcile(ObserverStartMode.Rehydrate)

        assertEquals(
            "reconcile mode=Rehydrate result=blocked blockers=PERMISSION_REVOKED,UNPAIRED",
            lines.single(),
        )
    }

    @Test
    fun memoryPressureFormatterMatchesAppEventGrammar() {
        assertEquals("kind=mem trim=15", formatDiagEvent(DiagEvent.MemoryPressure.Trim(15)))
    }

    private fun controller(diag: (String) -> Unit): HarnessController =
        HarnessController(
            permissionStatusReader = PermissionStatusReader {
                PermissionStatus(
                    microphoneGranted = false,
                    cameraGranted = true,
                    fineLocationGranted = true,
                    coarseLocationGranted = false,
                    backgroundLocationGranted = false,
                    notificationsGranted = true,
                    requireLocation = false,
                )
            },
            desiredObservingStore = MemoryDesiredStore(initial = true),
            cameraLock = NoopCameraLock(),
            observerLifecycle = NoopLifecycle(),
            heartbeatFreshness = HeartbeatFreshness { true },
            pairProbe = PairProbe { _, _ -> HarnessPairProbeResult(true, 200, 200, "ok", "home", "10.0.0.2", 7657) },
            relayPairProbe = RelayPairProbe { _, _ -> HarnessPairProbeResult(true, 200, 200, "ok", "home", "link.solstone.app", 443) },
            plStatusProbe = PlStatusProbe { HarnessPlStatus.NotPaired },
            syncEnqueue = NoopSync(),
            evidenceReader = NoopEvidenceReader(),
            bundleExport = BundleExport { HarnessExportResult("source", "dest", 0) },
            endpointStore = MemoryEndpointStore(),
            credentialStore = MemoryCredentialStore(),
            identityStore = MemoryIdentityStore(),
            sourceSnapshot = {
                SourceRuntimeSnapshot(
                    engineRunning = false,
                    providerEmitting = false,
                    storageOk = true,
                    exemptionVerified = true,
                )
            },
            deviceLabel = "test glasses",
            visibleCaptureAuthority = AlwaysVisibleCaptureAuthority,
            diag = diag,
        )

    private class NoopEngine : ContinuousSourceEngine {
        override fun start(sink: EmissionSink) = Unit
        override fun stop() = Unit
        override fun condition(): SourceCondition =
            SourceCondition(
                desiredOn = true,
                running = false,
                available = true,
                needsAttention = false,
                paused = false,
            )
    }

    private class NoopSpoolWriter : SpoolWriter {
        override fun seal(segment: app.solstone.core.segment.SealedSegment, payloadBytes: PayloadBytesProvider): SealResult =
            SealResult(segment.key.let { app.solstone.core.model.BundleManifest(it, emptyList(), emptyList()) }, null, SealState.SEALED)
    }

    private class NoopSealedSink : SealedSegmentSink {
        override fun persistSealed(
            segment: app.solstone.core.segment.SealedSegment,
            result: SealResult,
            sealedAtEpochMs: Long,
        ) = Unit
    }

    private class MemoryDesiredStore(initial: Boolean) : DesiredObservingStore {
        private var current = initial
        override fun isDesiredOn(): Boolean = current
        override fun setDesiredOn(on: Boolean) {
            current = on
        }
    }

    private class NoopCameraLock : CameraLock {
        override fun tryAcquire(): Boolean = true
        override fun release() = Unit
    }

    private class NoopLifecycle : ObserverLifecycle {
        override fun start() = Unit
        override fun stop() = Unit
    }

    private class NoopSync : SyncEnqueue {
        override fun enqueuePeriodic() = Unit
        override fun enqueueNow() = Unit
    }

    private class NoopEvidenceReader : EvidenceReader {
        override fun listEvidence(): List<HarnessEvidenceSegment> = emptyList()
        override fun pendingCount(): Int = 0
        override fun syncState(): HarnessSyncState = HarnessSyncState(0, null, null)
    }

    private class MemoryEndpointStore : EndpointStore {
        override fun save(endpoint: DirectEndpoint) = Unit
        override fun load(): DirectEndpoint? = null
        override fun clear() = Unit
    }

    private class MemoryCredentialStore : ClientCredentialStore {
        override fun save(credential: ClientCredential) = Unit
        override fun load(): ClientCredential? = null
        override fun clear() = Unit
    }

    private class MemoryIdentityStore : IdentityStore {
        override fun save(home: PairedHome) = Unit
        override fun load(): PairedHome? = null
        override fun clear() = Unit
    }
}
