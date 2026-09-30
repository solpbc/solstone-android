// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone

import android.Manifest
import app.solstone.core.sources.PHONE_STREAM
import app.solstone.observer.formfactor.phone.createPhonePairingMarkView
import app.solstone.observer.formfactor.phone.phoneOwnerButtonStyle
import app.solstone.observer.formfactor.phone.phoneOwnerTextStyle
import app.solstone.observer.formfactor.phone.PairingMismatchResult
import app.solstone.observer.formfactor.shared.QrBackend
import app.solstone.observer.scaffold.FormFactorSpec
import app.solstone.core.identity.GraphMutationResult
import app.solstone.core.identity.PairingGraphSnapshot
import app.solstone.platform.work.JournalRevokeOutcome
import app.solstone.platform.work.confirmCurrentJournal
import app.solstone.platform.work.SyncScheduler
import app.solstone.platform.work.forgetPushAfterCleared
import app.solstone.platform.work.revokeThisDeviceOnJournal
import app.solstone.platform.work.syncStores

import app.solstone.core.identity.PairingLease
import app.solstone.core.pl.DirectEndpoint as PlDirectEndpoint
import app.solstone.platform.pl.transport.conscrypt.openAuthenticatedClient
import app.solstone.platform.pl.transport.conscrypt.openRelaySyncClient

val PHONE_DECLARED_CAPTURE_FOREGROUND_TYPES = setOf("microphone", "location", "camera")

val phoneSpec = FormFactorSpec(
    stream = PHONE_STREAM,
    deviceLabel = "solstone phone",
    handlesPairLinks = true,
    qrBackend = QrBackend.Camera2,
    previewHeightPx = 480,
    ownerButtonStyle = ::phoneOwnerButtonStyle,
    ownerTextStyle = ::phoneOwnerTextStyle,
    declaredCaptureForegroundTypes = PHONE_DECLARED_CAPTURE_FOREGROUND_TYPES,
    matchForegroundTypesToWishes = true,
    permissions = { sdkInt ->
        if (sdkInt >= 33) {
            arrayOf(
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.CAMERA,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.POST_NOTIFICATIONS,
            )
        } else {
            arrayOf(
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.CAMERA,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            )
        }
    },
    pairingAccessoryFactory = { context, onConfirmed ->
        val stores = syncStores(context)
        createPhonePairingMarkView(
            context = context,
            coordinator = stores.journalIdentityCoordinator,
            onConfirmed = onConfirmed,
            currentPairing = { (stores.publisher.currentSnapshot() as? PairingGraphSnapshot.Committed)?.pairing },
            requestMark = {
                val direct = stores.publisher.acquireDirectLease()
                val relay = if (direct == null) stores.publisher.acquireRelayLease() else null
                val instanceId = direct?.snapshot?.home?.instanceId ?: relay?.snapshot?.home?.instanceId
                if (instanceId != null) {
                    stores.journalIdentityCoordinator.onUsableConnection(instanceId) {
                        if (direct != null) {
                            openAuthenticatedClient(PlDirectEndpoint(direct.endpoint.host, direct.endpoint.port), direct.credential)
                        } else {
                            val r = relay as PairingLease.Relay
                            openRelaySyncClient(r.relayOrigin, r.instanceId, r.deviceToken, r.credential)
                        }
                    }
                }
            },
            onMismatch = {
                val revoke = revokeThisDeviceOnJournal(stores.publisher)
                if (stores.publisher.forget() is GraphMutationResult.Cleared) {
                    forgetPushAfterCleared(stores)
                    if (revoke == JournalRevokeOutcome.UNREACHED) {
                        PairingMismatchResult.JournalUnreached
                    } else {
                        PairingMismatchResult.Disconnected
                    }
                } else {
                    PairingMismatchResult.LocalFailure
                }
            },
            onYes = {
                val snapshot = stores.publisher.currentSnapshot()
                val fp = (snapshot as? PairingGraphSnapshot.Committed)?.pairing?.clientCertFingerprint
                if (fp != null && confirmCurrentJournal(stores.publisher, stores.journalConfirmationStore, fp)) {
                    SyncScheduler.enqueueAfterConfirm(context.applicationContext, PHONE_STREAM)
                    true
                } else {
                    false
                }
            },
        )
    },
)
