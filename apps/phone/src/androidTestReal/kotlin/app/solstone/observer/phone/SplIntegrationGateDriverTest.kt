// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.system.Os
import android.system.OsConstants
import app.solstone.core.crypto.sha256Hex
import app.solstone.core.gate.GateAction
import app.solstone.core.gate.GateCheckpoint
import app.solstone.core.gate.GateDiagnostic
import app.solstone.core.gate.GateInvocation
import app.solstone.core.gate.GateInvocationDecision
import app.solstone.core.gate.GateOutcome
import app.solstone.core.gate.GateResult
import app.solstone.core.gate.GateResultWriter
import app.solstone.core.gate.GateSemanticFile
import app.solstone.core.gate.GateSemanticSegment
import app.solstone.core.gate.G3ProgressOrder
import app.solstone.core.gate.G3ProgressState
import app.solstone.core.gate.PlCheckpointKind
import app.solstone.core.gate.deriveGateIdentity
import app.solstone.core.gate.semanticCommitmentSha256
import app.solstone.core.model.BundleFile
import app.solstone.core.model.BundleManifest
import app.solstone.core.model.SegmentKey
import app.solstone.core.observer.IngestOutcome
import app.solstone.core.observer.OBSERVER_HANDLE_HEADER
import app.solstone.core.observer.OBSERVER_PROTOCOL_VERSION
import app.solstone.core.observer.ObserverIngestClient
import app.solstone.core.observer.ObserverRegistration
import app.solstone.core.observer.PROTOCOL_VERSION_HEADER
import app.solstone.core.observer.SEGMENTS_PATH
import app.solstone.core.observer.SegmentReconciler
import app.solstone.core.pl.HttpResponse
import app.solstone.core.pl.RelayPairLink
import app.solstone.core.pl.parseJson
import app.solstone.core.pl.parsePairLink
import app.solstone.observer.harness.RealPlStatusProbe
import app.solstone.observer.harness.RealRelayPairProbe
import app.solstone.platform.pl.transport.conscrypt.openRelaySyncClient
import app.solstone.platform.work.SyncStores
import app.solstone.platform.work.plStoreDir
import app.solstone.platform.work.syncStores
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.ExecutionException

@RunWith(AndroidJUnit4::class)
class SplIntegrationGateDriverTest {
    @Test
    fun runRequestedGateAction() {
        val decision = GateInvocation.decide(instrumentationExtras())
        if (decision is GateInvocationDecision.Skip) {
            assumeTrue("SPL gate action not supplied", false)
        }
        require(decision is GateInvocationDecision.Run) {
            (decision as GateInvocationDecision.Invalid).reason
        }

        val invocation = decision.invocation
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val startedAt = utcSecond()
        val result = try {
            when (invocation.action) {
                GateAction.G1_PAIR_ROUND_TRIP -> runG1(context, invocation, startedAt)
                GateAction.G2_LARGE_RESPONSE -> runG2(context, invocation, startedAt)
                GateAction.G3_INTERRUPT_RECOVER -> runG3(context, invocation, startedAt)
                GateAction.G4_DEGRADED_PROBE -> runStatusAction(context, invocation, startedAt, degraded = true)
                GateAction.G4_RECOVERY_PROBE -> runStatusAction(context, invocation, startedAt, degraded = false)
            }
        } catch (throwable: Throwable) {
            errorResult(invocation, startedAt, throwable)
        }
        val resultFile = File(context.filesDir, "$GATE_PRIVATE_DIR/$GATE_RESULT_FILE")
        GateResultWriter(resultFile).write(result)
        assertEquals(result.diagnostics.firstOrNull()?.errorType, GateOutcome.PASS, result.result)
    }

