// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.harness

import app.solstone.core.diagnostics.PairingFact

sealed interface SyncNowResult {
    data class NotPaired(val fact: PairingFact) : SyncNowResult {
        init {
            require(fact != PairingFact.PAIRED)
        }
    }

    data object Enqueued : SyncNowResult

    data class EnqueueFailed(val reason: String?) : SyncNowResult
}

fun syncNowMessage(result: SyncNowResult): String =
    when (result) {
        is SyncNowResult.NotPaired ->
            if (result.fact == PairingFact.REVOKED) {
                "Pairing revoked. Use \"Scan pair QR\" to link this device again."
            } else {
                "Not paired. Use \"Scan pair QR\" to link this device."
            }
        SyncNowResult.Enqueued -> "Sync enqueued. The outcome appears in Last success / Last failure."
        is SyncNowResult.EnqueueFailed ->
            "Couldn't enqueue sync: ${result.reason?.takeIf(String::isNotBlank) ?: "unknown"}"
    }
