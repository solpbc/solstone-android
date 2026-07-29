// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.gate

import java.security.MessageDigest

data class GateIdentity(
    val runNonce: String,
    val observerHostname: String,
    val observerDay: String,
    val g1Segment: String,
    val g1Payload: ByteArray,
    val g1PayloadBytes: Int,
    val g1PayloadSha256: String,
    val fixtureNamespaceSha256: String,
) {
    fun matchesG1Commitment(expectedBytes: Int, expectedSha256: String): Boolean =
        g1PayloadBytes == expectedBytes && g1PayloadSha256 == expectedSha256

    override fun equals(other: Any?): Boolean =
        other is GateIdentity &&
            runNonce == other.runNonce &&
            observerHostname == other.observerHostname &&
            observerDay == other.observerDay &&
            g1Segment == other.g1Segment &&
            g1Payload.contentEquals(other.g1Payload) &&
            g1PayloadBytes == other.g1PayloadBytes &&
            g1PayloadSha256 == other.g1PayloadSha256 &&
            fixtureNamespaceSha256 == other.fixtureNamespaceSha256

    override fun hashCode(): Int = 31 * runNonce.hashCode() + g1Payload.contentHashCode()
}

private val GATE_RUN_NONCE = Regex("""[0-9]{8}T[0-9]{6}Z-[0-9a-f]{16}""")

fun deriveGateIdentity(runNonce: String): GateIdentity {
    require(GATE_RUN_NONCE.matches(runNonce)) {
        "run_nonce must match YYYYMMDDTHHMMSSZ-<16 lowercase hex>"
    }
    val day = runNonce.substring(0, 8)
    val hhmmss = runNonce.substring(9, 15)
    val suffix = runNonce.substring(17, 33)
    val segment = "${hhmmss}_${1uL + suffix.toULong(16) % 9999uL}"
    val payload = "solstone android gate g1 run=$runNonce\n".toByteArray(Charsets.US_ASCII)
    val namespace =
        """{"driver_contract_version":1,"run_nonce":"$runNonce"}""".toByteArray(Charsets.UTF_8)
    return GateIdentity(
        runNonce = runNonce,
        observerHostname = "android-gate-$suffix.test",
        observerDay = day,
        g1Segment = segment,
        g1Payload = payload,
        g1PayloadBytes = payload.size,
        g1PayloadSha256 = payload.sha256Hex(),
        fixtureNamespaceSha256 = namespace.sha256Hex(),
    )
}

private fun ByteArray.sha256Hex(): String =
    MessageDigest.getInstance("SHA-256").digest(this).joinToString("") { "%02x".format(it) }