    private fun runG1(context: Context, invocation: GateInvocation, startedAt: String): GateResult =
        withPairAuthority(context, invocation) { pairAuthority ->
            val gateIdentity = deriveGateIdentity(invocation.runNonce)
            require(
                gateIdentity.matchesG1Commitment(
                    requireNotNull(invocation.expectedRoundTripBytes),
                    requireNotNull(invocation.expectedRoundTripSha256),
                ),
            ) { "round_trip_commitment_mismatch" }
            val stores = syncStores(context)
            requireEmptyProductionStores(context, stores)
            val preProbeId = probeId(invocation, "pre_pair")
            val pre = checkpointFromProductionStatus(
                "g1_pre_pair", preProbeId, realStatusProbe(stores, GateTelemetry()).probe(),
            )
            require(pre.variant == PlCheckpointKind.NOT_PAIRED) { "prepair_state_not_clean" }

            val link = parsePairLink(pairAuthority)
            require(link is RelayPairLink) { "pair_route_not_relay" }
            val pairTelemetry = GateTelemetry()
            val pair = RealRelayPairProbe(
                stores.credentialStore, stores.identityStore, pairTelemetry, pairTelemetry,
            ).pairOverRelay(link, gateIdentity.observerHostname)
            val identity = requireNotNull(stores.identityStore.load()) { "paired_identity_not_persisted" }
            val credential = requireNotNull(stores.credentialStore.load()) { "credential_not_persisted" }
            require(identity.relayOrigin == "https://link.solstone.app") { "relay_origin_invalid" }
            require(!identity.deviceToken.isNullOrBlank()) { "device_token_not_persisted" }

            val authenticatedTelemetry = GateTelemetry()
            val authenticatedProbeId = probeId(invocation, "authenticated")
            val authenticated = checkpointFromProductionStatus(
                "g1_authenticated",
                authenticatedProbeId,
                realStatusProbe(stores, authenticatedTelemetry).probe(),
            )

            val registrationTelemetry = GateTelemetry()
            val registration = openRelaySyncClient(
                identity.relayOrigin!!, identity.instanceId, identity.deviceToken!!, credential,
                registrationTelemetry, registrationTelemetry,
            ).use { client ->
                ObserverRegistration(client).register(
                    "android", gateIdentity.observerHostname, "phone", "spl-gate-v1",
                )
            }
            stores.identityStore.save(identity.copy(observerHandle = registration.handle))

            val payload = gateIdentity.g1Payload
            val day = gateIdentity.observerDay
            val segment = gateIdentity.g1Segment
            val fileName = "gate-${invocation.runNonce.takeLast(16)}.txt"
            val manifest = BundleManifest(
                SegmentKey(day, segment),
                listOf(
                    BundleFile(
                        "spl-gate", fileName, sha256Hex(payload), payload.size.toLong(), "text/plain",
                        0L, 0L,
                    ),
                ),
                emptyList(),
            )
            val ingestTelemetry = GateTelemetry()
            val ingest = openRelaySyncClient(
                identity.relayOrigin!!, identity.instanceId, identity.deviceToken!!, credential,
                ingestTelemetry, ingestTelemetry,
            ).use { client ->
                ObserverIngestClient(client) { "solstoneAndroidGate${invocation.runNonce.takeLast(16)}" }
                    .ingest(
                        manifest,
                        registration.handle,
                        { payload },
                        gateIdentity.observerHostname,
                        "android",
                    )
            }
            require(ingest !is IngestOutcome.Rejected) { "round_trip_ingest_rejected" }

            val roundTripTelemetry = GateTelemetry()
            val response = requestSegments(stores, registration.handle, day, roundTripTelemetry)
            val parsed = SegmentReconciler(noRequestClient(), registration.handle).parseFetchResponse(response)
            val parserSucceeded = parsed.any { remote ->
                remote.key == when (ingest) {
                    is IngestOutcome.Accepted -> ingest.serverSegment
                    is IngestOutcome.Collision -> ingest.serverSegment
                    is IngestOutcome.Duplicate -> ingest.existingSegment
                    is IngestOutcome.Rejected -> null
                } && remote.files.any { it.sha256 == sha256Hex(payload) }
            }
            val roundTripProbeId = probeId(invocation, "round_trip")
            val roundTripStatusTelemetry = GateTelemetry()
            val roundTrip = checkpointFromProductionStatus(
                "g1_round_trip", roundTripProbeId,
                realStatusProbe(stores, roundTripStatusTelemetry).probe(),
            )
            val totalDials = listOf(
                pairTelemetry, authenticatedTelemetry, registrationTelemetry, ingestTelemetry,
                roundTripTelemetry, roundTripStatusTelemetry,
            ).sumOf { it.snapshot().relayDials }
            result(
                invocation, startedAt, listOf(pre, authenticated, roundTrip), totalDials,
                linkedMapOf(
                    "pre_pair" to linkedMapOf(
                        "credential_absent" to true,
                        "identity_absent" to true,
                        "endpoint_absent" to true,
                        "observer_handle_absent" to true,
                    ),
                    "pair" to linkedMapOf(
                        "route" to "RELAY",
                        "relay_origin" to identity.relayOrigin,
                        "handshake_pinned" to pair.handshakePinned,
                        "pair_http_status" to pair.pairStatus,
                        "enroll_http_status" to pair.statusStatus,
                        "credential_persisted" to true,
                        "paired_identity_persisted" to true,
                        "device_token_persisted" to true,
                    ),
                    "registration" to linkedMapOf(
                        "http_status" to 200,
                        "protocol_version" to registration.protocolVersion,
                        "registered" to true,
                        "handle_persisted" to (stores.identityStore.load()?.observerHandle == registration.handle),
                    ),
                    "authenticated_status" to authenticated.status,
                    "round_trip" to linkedMapOf(
                        "expected_bytes" to invocation.expectedRoundTripBytes,
                        "actual_bytes" to payload.size,
                        "expected_sha256" to invocation.expectedRoundTripSha256,
                        "actual_sha256" to sha256Hex(payload),
                        "ingest_http_status" to 200,
                        "parser_succeeded" to parserSucceeded,
                    ),
                ),
            )
        }

