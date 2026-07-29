// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.gate

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GateContractV1Test {
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
            "gate_contract_version" to "1",
            "gate_action" to "g4_degraded_probe",
            "gate_run_nonce" to "20260729T120000Z-0123456789abcdef",
            "gate_action_sequence" to "4",
        )
        assertIs<GateInvocationDecision.Run>(GateInvocation.decide(valid))
        assertIs<GateInvocationDecision.Invalid>(GateInvocation.decide(valid + ("action" to "old")))
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
                "protocol_version" to 2,
                "item_count" to 1,
                "semantic_commitments_sha256" to "b".repeat(64),
            ),
        )
    }
}
