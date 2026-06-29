// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.glasses

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
import app.solstone.observer.harness.PairConnectionMode
import app.solstone.observer.harness.PairProbe
import app.solstone.observer.harness.PlStatusProbe
import app.solstone.observer.harness.RealEvidenceReader
import app.solstone.observer.harness.RelayPairProbe
import app.solstone.observer.harness.SourceRuntimeSnapshot
import app.solstone.observer.harness.SyncEnqueue
import app.solstone.platform.camera.still.SingleHolderCameraLock
import app.solstone.platform.fgs.ForegroundStartAllowed
import app.solstone.platform.fgs.AndroidPermissionStatusReader
import app.solstone.platform.persistence.room.SolstonePersistenceDatabase
import java.nio.file.Path

fun createGlassesHarnessFlavor(
    context: Context,
    cameraLock: SingleHolderCameraLock,
    lifecycle: app.solstone.observer.harness.ObserverLifecycle,
    sourceSnapshot: () -> SourceRuntimeSnapshot,
    database: SolstonePersistenceDatabase,
    spoolDir: Path,
): GlassesHarnessFlavor {
    val endpointStore = MemoryEndpointStore()
    val credentialStore = MemoryCredentialStore()
    val identityStore = MemoryIdentityStore()
    val heartbeat = MockHeartbeat()
    val sync = MockSyncEnqueue()
    return GlassesHarnessFlavor(
        controller = HarnessController(
            permissionStatusReader = AndroidPermissionStatusReader(context, requireLocation = false),
            desiredObservingStore = InMemoryDesiredObservingStore(),
            foregroundStartAllowed = ForegroundStartAllowed { true },
            cameraLock = cameraLock,
            observerLifecycle = lifecycle,
            heartbeatFreshness = heartbeat,
            pairProbe = PairProbe { _, _ ->
                endpointStore.save(DirectEndpoint("10.0.0.2", 7657))
                credentialStore.save(ClientCredential("private", "cert", listOf("ca")))
                identityStore.save(
                    PairedHome("home", "home", null, "sha256:ca", "sha256:client", "glasses", null, null, IdentityState.PAIRED),
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
                        "glasses",
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
            evidenceReader = RealEvidenceReader(database.segmentDao()),
            bundleExport = BundleExport {
                HarnessExportResult(
                    sourcePath = spoolDir.resolve(it.day).resolve(it.stream).resolve(it.segment).toString(),
                    destinationPath = context.filesDir.resolve("mock-export/${it.id}").absolutePath,
                    copiedFileCount = it.files.size,
                )
            },
            endpointStore = endpointStore,
            credentialStore = credentialStore,
            identityStore = identityStore,
            sourceSnapshot = sourceSnapshot,
            deviceLabel = "mock glasses",
        ),
        audioFeedback = FakeAudioFeedback(),
        heartbeatControl = heartbeat,
        syncControl = sync,
        exemptionVerified = { true },
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
