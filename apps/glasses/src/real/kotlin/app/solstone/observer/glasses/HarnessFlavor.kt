// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.glasses

import android.content.Context
import app.solstone.core.sources.GLASSES_STREAM
import app.solstone.observer.harness.AndroidNetworkAvailability
import app.solstone.observer.harness.HarnessController
import app.solstone.observer.harness.OpportunisticSync
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
import app.solstone.platform.fgs.AndroidForegroundStartAllowed
import app.solstone.platform.fgs.AndroidPermissionStatusReader
import app.solstone.platform.persistence.room.SolstonePersistenceDatabase
import app.solstone.platform.power.AndroidDeviceFingerprintProvider
import app.solstone.platform.power.AndroidBatteryExemptionStatus
import app.solstone.platform.power.ExemptionVerifier
import app.solstone.platform.power.OemGuidanceCatalog
import app.solstone.platform.power.SharedPreferencesAutostartConfirmationStore
import app.solstone.platform.work.syncStores
import java.nio.file.Path

fun createGlassesHarnessFlavor(
    context: Context,
    cameraLock: SingleHolderCameraLock,
    lifecycle: app.solstone.observer.harness.ObserverLifecycle,
    sourceSnapshot: () -> SourceRuntimeSnapshot,
    database: SolstonePersistenceDatabase,
    spoolDir: Path,
    visibleCaptureAuthority: VisibleCaptureAuthority,
): GlassesHarnessFlavor {
    val stores = syncStores(context)
    val external = (context.getExternalFilesDir(null) ?: context.filesDir.resolve("exports-external")).toPath()
    val guidance = OemGuidanceCatalog.select(AndroidDeviceFingerprintProvider().fingerprint())
    val verifier = ExemptionVerifier(
        batteryExemptionStatus = AndroidBatteryExemptionStatus(context),
        autostartConfirmationStore = SharedPreferencesAutostartConfirmationStore(context),
        autostartRequired = guidance.autostartAvailable,
    )
    val evidenceReader = RealEvidenceReader(database.segmentDao())
    val syncEnqueue = RealSyncEnqueue(context, GLASSES_STREAM)
    val packaged = RealAudioFeedback(context)
    val networkAvailability = AndroidNetworkAvailability(context)
    val opportunisticSync = OpportunisticSync(
        evidenceReader = evidenceReader,
        syncEnqueue = syncEnqueue,
        networkAvailability = networkAvailability,
    )
    return GlassesHarnessFlavor(
        controller = HarnessController(
            permissionStatusReader = AndroidPermissionStatusReader(context, requireLocation = false),
            desiredObservingStore = SharedPreferencesDesiredObservingStore(context),
            foregroundStartAllowed = AndroidForegroundStartAllowed(),
            cameraLock = cameraLock,
            observerLifecycle = lifecycle,
            heartbeatFreshness = RealHeartbeatFreshness(),
            pairProbe = RealPairProbe(stores.credentialStore, stores.identityStore, stores.endpointStore),
            relayPairProbe = RealRelayPairProbe(stores.credentialStore, stores.identityStore),
            plStatusProbe = RealPlStatusProbe(stores.endpointStore, stores.credentialStore, stores.identityStore),
            syncEnqueue = syncEnqueue,
            evidenceReader = evidenceReader,
            bundleExport = RealBundleExport(spoolDir, external),
            endpointStore = stores.endpointStore,
            credentialStore = stores.credentialStore,
            identityStore = stores.identityStore,
            sourceSnapshot = sourceSnapshot,
            deviceLabel = "solstone glasses",
            visibleCaptureAuthority = visibleCaptureAuthority,
            opportunisticSync = opportunisticSync,
            diag = { GlassesDiagLog.appendRaw(it) },
        ),
        audioFeedback = if (guidance.id == "rokid") {
            RokidTtsAudioFeedback(AndroidRokidTtsSpeaker(context), packaged)
        } else {
            packaged
        },
        oemGuidance = guidance,
        exemptionVerified = verifier::isExemptionVerified,
        isUsableNetworkPresent = networkAvailability::isUsableNow,
    )
}
