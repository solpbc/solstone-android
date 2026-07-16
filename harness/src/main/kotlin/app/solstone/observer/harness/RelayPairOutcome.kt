// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.harness

import app.solstone.platform.pl.transport.conscrypt.RelayPairWindowClosedException
import app.solstone.platform.pl.transport.conscrypt.RelayPairWindowUnavailableException
import app.solstone.platform.pl.transport.conscrypt.DirectPairEndpointException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

sealed interface PairAttemptOutcome {
    data class Linked(val result: HarnessPairProbeResult) : PairAttemptOutcome
    data object Retry : PairAttemptOutcome
    data class NetworkUnavailable(
        val failure: ConnectivityFailure,
        val endpointHost: String,
        val endpointPort: Int,
        val route: PairRoute,
    ) : PairAttemptOutcome
    data class WindowClosed(val statusCode: Int) : PairAttemptOutcome
    data class OtherFailure(val exceptionType: String, val statusCode: Int?) : PairAttemptOutcome
}

enum class PairRoute { DIRECT, RELAY }

enum class ConnectivityFailure { DEVICE_OFFLINE, HOST_DID_NOT_ANSWER, NAME_RESOLUTION }

fun classifyPairException(
    e: Throwable,
    endpointHost: String,
    endpointPort: Int,
    route: PairRoute,
    isUsableNetworkPresent: () -> Boolean,
): PairAttemptOutcome {
    if (e is RelayPairWindowClosedException) return PairAttemptOutcome.WindowClosed(401)
    val chain = generateSequence(e) { it.cause }.toList()
    if ((e is IOException && e.message == "WebSocket failed") || chain.any { it.isConnectivityFailure() }) {
        val directFailure = chain.filterIsInstance<DirectPairEndpointException>().firstOrNull()
        val failure = when {
            !isUsableNetworkPresent() -> ConnectivityFailure.DEVICE_OFFLINE
            chain.any { it is UnknownHostException } -> ConnectivityFailure.NAME_RESOLUTION
            else -> ConnectivityFailure.HOST_DID_NOT_ANSWER
        }
        return PairAttemptOutcome.NetworkUnavailable(
            failure = failure,
            endpointHost = directFailure?.endpointHost ?: endpointHost,
            endpointPort = directFailure?.endpointPort ?: endpointPort,
            route = route,
        )
    }
    return PairAttemptOutcome.OtherFailure(
        exceptionType = e.javaClass.simpleName,
        statusCode = chain.filterIsInstance<RelayPairWindowUnavailableException>().firstOrNull()?.statusCode,
    )
}

private fun Throwable.isConnectivityFailure(): Boolean =
    this is UnknownHostException ||
        this is ConnectException ||
        this is SocketException ||
        this is SocketTimeoutException
