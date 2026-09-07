// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.work

import app.solstone.core.identity.AccessSnapshot
import app.solstone.core.identity.AccessMutationResult
import app.solstone.core.identity.IdentityMutator
import app.solstone.core.identity.PairingGeneration
import app.solstone.core.model.PairedHome
import app.solstone.core.pl.shouldRefreshDeviceToken
import app.solstone.platform.pl.transport.conscrypt.DeviceTokenRefresh
import app.solstone.platform.pl.transport.conscrypt.HttpsPoster
import app.solstone.platform.pl.transport.conscrypt.RelayWebSocketClosedException
import app.solstone.platform.pl.transport.conscrypt.refreshDeviceToken

enum class SyncOutcome { SUCCESS, RETRY, FAILURE }

sealed interface RelayTokenResult {
    data class Ready(val transport: SyncTransport.Relay) : RelayTokenResult
    data object Obsolete : RelayTokenResult
    data object ReconnectNeeded : RelayTokenResult
}

fun interface RelayDial {
    fun dial(transport: SyncTransport.Relay): SyncOutcome
}

internal fun relaySnapshot(
    identity: PairedHome,
    transport: SyncTransport.Relay,
    mutator: IdentityMutator,
): AccessSnapshot? = mutator.accessSnapshot()?.takeIf {
    it.relayLiveEligible &&
        it.pairing == PairingGeneration(identity.instanceId, identity.clientCertFingerprint) &&
        it.home.instanceId == transport.instanceId &&
        it.home.relayOrigin == transport.relayOrigin && it.home.deviceToken == transport.deviceToken
}

fun maintainRelayToken(
    identity: PairedHome,
    transport: SyncTransport.Relay,
    poster: HttpsPoster,
    mutator: IdentityMutator,
    nowEpochMs: Long? = null,
): RelayTokenResult {
    val snapshot = relaySnapshot(identity, transport, mutator) ?: return RelayTokenResult.Obsolete
    if (!shouldRefreshDeviceToken(transport.deviceToken, nowEpochMs ?: System.currentTimeMillis())) return RelayTokenResult.Ready(transport)
    return when (val refresh = refreshDeviceToken(transport.deviceToken, transport.relayOrigin, poster, nowEpochMs = nowEpochMs)) {
        is DeviceTokenRefresh.Refreshed -> {
            val result = mutator.mutateIfCurrent(snapshot) {
                it.copy(deviceToken = refresh.deviceToken, expiresAt = refresh.expiresAt)
            }
            if (result is AccessMutationResult.Applied) {
                RelayTokenResult.Ready(transport.copy(deviceToken = refresh.deviceToken))
            } else {
                RelayTokenResult.Obsolete
            }
        }
        DeviceTokenRefresh.ReconnectNeeded -> if (mutator.accessSnapshot() == snapshot) RelayTokenResult.ReconnectNeeded else RelayTokenResult.Obsolete
        DeviceTokenRefresh.TransientError -> if (mutator.accessSnapshot() == snapshot) RelayTokenResult.Ready(transport) else RelayTokenResult.Obsolete
    }
}

fun dialWithReactiveRefresh(
    identity: PairedHome,
    transport: SyncTransport.Relay,
    poster: HttpsPoster,
    mutator: IdentityMutator,
    dial: RelayDial,
    log: (String, Throwable?) -> Unit = { _, _ -> },
): SyncOutcome {
    val snapshot = relaySnapshot(identity, transport, mutator) ?: return SyncOutcome.RETRY
    return try {
        dial.dial(transport)
    } catch (e: RelayWebSocketClosedException) {
        log("relay websocket closed", e)
        if (e.code != 4401 || mutator.accessSnapshot() != snapshot) return SyncOutcome.RETRY
        when (val refresh = refreshDeviceToken(transport.deviceToken, transport.relayOrigin, poster)) {
            is DeviceTokenRefresh.Refreshed -> {
                val result = mutator.mutateIfCurrent(snapshot) {
                    it.copy(deviceToken = refresh.deviceToken, expiresAt = refresh.expiresAt)
                }
                if (result !is AccessMutationResult.Applied) return SyncOutcome.RETRY
                val refreshedTransport = transport.copy(deviceToken = refresh.deviceToken)
                if (relaySnapshot(identity, refreshedTransport, mutator)?.generation != result.accessMutationGen) return SyncOutcome.RETRY
                try {
                    dial.dial(refreshedTransport)
                } catch (retryClose: RelayWebSocketClosedException) {
                    log("relay websocket closed after token refresh", retryClose)
                    SyncOutcome.RETRY
                }
            }
            DeviceTokenRefresh.ReconnectNeeded -> if (mutator.accessSnapshot() == snapshot) SyncOutcome.FAILURE else SyncOutcome.RETRY
            DeviceTokenRefresh.TransientError -> SyncOutcome.RETRY
        }
    }
}