    private fun runG2(context: Context, invocation: GateInvocation, startedAt: String): GateResult {
        val stores = syncStores(context)
        val identity = requirePairedIdentity(stores)
        val handle = requireNotNull(identity.observerHandle) { "observer_handle_absent" }
        val telemetry = GateTelemetry()
        val response = requestSegments(stores, handle, invocation.observerDay!!, telemetry)
        val parsed = SegmentReconciler(noRequestClient(), handle).parseFetchResponse(response)
        val semantic = semanticCommitment(parsed)
        val statusTelemetry = GateTelemetry()
        val checkpoint = checkpointFromProductionStatus(
            "g2_completed", probeId(invocation, "completed"),
            realStatusProbe(stores, statusTelemetry).probe(),
        )
        return result(
            invocation, startedAt, listOf(checkpoint),
            telemetry.snapshot().relayDials + statusTelemetry.snapshot().relayDials,
            linkedMapOf(
                "http_status" to response.status,
                "raw_body_bytes" to response.body.size,
                "raw_body_sha256" to sha256Hex(response.body),
                "parser_succeeded" to true,
                "protocol_version" to OBSERVER_PROTOCOL_VERSION,
                "item_count" to parsed.size,
                "semantic_commitments_sha256" to semantic,
            ),
        )
    }

