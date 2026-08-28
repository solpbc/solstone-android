// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.gate

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GateContractV2Test {
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
            "gate_contract_version" to "2",
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

        listOf<Any?>(null, "not-an-object").forEach { reconciliation ->
            val malformed = fixture.copy(facts = fixture.facts + ("reconciliation" to reconciliation))
            assertEquals(listOf("invalid_reconciliation_facts"), GateResultVerifier.violations(malformed))
        }
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
                    "handshake_pinned" to true, "pair_http_status" to 200, "enroll_http_status" to 200,
                    "credential_persisted" to true, "paired_identity_persisted" to true,
                    "device_token_persisted" to true,
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
}
