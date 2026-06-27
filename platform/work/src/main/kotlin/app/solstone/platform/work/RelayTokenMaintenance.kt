// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.work

import app.solstone.core.identity.IdentityStore
import app.solstone.core.model.PairedHome
import app.solstone.core.pl.shouldRefreshDeviceToken
import app.solstone.platform.pl.transport.conscrypt.DeviceTokenRefresh
import app.solstone.platform.pl.transport.conscrypt.HttpsPoster
import app.solstone.platform.pl.transport.conscrypt.RelayWebSocketClosedException
import app.solstone.platform.pl.transport.conscrypt.refreshDeviceToken

enum class SyncOutcome { SUCCESS, RETRY, FAILURE }

sealed interface RelayTokenResult {
    data class Ready(val transport: SyncTransport.Relay) : RelayTokenResult
    data object ReconnectNeeded : RelayTokenResult
}

fun interface RelayDial {
    fun dial(transport: SyncTransport.Relay): SyncOutcome
}

fun maintainRelayToken(
    identity: PairedHome,
    transport: SyncTransport.Relay,
    poster: HttpsPoster,
    identityStore: IdentityStore,
    nowEpochMs: Long,
): RelayTokenResult {
    if (!shouldRefreshDeviceToken(transport.deviceToken, nowEpochMs)) {
        return RelayTokenResult.Ready(transport)
    }
    return when (val refresh = refreshDeviceToken(transport.deviceToken, transport.relayOrigin, poster)) {
        is DeviceTokenRefresh.Refreshed -> {
            identityStore.save(identity.copy(deviceToken = refresh.deviceToken, expiresAt = refresh.expiresAt))
            RelayTokenResult.Ready(transport.copy(deviceToken = refresh.deviceToken))
        }
        DeviceTokenRefresh.ReconnectNeeded -> RelayTokenResult.ReconnectNeeded
        DeviceTokenRefresh.TransientError -> RelayTokenResult.Ready(transport)
    }
}

fun dialWithReactiveRefresh(
    identity: PairedHome,
    transport: SyncTransport.Relay,
    poster: HttpsPoster,
    identityStore: IdentityStore,
    dial: RelayDial,
): SyncOutcome =
    try {
        dial.dial(transport)
    } catch (e: RelayWebSocketClosedException) {
        if (e.code != 4401) {
            SyncOutcome.RETRY
        } else {
            when (val refresh = refreshDeviceToken(transport.deviceToken, transport.relayOrigin, poster)) {
                is DeviceTokenRefresh.Refreshed -> {
                    identityStore.save(identity.copy(deviceToken = refresh.deviceToken, expiresAt = refresh.expiresAt))
                    try {
                        dial.dial(transport.copy(deviceToken = refresh.deviceToken))
                    } catch (_: RelayWebSocketClosedException) {
                        SyncOutcome.RETRY
                    }
                }
                DeviceTokenRefresh.ReconnectNeeded -> SyncOutcome.FAILURE
                DeviceTokenRefresh.TransientError -> SyncOutcome.RETRY
            }
        }
    }
