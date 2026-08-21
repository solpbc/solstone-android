// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.gate

const val MAX_RESULT_BYTES = 64 * 1024
const val MAX_DIAGNOSTICS = 8

enum class GateOutcome { PASS, FAIL, ERROR }
enum class PlCheckpointKind(val wireName: String) {
    NOT_PAIRED("NotPaired"),
    PAIRED_UNREACHABLE("PairedButUnreachable"),
    REACHABLE("Reachable"),
}

data class GateHttpResult(
    val probeId: String,
    val attempted: Boolean,
    val completed: Boolean,
    val status: Int?,
    val productionPath: String = "/app/network/api/status",
)

data class GateCheckpoint(
    val checkpoint: String,
    val probeId: String,
    val variant: PlCheckpointKind,
    val reason: String?,
    val status: Int?,
    val httpResult: GateHttpResult?,
)

data class GateDiagnostic(val errorType: String, val stage: String)

data class GateResult(
    val schemaVersion: Int = SPL_GATE_RESULT_SCHEMA_VERSION,
    val driverContractVersion: Int = SPL_GATE_DRIVER_CONTRACT_VERSION,
    val runNonce: String,
    val action: GateAction,
    val actionSequence: Int,
    val result: GateOutcome,
    val startedAt: String,
    val finishedAt: String,
    val ownerStatusCheckpoints: List<GateCheckpoint>,
    val productionRelayDialAttempts: Int,
    val callerRetryAttempts: Int,
    val facts: Map<String, Any?>,
    val diagnostics: List<GateDiagnostic> = emptyList(),
)

object GateResultVerifier {
    fun violations(value: GateResult): List<String> = buildList {
        if (value.schemaVersion != 1 || value.driverContractVersion != 1) add("driver_schema_mismatch")
        if (value.actionSequence != value.action.sequence) add("out_of_order_action_result")
        if (value.startedAt > value.finishedAt) add("invalid_timestamps")
        if (value.productionRelayDialAttempts < 0 || value.callerRetryAttempts < 0) add("invalid_counters")
        if (value.diagnostics.size > MAX_DIAGNOSTICS) add("too_many_diagnostics")
        if (value.ownerStatusCheckpoints.map { it.checkpoint } != value.action.requiredCheckpoints) {
            add("invalid_checkpoint_set")
        }
        value.ownerStatusCheckpoints.forEach { checkpoint ->
            val http = checkpoint.httpResult
            if (http != null && (http.probeId != checkpoint.probeId ||
                    http.productionPath != "/app/network/api/status")
            ) {
                add("checkpoint_http_unbound")
            }
            when (checkpoint.variant) {
                PlCheckpointKind.NOT_PAIRED ->
                    if (checkpoint.reason != null || checkpoint.status != null || http != null) add("invalid_not_paired")
                PlCheckpointKind.PAIRED_UNREACHABLE ->
                    if (checkpoint.reason.isNullOrBlank() || checkpoint.status != null ||
                        http == null || !http.attempted || http.completed || http.status != null
                    ) add("invalid_unreachable")
                PlCheckpointKind.REACHABLE ->
                    if (checkpoint.reason != null || checkpoint.status != 200 ||
                        http == null || !http.attempted || !http.completed || http.status != 200
                    ) add("reachable_http_unbound")
            }
        }
        val expectedFactKeys = when (value.action) {
            GateAction.G1_PAIR_ROUND_TRIP ->
                setOf(
                    "pre_pair", "pair", "registration", "authenticated_status", "round_trip", "reconciliation",
                )
            GateAction.G2_LARGE_RESPONSE ->
                setOf(
                    "http_status", "raw_body_bytes", "raw_body_sha256", "parser_succeeded",
                    "protocol_version", "item_count", "semantic_commitments_sha256",
                )
            GateAction.G3_INTERRUPT_RECOVER ->
                setOf(
                    "expected_body_bytes", "expected_body_sha256", "expected_semantics_sha256",
                    "partial_body_bytes", "response_completed_before_cut", "progress_sequence_complete",
                    "cut_after_partial", "interrupted_request", "local_lifecycle", "recovery",
                )
            GateAction.G4_DEGRADED_PROBE, GateAction.G4_RECOVERY_PROBE ->
                setOf("relay_origin", "elapsed_ms")
        }
        if (value.facts.keys != expectedFactKeys) add("invalid_action_facts")
        when (value.action) {
            GateAction.G1_PAIR_ROUND_TRIP -> {
                requireNestedKeys(value.facts, "pre_pair", setOf(
                    "credential_absent", "identity_absent", "endpoint_absent", "observer_handle_absent",
                ), this)
                requireNestedKeys(value.facts, "pair", setOf(
                    "route", "relay_origin", "handshake_pinned", "pair_http_status",
                    "enroll_http_status", "credential_persisted", "paired_identity_persisted",
                    "device_token_persisted",
                ), this)
                requireNestedKeys(value.facts, "registration", setOf(
                    "http_status", "protocol_version", "registered", "handle_persisted",
                ), this)
                requireNestedKeys(value.facts, "round_trip", setOf(
                    "expected_bytes", "actual_bytes", "expected_sha256", "actual_sha256",
                    "ingest_http_status", "parser_succeeded",
                ), this)
                requireNestedKeys(value.facts, "reconciliation", setOf(
                    "server_segment", "server_name", "submitted_name", "matched_name",
                    "size", "sha256", "status",
                ), this)
            }
            GateAction.G3_INTERRUPT_RECOVER -> {
                requireNestedKeys(value.facts, "interrupted_request", setOf(
                    "failed", "error_type", "elapsed_ms",
                ), this)
                requireNestedKeys(value.facts, "local_lifecycle", setOf(
                    "active_streams_at_partial", "active_streams_after", "interrupted_session_closed",
                    "old_relay_session_disappeared", "source", "old_session_id_sha256",
                    "new_session_id_sha256",
                ), this)
                requireNestedKeys(value.facts, "recovery", setOf(
                    "fresh_session_opened", "http_status", "raw_body_bytes", "raw_body_sha256",
                    "semantic_commitments_sha256",
                ), this)
            }
            else -> Unit
        }
    }

    private fun requireNestedKeys(
        facts: Map<String, Any?>,
        key: String,
        expected: Set<String>,
        violations: MutableList<String>,
    ) {
        val nested = facts[key] as? Map<*, *> ?: run {
            violations += "invalid_${key}_facts"
            return
        }
        if (nested.keys != expected) violations += "invalid_${key}_facts"
    }
}
