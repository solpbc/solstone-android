// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.gate

private val GATE_RUN_NONCE = Regex("""[0-9]{8}T[0-9]{6}Z-[0-9a-f]{16}""")

fun deriveGateObserverHostname(runNonce: String): String {
    require(GATE_RUN_NONCE.matches(runNonce)) {
        "run_nonce must match YYYYMMDDTHHMMSSZ-<16 lowercase hex>"
    }
    val suffix = runNonce.substring(17, 33)
    return "android-gate-$suffix.test"
}
