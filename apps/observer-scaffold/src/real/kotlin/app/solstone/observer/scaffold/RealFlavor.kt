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
import app.solstone.platform.fgs.captureForegroundTypesFromTokens
import app.solstone.platform.persistence.room.SolstonePersistenceDatabase
import app.solstone.platform.work.DialDiagnostics
import app.solstone.platform.work.currentPhoneDeviceDescription
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
    pendingObsidian: () -> Int = { 0 },
): SharedObserverFlavor {
    val stores = syncStores(context)
    val external = (context.getExternalFilesDir(null) ?: context.filesDir.resolve("exports-external")).toPath()
    val evidenceReader = RealEvidenceReader(
        dao = database.segmentDao(),
        pendingImports = {
            val identity = stores.identityStore.load()
            val version = stores.journalVersionStore.load()?.version
            app.solstone.platform.persistence.room.countWaitingImports(
                rows = database.importDao().all(),
                instance = identity?.instanceId,
                journalVersion = version,
            )
        },
    )
    val syncEnqueue = RealSyncEnqueue(context, spec.stream)
    val networkAvailability = AndroidNetworkAvailability(context)
    val pairProbe = RealPairProbe(
        credentialStore = stores.credentialStore,
        identityStore = stores.identityStore,
        endpointStore = stores.endpointStore,
        journalVersionStore = stores.journalVersionStore,
        coordinator = stores.journalVersionCoordinator,
        mutator = stores.identityMutator,
        relayAccessCoordinator = stores.relayAccessCoordinator,
        journalMarkStore = stores.journalMarkStore,
        journalIdentityCoordinator = stores.journalIdentityCoordinator,
        publisher = stores.publisher,
        dialEvents = DialDiagnostics.events,
        confirmation = stores.journalConfirmationStore,
    )
    val relayPairProbe = RealRelayPairProbe(
        credentialStore = stores.credentialStore,
        identityStore = stores.identityStore,
        journalVersionStore = stores.journalVersionStore,
        coordinator = stores.journalVersionCoordinator,
        mutator = stores.identityMutator,
        relayAccessCoordinator = stores.relayAccessCoordinator,
        endpointStore = stores.endpointStore,
        journalMarkStore = stores.journalMarkStore,
        journalIdentityCoordinator = stores.journalIdentityCoordinator,
        publisher = stores.publisher,
        confirmation = stores.journalConfirmationStore,
    )
    val reconcileExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "solstone-import-reconcile").apply { isDaemon = true }
    }
    val importStoreForProbe = app.solstone.platform.persistence.room.ImportCustodyStore(
        app.solstone.platform.work.importStoreRoot(context).toPath(),
        database.importDao(),
    )
    val plStatusProbe = RealPlStatusProbe(
        endpointStore = stores.endpointStore,
        credentialStore = stores.credentialStore,
        identityStore = stores.identityStore,
        coordinator = stores.journalVersionCoordinator,
        relayAccessCoordinator = stores.relayAccessCoordinator,
        mutator = stores.identityMutator,
        localDescriptionProvider = { currentPhoneDeviceDescription(context) },
        dialEvents = DialDiagnostics.events,
        publisher = stores.publisher,
        handshakeGuard = stores.handshakeGuard,
        onReconcileImports = { identity, openClient ->
            val snapshotPairing = app.solstone.core.identity.PairingGeneration(
                identity.instanceId,
                identity.clientCertFingerprint,
            )
            reconcileExecutor.execute {
                val confirmed = app.solstone.core.identity.confirmedFor(
                    app.solstone.core.identity.JournalConfirmationPolicy.consults,
                    stores.journalConfirmationStore,
                    identity.clientCertFingerprint,
                )
                val refused = stores.pairingRefusalStore?.refusedFor(snapshotPairing) == true
                if (!confirmed || refused) return@execute
                importStoreForProbe.resetReviewItemsForInstanceChange(identity.instanceId)
                importStoreForProbe.resetUploadSessionsForInstanceChange(identity.instanceId)
                if (importStoreForProbe.reviewCandidates(identity.instanceId).isEmpty()) return@execute
                runCatching {
                    val client = openClient()
                    try {
                        val outcome = app.solstone.platform.work.reconcileReadyToReviewImports(
                            http = client,
                            store = importStoreForProbe,
                            instanceId = identity.instanceId,
                            pairingCurrent = { stores.identityMutator.currentPairingGeneration() == snapshotPairing },
                        )
                        if (outcome == app.solstone.platform.work.ImportReconcileOutcome.AUTH_STOP) {
                            stores.pairingRefusalStore?.markRefused(snapshotPairing)
                        }
                    } finally {
                        (client as? java.io.Closeable)?.close()
                    }
                }
            }
        },
    )
    val opportunisticSync = OpportunisticSync(
        evidenceReader = evidenceReader,
        syncEnqueue = syncEnqueue,
        networkAvailability = networkAvailability,
        emptySpoolRecovery = { plStatusProbe.probe() },
        onNetworkRecovery = { importStoreForProbe.clearRetrySchedule() },
    )
    val controller = HarnessController(
        permissionStatusReader = AndroidPermissionStatusReader(context),
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
        currentDeviceLabel = spec.currentDeviceLabel,
        visibleCaptureAuthority = visibleCaptureAuthority,
        isUsableNetworkPresent = networkAvailability::isUsableNow,
        opportunisticSync = opportunisticSync,
        identityMutator = stores.identityMutator,
        publisher = stores.publisher,
        declaredCaptureForegroundTypes = captureForegroundTypesFromTokens(spec.declaredCaptureForegroundTypes),
        hasNetwork = networkAvailability::hasNetwork,
        screenOn = { context.getSystemService(android.os.PowerManager::class.java)?.isInteractive == true },
    )
    val backlogStatus = RealBacklogStatusReader(
        dao = database.segmentDao(),
        plStatus = controller::currentPlStatus,
        identityStore = stores.identityStore,
        coordinator = stores.journalVersionCoordinator,
        imports = realImportBacklog(context, database, stores, controller::pairingFact),
        calendarCapability = { instanceId, caFingerprint ->
            database.calendarDao().capability(instanceId, caFingerprint)?.calendarNotes
        },
        pendingCalendarImports = { database.calendarDao().pendingImportCount() },
        pendingObsidian = pendingObsidian,
        present = controller::presentConnection,
    )
    return SharedObserverFlavor(
        controller = controller,
        opportunisticSync = opportunisticSync,
        backlogStatus = backlogStatus,
        journalConfirmation = stores.journalConfirmationStore,
    )
}

