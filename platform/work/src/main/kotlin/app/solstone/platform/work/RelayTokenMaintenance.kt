// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.work

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
    data object ReconnectNeeded : RelayTokenResult
}

fun interface RelayDial {
    fun dial(transport: SyncTransport.Relay): SyncOutcome
}

fun maintainRelayToken(
    identity: PairedHome,
    transport: SyncTransport.Relay,
    poster: HttpsPoster,
    mutator: IdentityMutator,
    nowEpochMs: Long,
): RelayTokenResult {
    if (!shouldRefreshDeviceToken(transport.deviceToken, nowEpochMs)) {
        return RelayTokenResult.Ready(transport)
    }
    return when (val refresh = refreshDeviceToken(transport.deviceToken, transport.relayOrigin, poster)) {
        is DeviceTokenRefresh.Refreshed -> {
            val pairing = PairingGeneration(identity.instanceId, identity.clientCertFingerprint)
            val accessGen = mutator.currentAccessMutationGen()
            val mutateResult = mutator.mutate(pairing, accessGen) { current ->
                current.copy(deviceToken = refresh.deviceToken, expiresAt = refresh.expiresAt)
            }
            if (mutateResult is AccessMutationResult.Applied || mutateResult is AccessMutationResult.DurabilityUncertain) {
                RelayTokenResult.Ready(transport.copy(deviceToken = refresh.deviceToken))
            } else {
                RelayTokenResult.Ready(transport)
            }
        }
        DeviceTokenRefresh.ReconnectNeeded -> RelayTokenResult.ReconnectNeeded
        DeviceTokenRefresh.TransientError -> RelayTokenResult.Ready(transport)
    }
}

fun dialWithReactiveRefresh(
    identity: PairedHome,
    transport: SyncTransport.Relay,
    poster: HttpsPoster,
    mutator: IdentityMutator,
    dial: RelayDial,
    log: (String, Throwable?) -> Unit = { _, _ -> },
): SyncOutcome =
    try {
        dial.dial(transport)
    } catch (e: RelayWebSocketClosedException) {
        log("relay websocket closed", e)
        if (e.code != 4401) {
            SyncOutcome.RETRY
        } else {
            when (val refresh = refreshDeviceToken(transport.deviceToken, transport.relayOrigin, poster)) {
                is DeviceTokenRefresh.Refreshed -> {
                    val pairing = PairingGeneration(identity.instanceId, identity.clientCertFingerprint)
                    val accessGen = mutator.currentAccessMutationGen()
                    val mutateResult = mutator.mutate(pairing, accessGen) { current ->
                        current.copy(deviceToken = refresh.deviceToken, expiresAt = refresh.expiresAt)
                    }
                    val tokenToDial = if (mutateResult is AccessMutationResult.Applied || mutateResult is AccessMutationResult.DurabilityUncertain) {
                        refresh.deviceToken
                    } else {
                        transport.deviceToken
                    }
                    try {
                        dial.dial(transport.copy(deviceToken = tokenToDial))
                    } catch (retryClose: RelayWebSocketClosedException) {
                        log("relay websocket closed after token refresh", retryClose)
                        SyncOutcome.RETRY
                    }
                }
                DeviceTokenRefresh.ReconnectNeeded -> SyncOutcome.FAILURE
                DeviceTokenRefresh.TransientError -> SyncOutcome.RETRY
            }
        }
    }