    private fun runG3(context: Context, invocation: GateInvocation, startedAt: String): GateResult {
        val stores = syncStores(context)
        val identity = requirePairedIdentity(stores)
        val handle = requireNotNull(identity.observerHandle) { "observer_handle_absent" }
        val progress = GateProgressWriter(File(context.filesDir, "$GATE_PRIVATE_DIR/$GATE_PROGRESS_FILE"))
        val progressOrder = G3ProgressOrder(invocation.expectedBodyBytes!!)
        val interruptedTelemetry = GateTelemetry()
        val executor = newGateExecutor()
        var partial = 0
        var activeAtPartial = 0
        val requestStarted = android.os.SystemClock.elapsedRealtime()
        try {
            val future = executor.submitBounded {
                requestSegments(stores, handle, invocation.observerDay!!, interruptedTelemetry)
            }
            while (android.os.SystemClock.elapsedRealtime() - requestStarted <= GATE_STAGE_TIMEOUT_MS) {
                partial = interruptedTelemetry.snapshot().consumedBytes
                if (partial > 0 && partial < invocation.expectedBodyBytes!!) {
                    activeAtPartial = interruptedTelemetry.snapshot().activeStreams
                    break
                }
                if (future.isDone) error("interruption_not_achieved")
                Thread.sleep(10)
            }
            require(partial > 0 && partial < invocation.expectedBodyBytes!!) { "partial_signal_timeout" }
            progress.write(
                invocation.runNonce,
                progressOrder.advance(G3ProgressState.PARTIAL_RESPONSE_CONSUMED, partial),
            )
            val requestFailed = try {
                val elapsed = android.os.SystemClock.elapsedRealtime() - requestStarted
                future.awaitBounded(GATE_STAGE_TIMEOUT_MS - elapsed)
                false
            } catch (_: ExecutionException) {
                true
            }
            require(requestFailed) { "interruption_not_achieved" }
            val interruptedElapsed = android.os.SystemClock.elapsedRealtime() - requestStarted
            val interruptedSnapshot = interruptedTelemetry.snapshot()
            require(
                interruptedSnapshot.activeStreams == 0 && interruptedSnapshot.terminated,
            ) { "interrupted_local_cleanup_unproven" }
            progress.write(
                invocation.runNonce,
                progressOrder.advance(G3ProgressState.INTERRUPTED_REQUEST_FAILED, partial),
            )

            val degradedTelemetry = GateTelemetry()
            val interrupted = checkpointFromProductionStatus(
                "g3_interrupted", probeId(invocation, "interrupted"),
                realStatusProbe(stores, degradedTelemetry).probe(),
            )
            require(
                interrupted.variant == PlCheckpointKind.PAIRED_UNREACHABLE &&
                    !interrupted.reason.isNullOrBlank(),
            ) { "degraded_status_unproven" }
            progress.write(
                invocation.runNonce,
                progressOrder.advance(G3ProgressState.DEGRADED_STATUS_RECORDED, partial),
            )

            val restorationTelemetry = awaitRestoredProductionStatus(stores)
            progress.write(
                invocation.runNonce,
                progressOrder.advance(G3ProgressState.NETWORK_RESTORE_OBSERVED, partial),
            )

            val recoveryTelemetry = GateTelemetry()
            val recovery = requestSegments(stores, handle, invocation.observerDay!!, recoveryTelemetry)
            val parsed = SegmentReconciler(noRequestClient(), handle).parseFetchResponse(recovery)
            val semantic = semanticCommitment(parsed)
            val recoveredStatusTelemetry = GateTelemetry()
            val recovered = checkpointFromProductionStatus(
                "g3_recovered", probeId(invocation, "recovered"),
                realStatusProbe(stores, recoveredStatusTelemetry).probe(),
            )
            val oldSession = requireNotNull(interruptedSnapshot.sessionIdSha256) {
                "old_session_identity_unavailable"
            }
            val newSession = requireNotNull(recoveryTelemetry.snapshot().sessionIdSha256) {
                "new_session_identity_unavailable"
            }
            return result(
                invocation, startedAt, listOf(interrupted, recovered),
                interruptedSnapshot.relayDials + degradedTelemetry.snapshot().relayDials +
                    restorationTelemetry.snapshot().relayDials + recoveryTelemetry.snapshot().relayDials +
                    recoveredStatusTelemetry.snapshot().relayDials,
                linkedMapOf(
                    "expected_body_bytes" to invocation.expectedBodyBytes,
                    "expected_body_sha256" to invocation.expectedBodySha256,
                    "expected_semantics_sha256" to invocation.expectedSemanticsSha256,
                    "partial_body_bytes" to partial,
                    "response_completed_before_cut" to false,
                    "progress_sequence_complete" to progressOrder.isComplete(),
                    "cut_after_partial" to true,
                    "interrupted_request" to linkedMapOf(
                        "failed" to true,
                        "error_type" to "transport_interrupted",
                        "elapsed_ms" to interruptedElapsed,
                    ),
                    "local_lifecycle" to linkedMapOf(
                        "active_streams_at_partial" to activeAtPartial,
                        "active_streams_after" to interruptedSnapshot.activeStreams,
                        "interrupted_session_closed" to interruptedSnapshot.terminated,
                        "old_relay_session_disappeared" to false,
                        "source" to "android_driver_relay_session_lifecycle_v1",
                        "old_session_id_sha256" to oldSession,
                        "new_session_id_sha256" to newSession,
                    ),
                    "recovery" to linkedMapOf(
                        "fresh_session_opened" to (oldSession != newSession),
                        "http_status" to recovery.status,
                        "raw_body_bytes" to recovery.body.size,
                        "raw_body_sha256" to sha256Hex(recovery.body),
                        "semantic_commitments_sha256" to semantic,
                    ),
                ),
            )
        } finally {
            executor.shutdownNow()
        }
    }