/** What the app shows about owner-shared imports, read from the import store and the pairing. */
internal fun realImportBacklog(
    context: Context,
    database: SolstonePersistenceDatabase,
    stores: app.solstone.platform.work.SyncStores,
    pairingFact: () -> app.solstone.core.diagnostics.PairingFact,
): () -> app.solstone.observer.harness.HarnessImportBacklog {
    val store = app.solstone.platform.persistence.room.ImportCustodyStore(
        app.solstone.platform.work.importStoreRoot(context).toPath(),
        database.importDao(),
    )
    return {
        val identity = stores.identityStore.load()
        val record = stores.journalVersionStore.load()
        val gate = if (identity == null) {
            app.solstone.platform.work.ImportVersionGate.UNKNOWN
        } else {
            app.solstone.platform.work.importVersionGate(record, identity.instanceId, identity.caChainFingerprint)
        }
        val items = store.items(identity?.instanceId, record?.version)
        val refused = identity != null && (stores.pairingRefusalStore?.refusedFor(
            app.solstone.core.identity.PairingGeneration(identity.instanceId, identity.clientCertFingerprint),
        ) ?: false)
        // An item another journal refused is going again here, so it counts as waiting and can
        // still shrink; only attention this journal gave, or a missing copy, needs the owner. A
        // journal too old to take imports holds every waiting one until the owner updates it, so
        // those can't shrink on their own either and never read as syncing.
        val elsewhere = app.solstone.platform.persistence.room.ImportAttentionScope.ELSEWHERE
        val movingItems = items.filter { it.state == app.solstone.platform.persistence.room.IMPORT_STATE_PENDING || it.scope == elsewhere }
        val reviewCount = items.count { it.state == app.solstone.platform.persistence.room.IMPORT_STATE_READY_TO_REVIEW }
        val needOwner = items.count {
            (it.state == app.solstone.platform.persistence.room.IMPORT_STATE_ATTENTION && it.scope != elsewhere) ||
                it.state == app.solstone.platform.persistence.room.IMPORT_STATE_REVIEW_EXPIRED
        }
        val journalVersion = record?.version.orEmpty()
        val captionHeldCount = if (gate == app.solstone.platform.work.ImportVersionGate.SUPPORTED &&
            !app.solstone.platform.work.journalSupportsImportCaptions(journalVersion)
        ) {
            movingItems.count { it.hasCaption && !it.hasSavedProgress }
        } else {
            0
        }
        val effectiveGate = app.solstone.platform.work.effectiveImportGate(gate, journalVersion, movingItems.size, captionHeldCount)
        val heldCount = if (gate == app.solstone.platform.work.ImportVersionGate.TOO_OLD) movingItems.size else captionHeldCount
        app.solstone.observer.harness.HarnessImportBacklog(
            pendingCount = movingItems.size - heldCount,
            attentionCount = needOwner + heldCount,
            pairingIncomplete = pairingFact() == app.solstone.core.diagnostics.PairingFact.INCOMPLETE,
            refused = refused,
            gate = effectiveGate,
            sending = items.any { it.inFlight },
            lastDelivery = store.lastDelivery(),
            currentInstanceId = identity?.instanceId,
            reviewCount = reviewCount,
        )
    }
}
