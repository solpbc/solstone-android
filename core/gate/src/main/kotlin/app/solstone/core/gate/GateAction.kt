// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.gate

const val SPL_GATE_DRIVER_CONTRACT_VERSION = 4
const val SPL_GATE_RESULT_SCHEMA_VERSION = 1

enum class GateAction(
    val wireName: String,
    val sequence: Int,
    val requiredCheckpoints: List<String>,
) {
    G1_PAIR_ROUND_TRIP(
        "g1_pair_round_trip",
        1,
        listOf("g1_pre_pair", "g1_authenticated", "g1_round_trip"),
    ),
    G2_LARGE_RESPONSE(
        "g2_large_response",
        2,
        listOf("g2_completed"),
    ),
    G3_INTERRUPT_RECOVER(
        "g3_interrupt_recover",
        3,
        listOf("g3_interrupted", "g3_recovered"),
    ),
    G4_DEGRADED_PROBE(
        "g4_degraded_probe",
        4,
        listOf("g4_degraded"),
    ),
    G4_RECOVERY_PROBE(
        "g4_recovery_probe",
        5,
        listOf("g4_recovered"),
    ),
    ;

    companion object {
        fun fromWireName(value: String): GateAction? = entries.firstOrNull { it.wireName == value }
    }
}

sealed interface GateInvocationDecision {
    data object Skip : GateInvocationDecision
    data class Run(val invocation: GateInvocation) : GateInvocationDecision
    data class Invalid(val reason: String) : GateInvocationDecision
}

data class GateInvocation(
    val runNonce: String,
    val action: GateAction,
    val actionSequence: Int,
    val observerDay: String?,
    val expectedBodyBytes: Int?,
    val expectedBodySha256: String?,
    val expectedSemanticsSha256: String?,
) {
    companion object {
        private val NONCE = Regex("""\d{8}T\d{6}Z-[0-9a-f]{16}""")
        private val SHA256 = Regex("""[0-9a-f]{64}""")
        private val DAY = Regex("""\d{8}""")
        private val COMMON_KEYS = setOf(
            "gate_contract_version", "gate_action", "gate_run_nonce", "gate_action_sequence",
        )

        fun decide(extras: Map<String, String?>): GateInvocationDecision {
            val actionText = extras["gate_action"]?.takeIf(String::isNotBlank)
                ?: return GateInvocationDecision.Skip
            val action = GateAction.fromWireName(actionText)
                ?: return GateInvocationDecision.Invalid("unknown_action")
            val expectedKeys = COMMON_KEYS + when (action) {
                GateAction.G1_PAIR_ROUND_TRIP -> emptySet()
                GateAction.G2_LARGE_RESPONSE, GateAction.G3_INTERRUPT_RECOVER ->
                    setOf(
                        "gate_observer_day", "gate_expected_body_bytes", "gate_expected_body_sha256",
                        "gate_expected_semantics_sha256",
                    )
                GateAction.G4_DEGRADED_PROBE, GateAction.G4_RECOVERY_PROBE -> emptySet()
            }
            if (extras.filterValues { it != null }.keys != expectedKeys) {
                return GateInvocationDecision.Invalid("unexpected_arguments")
            }
            if (extras["gate_contract_version"] != SPL_GATE_DRIVER_CONTRACT_VERSION.toString()) {
                return GateInvocationDecision.Invalid("driver_contract_mismatch")
            }
            val nonce = extras["gate_run_nonce"].orEmpty()
            if (!NONCE.matches(nonce)) return GateInvocationDecision.Invalid("invalid_run_nonce")
            val sequence = extras["gate_action_sequence"]?.toIntOrNull()
            if (sequence != action.sequence) return GateInvocationDecision.Invalid("out_of_order_action")

            fun positiveInt(key: String): Int? = extras[key]?.toIntOrNull()?.takeIf { it > 0 }
            fun digest(key: String): String? = extras[key]?.takeIf(SHA256::matches)

            val day = extras["gate_observer_day"]
            val bodyBytes = positiveInt("gate_expected_body_bytes")
            val bodySha = digest("gate_expected_body_sha256")
            val semanticSha = digest("gate_expected_semantics_sha256")
            when (action) {
                GateAction.G1_PAIR_ROUND_TRIP -> Unit
                GateAction.G2_LARGE_RESPONSE, GateAction.G3_INTERRUPT_RECOVER ->
                    if (day == null || !DAY.matches(day) || bodyBytes == null || bodySha == null || semanticSha == null) {
                        return GateInvocationDecision.Invalid("invalid_response_commitment")
                    }
                else -> Unit
            }
            return GateInvocationDecision.Run(
                GateInvocation(
                    nonce, action, sequence, day, bodyBytes, bodySha, semanticSha,
                ),
            )
        }
    }
}