    private fun awaitRestoredProductionStatus(stores: SyncStores): GateTelemetry {
        val telemetry = GateTelemetry()
        val started = android.os.SystemClock.elapsedRealtime()
        repeat(2) { attempt ->
            val status = realStatusProbe(stores, telemetry).probe()
            if (status is app.solstone.observer.harness.HarnessPlStatus.Reachable &&
                status.status == 200
            ) {
                return telemetry
            }
            val elapsed = android.os.SystemClock.elapsedRealtime() - started
            val remaining = GATE_STAGE_TIMEOUT_MS - elapsed
            if (attempt == 1 || remaining < 30_250L) {
                error("network_restore_unverified")
            }
            Thread.sleep(250L)
        }
        error("network_restore_unverified")
    }

    private fun runStatusAction(
        context: Context,
        invocation: GateInvocation,
        startedAt: String,
        degraded: Boolean,
    ): GateResult {
        val stores = syncStores(context)
        val identity = requirePairedIdentity(stores)
        val telemetry = GateTelemetry()
        val started = android.os.SystemClock.elapsedRealtime()
        val status = realStatusProbe(stores, telemetry).probe()
        val elapsed = android.os.SystemClock.elapsedRealtime() - started
        val checkpoint = checkpointFromProductionStatus(
            if (degraded) "g4_degraded" else "g4_recovered",
            probeId(invocation, if (degraded) "degraded" else "recovered"),
            status,
        )
        return result(
            invocation, startedAt, listOf(checkpoint), telemetry.snapshot().relayDials,
            linkedMapOf("relay_origin" to identity.relayOrigin, "elapsed_ms" to elapsed),
        )
    }

    private fun result(
        invocation: GateInvocation,
        startedAt: String,
        checkpoints: List<GateCheckpoint>,
        relayDials: Int,
        facts: Map<String, Any?>,
    ): GateResult {
        val value = GateResult(
            runNonce = invocation.runNonce,
            action = invocation.action,
            actionSequence = invocation.actionSequence,
            result = GateOutcome.PASS,
            startedAt = startedAt,
            finishedAt = utcSecond(),
            ownerStatusCheckpoints = checkpoints,
            productionRelayDialAttempts = relayDials,
            callerRetryAttempts = 0,
            facts = facts,
        )
        require(app.solstone.core.gate.GateResultVerifier.violations(value).isEmpty()) {
            "driver_evidence_invalid"
        }
        return value
    }

    private fun errorResult(
        invocation: GateInvocation,
        startedAt: String,
        throwable: Throwable,
    ): GateResult {
        val errorType = throwable.message
            ?.takeIf { it.matches(Regex("[a-z0-9_]{1,64}")) }
            ?: throwable.javaClass.simpleName.take(64)
        return GateResult(
            runNonce = invocation.runNonce,
            action = invocation.action,
            actionSequence = invocation.actionSequence,
            result = if (errorType == "interruption_not_achieved") GateOutcome.FAIL else GateOutcome.ERROR,
            startedAt = startedAt,
            finishedAt = utcSecond(),
            ownerStatusCheckpoints = invocation.action.requiredCheckpoints.mapIndexed { index, name ->
                GateCheckpoint(name, probeId(invocation, "missing-$index"), PlCheckpointKind.NOT_PAIRED, null, null, null)
            },
            productionRelayDialAttempts = 0,
            callerRetryAttempts = 0,
            facts = emptyFacts(invocation.action),
            diagnostics = listOf(GateDiagnostic(errorType, "driver")),
        )
    }

