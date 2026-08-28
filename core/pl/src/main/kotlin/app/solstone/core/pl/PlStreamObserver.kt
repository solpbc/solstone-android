// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.pl

interface PlStreamObserver {
    fun onStreamOpened(streamId: Int)

    fun onResponseDataConsumed(streamId: Int, deltaBytes: Int, cumulativeBytes: Int)

    fun onStreamTerminated(streamId: Int, successful: Boolean)
}

fun interface RelayDialObserver {
    fun onRelayDialAttempt(attemptNumber: Int, host: String, port: Int)
}

fun interface DirectDialObserver {
    fun onDirectDialAttempt(host: String, port: Int)
}
