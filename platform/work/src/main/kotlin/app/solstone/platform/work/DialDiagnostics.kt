// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.work

import app.solstone.core.pl.DialEventLog
import app.solstone.core.pl.DialOutcome
import app.solstone.core.pl.DirectEndpoint
import app.solstone.core.pl.parseProductionRelayOrigin

/**
 * Where dial events go so the owner can see which addresses the app tried.
 *
 * The app process installs [sink]; this module owns no diagnostics sink of its own, the same
 * shape as [SyncWorker.syncDiag]. One [events] log is shared by every caller in the process, so
 * "only log a success when it is a change" holds across sync and the connection check.
 */
object DialDiagnostics {
    @Volatile
    @JvmStatic
    var sink: ((String) -> Unit)? = null

    val events: DialEventLog = DialEventLog { line -> sink?.invoke(line) }
}

/**
 * The address this transport dials, read from its explicit endpoint fields only: a direct
 * endpoint's host and port, or the relay origin's host and port. Never the relay's path, token or
 * instance ID. Null when the relay origin is not one the transport would dial.
 */
fun SyncTransport.dialAddress(): DirectEndpoint? =
    when (this) {
        is SyncTransport.Direct -> endpoint
        is SyncTransport.Relay -> parseProductionRelayOrigin(relayOrigin)?.let { DirectEndpoint(it.host, it.effectivePort) }
    }

/** Runs [open] for [transport], recording the dial's outcome in [log]. Rethrows any failure. */
fun <T> recordDial(log: DialEventLog?, transport: SyncTransport, open: () -> T): T {
    val address = if (log == null) null else transport.dialAddress()
    val opened = try {
        open()
    } catch (e: Throwable) {
        if (address != null) log?.recordFailure(address.host, address.port, e)
        throw e
    }
    if (address != null) log?.record(address.host, address.port, DialOutcome.CONNECTED)
    return opened
}