    private fun withPairAuthority(
        context: Context,
        invocation: GateInvocation,
        block: (String) -> GateResult,
    ): GateResult {
        val file = File(context.filesDir, "$GATE_PRIVATE_DIR/$GATE_AUTHORITY_FILE")
        val stat = Os.stat(file.path)
        require(OsConstants.S_ISREG(stat.st_mode) && stat.st_mode and 0x1ff == 0x180) {
            "pair_authority_mode_invalid"
        }
        val bytes = file.inputStream().use { input ->
            val bounded = input.readNBytes(16_385)
            require(bounded.size <= 16_384) { "pair_authority_too_large" }
            bounded
        }
        require(file.delete() && !file.exists()) { "pair_authority_delete_failed" }
        var pairLink: String? = null
        try {
            val root = parseJson(bytes.toString(Charsets.UTF_8)) as? Map<*, *>
                ?: error("pair_authority_malformed")
            require(
                root.keys == setOf(
                    "schema_version", "driver_contract_version", "run_nonce", "action",
                    "action_sequence", "pair_link",
                ),
            ) { "pair_authority_malformed" }
            require((root["schema_version"] as? Number)?.toInt() == 1) { "pair_authority_malformed" }
            require((root["driver_contract_version"] as? Number)?.toInt() == 1) { "pair_authority_malformed" }
            require(root["run_nonce"] == invocation.runNonce) { "pair_authority_stale" }
            require(root["action"] == "g1_pair_round_trip") { "pair_authority_action_mismatch" }
            require((root["action_sequence"] as? Number)?.toInt() == 1) { "pair_authority_sequence_mismatch" }
            pairLink = root["pair_link"] as? String ?: error("pair_authority_malformed")
            return block(pairLink)
        } finally {
            pairLink = null
            bytes.fill(0)
        }
    }

    private fun requestSegments(
        stores: SyncStores,
        handle: String,
        day: String,
        telemetry: GateTelemetry,
    ): HttpResponse {
        val identity = requirePairedIdentity(stores)
        val credential = requireNotNull(stores.credentialStore.load()) { "credential_absent" }
        return openRelaySyncClient(
            requireNotNull(identity.relayOrigin), identity.instanceId, requireNotNull(identity.deviceToken),
            credential, telemetry, telemetry,
        ).use { client ->
            client.request(
                "GET", "$SEGMENTS_PATH/$day",
                mapOf(
                    OBSERVER_HANDLE_HEADER to handle,
                    PROTOCOL_VERSION_HEADER to OBSERVER_PROTOCOL_VERSION.toString(),
                ),
                null,
            )
        }
    }

    private fun realStatusProbe(stores: SyncStores, telemetry: GateTelemetry) =
        RealPlStatusProbe(
            stores.endpointStore, stores.credentialStore, stores.identityStore, telemetry, telemetry,
        )

    private fun requirePairedIdentity(stores: SyncStores) =
        requireNotNull(stores.identityStore.load()) { "paired_identity_absent" }

    private fun requireEmptyProductionStores(context: Context, stores: SyncStores) {
        val absent = stores.endpointStore.load() == null &&
            stores.credentialStore.load() == null &&
            stores.identityStore.load() == null &&
            stores.beaconStateStore.load() == null
        val directory = plStoreDir(context)
        val regularFiles = if (directory.exists()) {
            directory.walkTopDown().filter(File::isFile).toList()
        } else {
            emptyList()
        }
        require(absent && regularFiles.isEmpty()) { "preexisting_pair_state" }
    }

