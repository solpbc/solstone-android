// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.scaffold

import android.content.Context
import app.solstone.observer.harness.AndroidNetworkAvailability
import app.solstone.observer.harness.HarnessController
import app.solstone.observer.harness.ObserverLifecycle
import app.solstone.observer.harness.OpportunisticSync
import app.solstone.observer.harness.RealBacklogStatusReader
import app.solstone.observer.harness.RealBundleExport
import app.solstone.observer.harness.RealEvidenceReader
import app.solstone.observer.harness.RealHeartbeatFreshness
import app.solstone.observer.harness.RealPairProbe
import app.solstone.observer.harness.RealPlStatusProbe
import app.solstone.observer.harness.RealRelayPairProbe
import app.solstone.observer.harness.RealSyncEnqueue
import app.solstone.observer.harness.SharedPreferencesDesiredObservingStore
import app.solstone.observer.harness.SourceRuntimeSnapshot
import app.solstone.observer.harness.VisibleCaptureAuthority
import app.solstone.platform.camera.still.SingleHolderCameraLock
import app.solstone.platform.fgs.AndroidPermissionStatusReader
import app.solstone.platform.persistence.room.SolstonePersistenceDatabase
import app.solstone.platform.work.syncStores
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
    val stores = syncStores(context)
    val external = (context.getExternalFilesDir(null) ?: context.filesDir.resolve("exports-external")).toPath()
    val evidenceReader = RealEvidenceReader(database.segmentDao())
    val syncEnqueue = RealSyncEnqueue(context, spec.stream)
    val networkAvailability = AndroidNetworkAvailability(context)
    val opportunisticSync = OpportunisticSync(
        evidenceReader = evidenceReader,
        syncEnqueue = syncEnqueue,
        networkAvailability = networkAvailability,
    )
    val pairProbe = RealPairProbe(
        credentialStore = stores.credentialStore,
        identityStore = stores.identityStore,
        endpointStore = stores.endpointStore,
        journalVersionStore = stores.journalVersionStore,
        coordinator = stores.journalVersionCoordinator,
    )
    val relayPairProbe = RealRelayPairProbe(
        credentialStore = stores.credentialStore,
        identityStore = stores.identityStore,
        journalVersionStore = stores.journalVersionStore,
        coordinator = stores.journalVersionCoordinator,
    )
    val plStatusProbe = RealPlStatusProbe(
        endpointStore = stores.endpointStore,
        credentialStore = stores.credentialStore,
        identityStore = stores.identityStore,
        coordinator = stores.journalVersionCoordinator,
    )
    val controller = HarnessController(
        permissionStatusReader = AndroidPermissionStatusReader(context, requireLocation = true),
        desiredObservingStore = SharedPreferencesDesiredObservingStore(context),
        cameraLock = cameraLock,
        observerLifecycle = lifecycle,
        heartbeatFreshness = RealHeartbeatFreshness(),
        pairProbe = pairProbe,
        relayPairProbe = relayPairProbe,
        plStatusProbe = plStatusProbe,
        syncEnqueue = syncEnqueue,
        evidenceReader = evidenceReader,
        bundleExport = RealBundleExport(spoolDir, external),
        endpointStore = stores.endpointStore,
        credentialStore = stores.credentialStore,
        identityStore = stores.identityStore,
        sourceSnapshot = sourceSnapshot,
        deviceLabel = spec.deviceLabel,
        visibleCaptureAuthority = visibleCaptureAuthority,
        isUsableNetworkPresent = networkAvailability::isUsableNow,
        opportunisticSync = opportunisticSync,
    )
    val backlogStatus = RealBacklogStatusReader(
        dao = database.segmentDao(),
        plStatus = controller::probePlStatus,
        identityStore = stores.identityStore,
        coordinator = stores.journalVersionCoordinator,
    )
    return SharedObserverFlavor(
        controller = controller,
        opportunisticSync = opportunisticSync,
        backlogStatus = backlogStatus,
    )
}
