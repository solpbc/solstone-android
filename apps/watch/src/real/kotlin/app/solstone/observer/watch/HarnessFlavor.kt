// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.watch

import android.content.Context
import app.solstone.observer.harness.HarnessController
import app.solstone.observer.harness.RealBundleExport
import app.solstone.observer.harness.RealEvidenceReader
import app.solstone.observer.harness.RealHeartbeatFreshness
import app.solstone.observer.harness.RealPairProbe
import app.solstone.observer.harness.RealPlStatusProbe
import app.solstone.observer.harness.RealRelayPairProbe
import app.solstone.observer.harness.RealSyncEnqueue
import app.solstone.observer.harness.SourceRuntimeSnapshot
import app.solstone.platform.camera.still.SingleHolderCameraLock
import app.solstone.platform.fgs.AndroidPermissionStatusReader
import app.solstone.platform.persistence.room.SolstonePersistenceDatabase
import app.solstone.platform.power.AndroidBatteryExemptionStatus
import app.solstone.platform.power.ExemptionVerifier
import app.solstone.platform.power.SharedPreferencesAutostartConfirmationStore
import app.solstone.platform.work.syncStores
import java.nio.file.Path

fun createWatchHarnessFlavor(
    context: Context,
    cameraLock: SingleHolderCameraLock,
    lifecycle: app.solstone.observer.harness.ObserverLifecycle,
    sourceSnapshot: () -> SourceRuntimeSnapshot,
    database: SolstonePersistenceDatabase,
    spoolDir: Path,
): WatchHarnessFlavor {
    val stores = syncStores(context)
    val external = (context.getExternalFilesDir(null) ?: context.filesDir.resolve("exports-external")).toPath()
    val verifier = ExemptionVerifier(
        AndroidBatteryExemptionStatus(context),
        SharedPreferencesAutostartConfirmationStore(context),
    )
    return WatchHarnessFlavor(
        controller = HarnessController(
            permissionStatusReader = AndroidPermissionStatusReader(context, requireLocation = true),
            cameraLock = cameraLock,
            observerLifecycle = lifecycle,
            heartbeatFreshness = RealHeartbeatFreshness(),
            pairProbe = RealPairProbe(stores.credentialStore, stores.identityStore, stores.endpointStore),
            relayPairProbe = RealRelayPairProbe(stores.credentialStore, stores.identityStore),
            plStatusProbe = RealPlStatusProbe(stores.endpointStore, stores.credentialStore, stores.identityStore),
            syncEnqueue = RealSyncEnqueue(context),
            evidenceReader = RealEvidenceReader(database.segmentDao()),
            bundleExport = RealBundleExport(spoolDir, external),
            endpointStore = stores.endpointStore,
            credentialStore = stores.credentialStore,
            identityStore = stores.identityStore,
            sourceSnapshot = sourceSnapshot,
            deviceLabel = "solstone watch",
        ),
        exemptionVerified = verifier::isExemptionVerified,
    )
}