    private fun semanticCommitment(segments: List<app.solstone.core.observer.ServerSegment>): String =
        semanticCommitmentSha256(
            segments.map { segment ->
                GateSemanticSegment(
                    segment.key,
                    segment.files.map {
                        GateSemanticFile(it.name, it.sha256, it.status, it.submittedName)
                    },
                )
            },
        )

    private fun probeId(invocation: GateInvocation, suffix: String) =
        "${invocation.runNonce}:${invocation.actionSequence}:$suffix"

    private fun instrumentationExtras(): Map<String, String?> {
        val arguments = InstrumentationRegistry.getArguments()
        return listOf(
            "gate_contract_version",
            "gate_action",
            "gate_run_nonce",
            "gate_action_sequence",
            "gate_observer_day",
            "gate_expected_body_bytes",
            "gate_expected_body_sha256",
            "gate_expected_semantics_sha256",
            "gate_expected_round_trip_bytes",
            "gate_expected_round_trip_sha256",
        ).associateWith(arguments::getString).filterValues { it != null }
    }

    private fun noRequestClient(): app.solstone.core.pl.PlHttpClient =
        object : app.solstone.core.pl.PlHttpClient {
            override fun request(
                method: String,
                path: String,
                headers: Map<String, String>,
                body: ByteArray?,
            ): HttpResponse = error("unexpected_second_request")
        }

    private fun emptyFacts(action: GateAction): Map<String, Any?> = when (action) {
        GateAction.G1_PAIR_ROUND_TRIP -> linkedMapOf(
            "pre_pair" to linkedMapOf(
                "credential_absent" to false, "identity_absent" to false,
                "endpoint_absent" to false, "observer_handle_absent" to false,
            ),
            "pair" to linkedMapOf(
                "route" to null, "relay_origin" to null, "handshake_pinned" to false,
                "pair_http_status" to null, "enroll_http_status" to null,
                "credential_persisted" to false, "paired_identity_persisted" to false,
                "device_token_persisted" to false,
            ),
            "registration" to linkedMapOf(
                "http_status" to null, "protocol_version" to null, "registered" to false,
                "handle_persisted" to false,
            ),
            "authenticated_status" to null,
            "round_trip" to linkedMapOf(
                "expected_bytes" to null,
                "actual_bytes" to null, "expected_sha256" to null, "actual_sha256" to null,
                "ingest_http_status" to null, "parser_succeeded" to false,
            ),
        )
        GateAction.G2_LARGE_RESPONSE -> linkedMapOf(
            "http_status" to null, "raw_body_bytes" to null, "raw_body_sha256" to null,
            "parser_succeeded" to false, "protocol_version" to null, "item_count" to null,
            "semantic_commitments_sha256" to null,
        )
        GateAction.G3_INTERRUPT_RECOVER -> linkedMapOf(
            "expected_body_bytes" to null, "expected_body_sha256" to null,
            "expected_semantics_sha256" to null, "partial_body_bytes" to 0,
            "response_completed_before_cut" to false, "progress_sequence_complete" to false,
            "cut_after_partial" to false,
            "interrupted_request" to linkedMapOf(
                "failed" to false, "error_type" to null, "elapsed_ms" to null,
            ),
            "local_lifecycle" to linkedMapOf(
                "active_streams_at_partial" to 0, "active_streams_after" to 0,
                "interrupted_session_closed" to false, "old_relay_session_disappeared" to false,
                "source" to "android_driver_relay_session_lifecycle_v1",
                "old_session_id_sha256" to null, "new_session_id_sha256" to null,
            ),
            "recovery" to linkedMapOf(
                "fresh_session_opened" to false, "http_status" to null, "raw_body_bytes" to null,
                "raw_body_sha256" to null, "semantic_commitments_sha256" to null,
            ),
        )
        GateAction.G4_DEGRADED_PROBE, GateAction.G4_RECOVERY_PROBE ->
            linkedMapOf("relay_origin" to null, "elapsed_ms" to null)
    }

}
