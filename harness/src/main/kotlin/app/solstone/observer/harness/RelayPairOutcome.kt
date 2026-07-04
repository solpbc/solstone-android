// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.harness

import app.solstone.platform.pl.transport.conscrypt.RelayPairWindowClosedException
import app.solstone.platform.pl.transport.conscrypt.RelayPairWindowUnavailableException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

sealed interface PairAttemptOutcome {
    data class Linked(val result: HarnessPairProbeResult) : PairAttemptOutcome
    data object Retry : PairAttemptOutcome
    data object NetworkUnavailable : PairAttemptOutcome
    data class WindowClosed(val statusCode: Int) : PairAttemptOutcome
    data class OtherFailure(val exceptionType: String, val statusCode: Int?) : PairAttemptOutcome
}

fun classifyPairException(e: Throwable): PairAttemptOutcome {
    if (e is RelayPairWindowClosedException) return PairAttemptOutcome.WindowClosed(401)
    val chain = generateSequence(e) { it.cause }.toList()
    if ((e is IOException && e.message == "WebSocket failed") || chain.any { it.isConnectivityFailure() }) {
        return PairAttemptOutcome.NetworkUnavailable
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
