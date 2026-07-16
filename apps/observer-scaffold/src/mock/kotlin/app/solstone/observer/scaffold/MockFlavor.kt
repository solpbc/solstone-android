// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.scaffold

import android.content.Context
import app.solstone.core.identity.ClientCredential
import app.solstone.core.identity.ClientCredentialStore
import app.solstone.core.identity.IdentityStore
import app.solstone.core.model.IdentityState
import app.solstone.core.model.PairedHome
import app.solstone.core.pl.DirectEndpoint
import app.solstone.core.pl.EndpointStore
import app.solstone.observer.harness.BundleExport
import app.solstone.observer.harness.HarnessController
import app.solstone.observer.harness.HarnessExportResult
import app.solstone.observer.harness.HarnessPairProbeResult
import app.solstone.observer.harness.HarnessPlStatus
import app.solstone.observer.harness.HeartbeatFreshness
import app.solstone.observer.harness.InMemoryDesiredObservingStore
import app.solstone.observer.harness.ObserverLifecycle
import app.solstone.observer.harness.NetworkAvailability
import app.solstone.observer.harness.OpportunisticSync
import app.solstone.observer.harness.PairConnectionMode
import app.solstone.observer.harness.PairProbe
import app.solstone.observer.harness.PlStatusProbe
import app.solstone.observer.harness.RealEvidenceReader
import app.solstone.observer.harness.RelayPairProbe
import app.solstone.observer.harness.SourceRuntimeSnapshot
import app.solstone.observer.harness.SyncEnqueue
import app.solstone.observer.harness.VisibleCaptureAuthority
import app.solstone.platform.camera.still.SingleHolderCameraLock
import app.solstone.platform.fgs.AndroidPermissionStatusReader
import app.solstone.platform.persistence.room.SolstonePersistenceDatabase
import app.solstone.platform.power.OemGuidanceCatalog
import java.nio.file.Path

fun buildObserverFlavor(
    context: Context,
    spec: FormFactorSpec,
    cameraLock: SingleHolderCameraLock,
    lifecycle: ObserverLifecycle,
    sourceSnapshot: () -> SourceRuntimeSnapshot,
    database: SolstonePersistenceDatabase,
    spoolDir: Path,
    visibleCaptureAuthority: VisibleCaptureAuthority,
): SharedObserverFlavor {
    val endpointStore = MemoryEndpointStore()
    val credentialStore = MemoryCredentialStore()
    val identityStore = MemoryIdentityStore()
    val heartbeat = MockHeartbeat()
    val sync = MockSyncEnqueue()
    val evidenceReader = RealEvidenceReader(database.segmentDao())
    val opportunisticSync = OpportunisticSync(evidenceReader, sync, NoopNetworkAvailability)
    val guidance = OemGuidanceCatalog.generic
    return SharedObserverFlavor(
        controller = HarnessController(
            permissionStatusReader = AndroidPermissionStatusReader(context, requireLocation = true),
            desiredObservingStore = InMemoryDesiredObservingStore(),
            cameraLock = cameraLock,
            observerLifecycle = lifecycle,
            heartbeatFreshness = heartbeat,
            pairProbe = PairProbe { _, _ ->
                endpointStore.save(DirectEndpoint("10.0.0.2", 7657))
                credentialStore.save(ClientCredential("private", "cert", listOf("ca")))
                identityStore.save(
                    PairedHome("home", "home", null, "sha256:ca", "sha256:client", spec.deviceLabel, null, null, IdentityState.PAIRED),
                )
                HarnessPairProbeResult(true, 200, 200, "ok", "home", "10.0.0.2", 7657, PairConnectionMode.PAIRING)
            },
            relayPairProbe = RelayPairProbe { _, _ ->
                credentialStore.save(ClientCredential("private", "cert", listOf("ca")))
                identityStore.save(
                    PairedHome(
                        "home",
                        "home",
                        "https://link.solstone.app",
                        "sha256:ca",
                        "sha256:client",
                        spec.deviceLabel,
                        "mock-device-token",
                        null,
                        IdentityState.PAIRED,
                    ),
                )
                HarnessPairProbeResult(true, 200, 200, "", "home", "link.solstone.app", 443, PairConnectionMode.PAIRING)
            },
            plStatusProbe = PlStatusProbe {
                val identity = identityStore.load()
                if (credentialStore.load() == null || identity == null || (identity.relayOrigin == null && endpointStore.load() == null)) {
                    HarnessPlStatus.NotPaired
                } else if (heartbeat.fresh) {
                    HarnessPlStatus.Reachable(200)
                } else {
                    HarnessPlStatus.PairedButUnreachable("mock unreachable")
                }
            },
            syncEnqueue = sync,
            evidenceReader = evidenceReader,
            bundleExport = BundleExport {
                HarnessExportResult(
                    sourcePath = spoolDir.resolve(it.day).resolve(it.stream).resolve(it.dirSegment).toString(),
                    destinationPath = context.filesDir.resolve("mock-export/${it.id}").absolutePath,
                    copiedFileCount = it.files.size,
                )
            },
            endpointStore = endpointStore,
            credentialStore = credentialStore,
            identityStore = identityStore,
            sourceSnapshot = sourceSnapshot,
            deviceLabel = spec.deviceLabel,
            visibleCaptureAuthority = visibleCaptureAuthority,
            isUsableNetworkPresent = NoopNetworkAvailability::isUsableNow,
            opportunisticSync = opportunisticSync,
        ),
        heartbeatControl = heartbeat,
        syncControl = sync,
        opportunisticSync = opportunisticSync,
        exemptionVerified = { true },
        batteryGuidance = guidance.batteryExemption,
        launchBatteryGuidance = { launchGuidance(context, guidance.batteryExemption) },
        isUsableNetworkPresent = NoopNetworkAvailability::isUsableNow,
    )
}

class MockHeartbeat : HeartbeatFreshness, HeartbeatControl {
    var fresh: Boolean = true
        private set

    override fun isFresh(): Boolean = fresh

    override fun setFresh(fresh: Boolean) {
        this.fresh = fresh
    }
}

class MockSyncEnqueue : SyncEnqueue, SyncControl {
    override var enqueuePeriodicCalls: Int = 0
        private set

    override var enqueueNowCalls: Int = 0
        private set

    override fun enqueuePeriodic() {
        enqueuePeriodicCalls += 1
    }

    override fun enqueueNow() {
        enqueueNowCalls += 1
    }
}

private object NoopNetworkAvailability : NetworkAvailability {
    override fun start(onUsableNetwork: () -> Unit) = Unit
    override fun stop() = Unit
    override fun isUsableNow(): Boolean = true
}

private class MemoryEndpointStore : EndpointStore {
    private var endpoint: DirectEndpoint? = null
    override fun save(endpoint: DirectEndpoint) {
        this.endpoint = endpoint
    }
    override fun load(): DirectEndpoint? = endpoint
    override fun clear() {
        endpoint = null
    }
}

private class MemoryCredentialStore : ClientCredentialStore {
    private var credential: ClientCredential? = null
    override fun save(credential: ClientCredential) {
        this.credential = credential
    }
    override fun load(): ClientCredential? = credential
    override fun clear() {
        credential = null
    }
}

private class MemoryIdentityStore : IdentityStore {
    private var home: PairedHome? = null
    override fun save(home: PairedHome) {
        this.home = home
    }
    override fun load(): PairedHome? = home
    override fun clear() {
        home = null
    }
}
