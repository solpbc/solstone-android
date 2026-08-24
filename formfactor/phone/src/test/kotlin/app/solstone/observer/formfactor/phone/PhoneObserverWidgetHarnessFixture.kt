// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import app.solstone.core.identity.ClientCredential
import app.solstone.core.identity.ClientCredentialStore
import app.solstone.core.identity.IdentityStore
import app.solstone.core.model.IdentityState
import app.solstone.core.model.PairedHome
import app.solstone.core.model.SilencedFact
import app.solstone.core.pl.DirectEndpoint
import app.solstone.core.pl.EndpointStore
import app.solstone.core.sources.ContinuousSourceEngine
import app.solstone.core.sources.EmissionSink
import app.solstone.core.sources.SourceCondition
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
import app.solstone.observer.harness.InMemorySourceWishStore
import app.solstone.observer.harness.MainPoster
import app.solstone.observer.harness.ObserverLifecycle
import app.solstone.observer.harness.PairProbe
import app.solstone.observer.harness.RelayPairProbe
import app.solstone.observer.harness.SourceRegistration
import app.solstone.observer.harness.SourceRegistry
import app.solstone.observer.harness.SourceRuntimeSnapshot
import app.solstone.observer.harness.SourceWish
import app.solstone.observer.harness.SourcesReadModel
import app.solstone.observer.harness.SyncEnqueue
import app.solstone.observer.harness.VisibleCaptureAuthority
import app.solstone.platform.camera.still.CameraLock
import app.solstone.platform.fgs.PermissionStatus
import app.solstone.platform.fgs.PermissionStatusReader

internal class PhoneObserverWidgetHarnessFixture {
    private val audio = MutableSourceEngine()
    private val location = MutableSourceEngine()
    private var sourceSnapshot = SourceRuntimeSnapshot(
        engineRunning = true,
        providerEmitting = true,
        storageOk = true,
        silenced = SilencedFact.NOT_SILENCED,
    )
    private val controller = HarnessController(
        permissionStatusReader = StaticPermissionReader,
        desiredObservingStore = OnDesiredStore,
        cameraLock = NoopCameraLock,
        observerLifecycle = NoopLifecycle,
        heartbeatFreshness = HeartbeatFreshness { true },
        pairProbe = PairProbe { _, _ -> pairedProbeResult },
        relayPairProbe = RelayPairProbe { _, _ -> pairedProbeResult },
        plStatusProbe = { HarnessPlStatus.Reachable(200) },
        syncEnqueue = NoopSyncEnqueue,
        evidenceReader = EmptyEvidenceReader,
        bundleExport = BundleExport { HarnessExportResult("", "", 0) },
        endpointStore = PairedEndpointStore,
        credentialStore = PairedCredentialStore,
        identityStore = PairedIdentityStore,
        sourceSnapshot = { sourceSnapshot },
        deviceLabel = "phone",
        visibleCaptureAuthority = VisibleCaptureAuthority { true },
        isUsableNetworkPresent = { true },
    )
    private val registry = SourceRegistry(
        controller = controller,
        registrations = listOf(
            SourceRegistration(
                sourceId = "audio",
                engine = audio,
                requiredPermissionsGranted = { it.microphoneGranted },
            ),
            SourceRegistration(
                sourceId = "location",
                engine = location,
                requiredPermissionsGranted = { it.fineLocationGranted || it.coarseLocationGranted },
            ),
        ),
        main = MainPoster { task -> task() },
        wishStore = InMemorySourceWishStore(),
    )

    fun snapshot(
        providerFresh: Boolean,
        silenced: SilencedFact,
        audioSilenced: SilencedFact = silenced,
    ): SourcesReadModel {
        sourceSnapshot = sourceSnapshot.copy(providerEmitting = providerFresh, silenced = silenced)
        audio.condition = audio.condition.copy(silenced = audioSilenced)
        return registry.snapshot()
    }

    fun setAudioWish(wish: SourceWish) {
        registry.setWish("audio", wish)
    }

    private class MutableSourceEngine : ContinuousSourceEngine {
        var condition = SourceCondition(
            desiredOn = true,
            running = true,
            available = true,
            needsAttention = false,
            paused = false,
            silenced = SilencedFact.NOT_SILENCED,
        )

        override fun start(sink: EmissionSink) = Unit

        override fun stop() = Unit

        override fun condition(): SourceCondition = condition
    }

    private data object StaticPermissionReader : PermissionStatusReader {
        override fun read(): PermissionStatus = PermissionStatus(
            microphoneGranted = true,
            cameraGranted = true,
            fineLocationGranted = true,
            coarseLocationGranted = false,
            backgroundLocationGranted = false,
            notificationsGranted = true,
        )
    }

    private data object OnDesiredStore : DesiredObservingStore {
        override fun isDesiredOn(): Boolean = true

        override fun setDesiredOn(on: Boolean) = Unit
    }

    private data object NoopCameraLock : CameraLock {
        override fun tryAcquire(): Boolean = true

        override fun release() = Unit
    }

    private data object NoopLifecycle : ObserverLifecycle {
        override fun start() = Unit

        override fun stop() = Unit
    }

    private data object NoopSyncEnqueue : SyncEnqueue {
        override fun enqueuePeriodic() = Unit

        override fun enqueueNow() = Unit
    }

    private data object EmptyEvidenceReader : EvidenceReader {
        override fun listEvidence(): List<HarnessEvidenceSegment> = emptyList()

        override fun pendingCount(): Int = 0

        override fun syncState(): HarnessSyncState = HarnessSyncState(0, null, null)
    }

    private data object PairedEndpointStore : EndpointStore {
        private val endpoint = DirectEndpoint("127.0.0.1", 7657)

        override fun save(endpoint: DirectEndpoint) = Unit

        override fun load(): DirectEndpoint = endpoint

        override fun clear() = Unit
    }

    private data object PairedCredentialStore : ClientCredentialStore {
        private val credential = ClientCredential("private", "cert", listOf("ca"))

        override fun save(credential: ClientCredential) = Unit

        override fun load(): ClientCredential = credential

        override fun clear() = Unit
    }

    private data object PairedIdentityStore : IdentityStore {
        private val home = PairedHome(
            instanceId = "home",
            homeLabel = "home",
            relayOrigin = null,
            caChainFingerprint = "sha256:ca",
            clientCertFingerprint = "sha256:client",
            observerHandle = "phone",
            deviceToken = null,
            expiresAt = null,
            state = IdentityState.PAIRED,
        )

        override fun save(home: PairedHome) = Unit

        override fun load(): PairedHome = home

        override fun clear() = Unit
    }

    private companion object {
        val pairedProbeResult = HarnessPairProbeResult(
            handshakePinned = true,
            pairStatus = 200,
            statusStatus = 200,
            statusBody = "",
            homeLabel = "home",
            endpointHost = "127.0.0.1",
            endpointPort = 7657,
        )
    }
}
