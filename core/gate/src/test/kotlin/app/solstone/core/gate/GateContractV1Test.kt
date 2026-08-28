// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.gate

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GateContractV4Test {
    @Test
    fun actionsAndSequencesAreExact() {
        assertEquals(
            listOf(
                "g1_pair_round_trip" to 1,
                "g2_large_response" to 2,
                "g3_interrupt_recover" to 3,
                "g4_degraded_probe" to 4,
                "g4_recovery_probe" to 5,
            ),
            GateAction.entries.map { it.wireName to it.sequence },
        )
    }

    @Test
    fun absentActionSkipsBeforeOtherArgumentsAreRead() {
        assertIs<GateInvocationDecision.Skip>(GateInvocation.decide(emptyMap()))
    }

    @Test
    fun invocationRequiresExactGateKeys() {
        val valid = mapOf(
            "gate_contract_version" to "4",
            "gate_action" to "g1_pair_round_trip",
            "gate_run_nonce" to "20260729T120000Z-0123456789abcdef",
            "gate_action_sequence" to "1",
        )
        assertIs<GateInvocationDecision.Run>(GateInvocation.decide(valid))
        assertIs<GateInvocationDecision.Invalid>(GateInvocation.decide(valid + ("gate_extra" to "old")))
        assertIs<GateInvocationDecision.Invalid>(
            GateInvocation.decide(valid - "gate_action_sequence"),
        )
        assertIs<GateInvocationDecision.Invalid>(
            GateInvocation.decide(valid + ("gate_action_sequence" to "5")),
        )
    }

    @Test
    fun resultCodecUsesExactSnakeCaseEnvelopeAndStrictCorrelation() {
        val result = resultFixture()
        val bytes = GateResultCodec.encode(result)
        val text = bytes.toString(Charsets.UTF_8)
        assertTrue("\"schema_version\"" in text)
        assertFalse("schemaVersion" in text)
        assertEquals(result, GateResultCodec.decode(bytes))
        assertTrue(
            acceptsGateResult(
                bytes,
                GateAcceptanceExpectation(result.runNonce, result.action, result.actionSequence),
            ),
        )
        assertFalse(
            acceptsGateResult(
                bytes,
                GateAcceptanceExpectation(result.runNonce, result.action, result.actionSequence + 1),
            ),
        )
    }

    @Test
    fun reachableMustBeBoundToSameCompletedProductionProbe() {
        val fixture = resultFixture()
        assertTrue(GateResultVerifier.violations(fixture).isEmpty())
        val checkpoint = fixture.ownerStatusCheckpoints.single()
        val mutated = fixture.copy(
            ownerStatusCheckpoints = listOf(
                checkpoint.copy(httpResult = checkpoint.httpResult!!.copy(probeId = "other")),
            ),
        )
        assertTrue("checkpoint_http_unbound" in GateResultVerifier.violations(mutated))
    }

    @Test
    fun nestedFactGroupsMustBeObjects() {
        val fixture = g1ResultFixture()

        assertTrue(GateResultVerifier.violations(fixture).isEmpty())

        listOf<Any?>(null, "not-an-object").forEach { reconciliation ->
            val malformed = fixture.copy(facts = fixture.facts + ("reconciliation" to reconciliation))
            assertEquals(listOf("invalid_reconciliation_facts"), GateResultVerifier.violations(malformed))
        }
    }

    @Test
    fun g3RequiresTheTransportNeutralCallbackLifecycleShape() {
        val fixture = g3ResultFixture()

        assertTrue(GateResultVerifier.violations(fixture).isEmpty())
        val stale = fixture.copy(
            facts = fixture.facts + (
                "local_lifecycle" to linkedMapOf(
                    "active_streams_at_partial" to 1,
                    "active_streams_after" to 0,
                    "interrupted_session_closed" to true,
                    "old_relay_session_disappeared" to true,
                    "source" to "android_driver_relay_session_lifecycle_v1",
                    "old_session_id_sha256" to "a".repeat(64),
                    "new_session_id_sha256" to "b".repeat(64),
                )
            ),
        )

        assertEquals(listOf("invalid_local_lifecycle_facts"), GateResultVerifier.violations(stale))
    }

    @Test
    fun g4RequiresTheTransportNeutralDialShape() {
        val fixture = g4ResultFixture()

        assertTrue(GateResultVerifier.violations(fixture).isEmpty())
        val stale = fixture.copy(
            facts = linkedMapOf("relay_origin" to "https://link.solstone.app", "elapsed_ms" to 1),
        )

        assertEquals(listOf("invalid_action_facts", "invalid_transport_facts"), GateResultVerifier.violations(stale))
    }

    private fun resultFixture(): GateResult {
        val probe = "20260729T120000Z-0123456789abcdef:g2:1"
        return GateResult(
            runNonce = "20260729T120000Z-0123456789abcdef",
            action = GateAction.G2_LARGE_RESPONSE,
            actionSequence = 2,
            result = GateOutcome.PASS,
            startedAt = "2026-07-29T12:00:00Z",
            finishedAt = "2026-07-29T12:00:01Z",
            ownerStatusCheckpoints = listOf(
                GateCheckpoint(
                    "g2_completed", probe, PlCheckpointKind.REACHABLE, null, 200,
                    GateHttpResult(probe, true, true, 200),
                ),
            ),
            productionRelayDialAttempts = 1,
            callerRetryAttempts = 0,
            facts = linkedMapOf(
                "http_status" to 200,
                "raw_body_bytes" to 1_048_577,
                "raw_body_sha256" to "a".repeat(64),
                "parser_succeeded" to true,
                "protocol_version" to 3,
                "item_count" to 1,
                "semantic_commitments_sha256" to "b".repeat(64),
            ),
        )
    }

    private fun g1ResultFixture(): GateResult {
        val preProbe = "20260729T120000Z-0123456789abcdef:g1:pre"
        val authenticatedProbe = "20260729T120000Z-0123456789abcdef:g1:authenticated"
        val roundTripProbe = "20260729T120000Z-0123456789abcdef:g1:round_trip"
        fun reachable(checkpoint: String, probe: String) = GateCheckpoint(
            checkpoint, probe, PlCheckpointKind.REACHABLE, null, 200,
            GateHttpResult(probe, true, true, 200),
        )
        return GateResult(
            runNonce = "20260729T120000Z-0123456789abcdef",
            action = GateAction.G1_PAIR_ROUND_TRIP,
            actionSequence = 1,
            result = GateOutcome.PASS,
            startedAt = "2026-07-29T12:00:00Z",
            finishedAt = "2026-07-29T12:00:01Z",
            ownerStatusCheckpoints = listOf(
                reachable("g1_pre_pair", preProbe),
                reachable("g1_authenticated", authenticatedProbe),
                reachable("g1_round_trip", roundTripProbe),
            ),
            productionRelayDialAttempts = 1,
            callerRetryAttempts = 0,
            facts = linkedMapOf(
                "pre_pair" to linkedMapOf(
                    "credential_absent" to true, "identity_absent" to true,
                    "endpoint_absent" to true, "observer_handle_absent" to true,
                ),
                "pair" to linkedMapOf(
                    "route" to "RELAY", "relay_origin" to "https://link.solstone.app",
                    "endpoint_host" to null, "endpoint_port" to null,
                    "handshake_pinned" to true, "pair_http_status" to 200, "enroll_http_status" to 200,
                    "credential_persisted" to true, "paired_identity_persisted" to true,
                    "device_token_persisted" to true, "client_cert_cid" to "sha256:" + "a".repeat(64),
                ),
                "authenticated_status" to 200,
                "capture" to linkedMapOf(
                    "visible_activity" to true, "minimum_capture_ms" to 3_000,
                    "local_segment_id" to "local-1", "day" to "20260729", "segment" to "120000_60",
                    "source" to "audio", "name" to "audio.m4a", "media_type" to "audio/mp4",
                    "byte_size" to 3, "sha256" to "a".repeat(64), "queue_state_before_sync" to "SEALED",
                ),
                "round_trip" to linkedMapOf(
                    "sync_enqueued" to true, "sync_work_state" to "SUCCEEDED",
                    "queue_state_after_sync" to "UPLOADED", "actual_bytes" to 3,
                    "actual_sha256" to "a".repeat(64), "segment_fetch_http_status" to 200,
                    "parser_succeeded" to true,
                ),
                "reconciliation" to linkedMapOf(
                    "server_segment" to "segment", "server_name" to "fixture.wav",
                    "submitted_name" to "fixture.wav", "matched_name" to "fixture.wav",
                    "size" to 3, "sha256" to "a".repeat(64), "status" to "present",
                    "source" to "audio", "local_segment_id" to "local-1",
                ),
            ),
        )
    }

    private fun g3ResultFixture(): GateResult {
        val interruptedProbe = "20260729T120000Z-0123456789abcdef:g3:interrupted"
        val recoveredProbe = "20260729T120000Z-0123456789abcdef:g3:recovered"
        return GateResult(
            runNonce = "20260729T120000Z-0123456789abcdef",
            action = GateAction.G3_INTERRUPT_RECOVER,
            actionSequence = 3,
            result = GateOutcome.PASS,
            startedAt = "2026-07-29T12:00:00Z",
            finishedAt = "2026-07-29T12:00:01Z",
            ownerStatusCheckpoints = listOf(
                GateCheckpoint(
                    "g3_interrupted", interruptedProbe, PlCheckpointKind.PAIRED_UNREACHABLE,
                    "network_interrupted", null,
                    GateHttpResult(interruptedProbe, true, false, null),
                ),
                GateCheckpoint(
                    "g3_recovered", recoveredProbe, PlCheckpointKind.REACHABLE, null, 200,
                    GateHttpResult(recoveredProbe, true, true, 200),
                ),
            ),
            productionRelayDialAttempts = 1,
            callerRetryAttempts = 0,
            facts = linkedMapOf(
                "expected_body_bytes" to 1_048_577,
                "expected_body_sha256" to "a".repeat(64),
                "expected_semantics_sha256" to "b".repeat(64),
                "partial_body_bytes" to 1,
                "response_completed_before_cut" to false,
                "progress_sequence_complete" to true,
                "cut_after_partial" to true,
                "interrupted_request" to linkedMapOf(
                    "failed" to true, "error_type" to "transport_interrupted", "elapsed_ms" to 1,
                ),
                "local_lifecycle" to linkedMapOf(
                    "active_streams_at_partial" to 1, "active_streams_after" to 0,
                    "interrupted_streams_opened" to 1, "interrupted_streams_terminated" to 1,
                    "interrupted_streams_failed" to 1, "recovery_streams_opened" to 1,
                    "recovery_streams_terminated" to 1, "recovery_streams_successful" to 1,
                    "source" to "android_driver_transport_stream_lifecycle_v2",
                ),
                "recovery" to linkedMapOf(
                    "fresh_transport_stream_opened" to true, "http_status" to 200,
                    "raw_body_bytes" to 1_048_577, "raw_body_sha256" to "a".repeat(64),
                    "semantic_commitments_sha256" to "b".repeat(64),
                ),
            ),
        )
    }

    private fun g4ResultFixture(): GateResult {
        val probe = "20260729T120000Z-0123456789abcdef:g4:degraded"
        return GateResult(
            runNonce = "20260729T120000Z-0123456789abcdef",
            action = GateAction.G4_DEGRADED_PROBE,
            actionSequence = 4,
            result = GateOutcome.PASS,
            startedAt = "2026-07-29T12:00:00Z",
            finishedAt = "2026-07-29T12:00:01Z",
            ownerStatusCheckpoints = listOf(
                GateCheckpoint(
                    "g4_degraded", probe, PlCheckpointKind.PAIRED_UNREACHABLE,
                    "network_denied", null, GateHttpResult(probe, true, false, null),
                ),
            ),
            productionRelayDialAttempts = 0,
            callerRetryAttempts = 0,
            facts = linkedMapOf(
                "transport" to linkedMapOf(
                    "route" to "DIRECT", "relay_origin" to null,
                    "endpoint_host" to "10.0.0.40", "endpoint_port" to 7657,
                    "direct_dial_attempts" to 1,
                ),
                "elapsed_ms" to 1,
            ),
        )
    }
}
