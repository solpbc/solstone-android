// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone

import android.Manifest
import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
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
import app.solstone.core.gate.SPL_GATE_DRIVER_CONTRACT_VERSION
import app.solstone.core.model.QueueState
import app.solstone.core.gate.semanticCommitmentSha256
import app.solstone.core.observer.INGEST_PROTOCOL_VERSION
import app.solstone.core.observer.PROTOCOL_VERSION_HEADER
import app.solstone.core.observer.SEGMENTS_PATH
import app.solstone.core.observer.SegmentReconciler
import app.solstone.core.pl.HttpResponse
import app.solstone.core.pl.RelayPairLink
import app.solstone.core.pl.parseJson
import app.solstone.core.pl.parsePairLink
import app.solstone.observer.harness.RealPlStatusProbe
import app.solstone.observer.harness.SourceToggleResult
import app.solstone.observer.harness.SourceWish
import app.solstone.observer.harness.SyncNowResult
import app.solstone.observer.scaffold.ObserverActivity
import app.solstone.platform.pl.transport.conscrypt.openRelaySyncClient
import app.solstone.platform.persistence.room.openSolstonePersistenceDatabase
import app.solstone.platform.work.SyncStores
import app.solstone.platform.work.SyncScheduler
import app.solstone.platform.work.plStoreDir
import app.solstone.platform.work.syncStores
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

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
            val stores = syncStores(context)
            requireEmptyProductionStores(context, stores)
            val pre = checkpointFromProductionStatus(
                "g1_pre_pair", probeId(invocation, "pre_pair"), realStatusProbe(stores, GateTelemetry()).probe(),
            )
            require(pre.variant == PlCheckpointKind.NOT_PAIRED) { "prepair_state_not_clean" }
            grantCapturePermissions(context)
            ActivityScenario.launch(ObserverActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    require(!activity.isFinishing && !activity.isDestroyed) { "visible_capture_activity_unavailable" }
                }
                val container = waitForObserverContainer()
                require(waitForRecovery(container)) { "capture_recovery_not_ready" }
                require(container.sources.setWish("camera", SourceWish.Off) is SourceToggleResult.Applied) {
                    "camera_source_not_disabled"
                }
                require(container.sources.setWish("location", SourceWish.Off) is SourceToggleResult.Applied) {
                    "location_source_not_disabled"
                }
                val link = parsePairLink(pairAuthority)
                require(link is RelayPairLink) { "pair_route_not_relay" }
                val pair = requireNotNull(container.controller.onScannedPairLink(pairAuthority)) { "pair_refused" }
                val identity = requireNotNull(stores.identityStore.load()) { "paired_identity_not_persisted" }
                require(identity.relayOrigin == "https://link.solstone.app") { "relay_origin_invalid" }
                require(!identity.deviceToken.isNullOrBlank()) { "device_token_not_persisted" }

                val authenticatedTelemetry = GateTelemetry()
                val authenticated = checkpointFromProductionStatus(
                    "g1_authenticated",
                    probeId(invocation, "authenticated"),
                    realStatusProbe(stores, authenticatedTelemetry).probe(),
                )
                require(container.controller.start()) { "visible_capture_start_refused" }
                Thread.sleep(PHYSICAL_CAPTURE_MINIMUM_MS)
                container.controller.stop()

                lateinit var captured: app.solstone.observer.harness.HarnessEvidenceSegment
                lateinit var audio: app.solstone.observer.harness.HarnessEvidenceFile
                waitUntil("sealed physical audio segment", PHYSICAL_CAPTURE_SEAL_TIMEOUT_MS) {
                    val candidate = container.controller.listEvidence().lastOrNull { evidence ->
                        evidence.files.singleOrNull { file ->
                            file.sourceId == "audio" &&
                                file.name == "audio.m4a" &&
                                file.mediaType == "audio/mp4" &&
                                file.byteSize > 0
                        } != null
                    }
                    val candidateAudio = candidate?.files?.singleOrNull {
                        it.sourceId == "audio" &&
                            it.name == "audio.m4a" &&
                            it.mediaType == "audio/mp4" &&
                            it.byteSize > 0
                    }
                    if (candidate == null || candidateAudio == null) return@waitUntil false
                    captured = candidate
                    audio = candidateAudio
                    true
                }
                require(captured.state == QueueState.SEALED) { "captured_segment_not_sealed" }

                require(container.controller.syncNow() == SyncNowResult.Enqueued) { "normal_sync_not_enqueued" }
                awaitNormalSync(context, captured.id)

                val roundTripTelemetry = GateTelemetry()
                val response = requestSegments(stores, captured.day, audio.sourceId, roundTripTelemetry)
                val parsed = SegmentReconciler(noRequestClient()).parseFetchResponse(response)
                val reconciledSegment = requireNotNull(parsed.firstOrNull { it.key == captured.segment }) {
                    "round_trip_reconciliation_unproven"
                }
                val reconciled = requireNotNull(
                    reconciledSegment.files.firstOrNull { file ->
                        (file.submittedName ?: file.name) == audio.name &&
                            file.size == audio.byteSize &&
                            file.sha256.equals(audio.sha256, ignoreCase = true) &&
                            file.status in setOf("present", "processed")
                    },
                ) { "round_trip_reconciliation_unproven" }
                val roundTripStatusTelemetry = GateTelemetry()
                val roundTrip = checkpointFromProductionStatus(
                    "g1_round_trip",
                    probeId(invocation, "round_trip"),
                    realStatusProbe(stores, roundTripStatusTelemetry).probe(),
                )
                result(
                    invocation,
                    startedAt,
                    listOf(pre, authenticated, roundTrip),
                    authenticatedTelemetry.snapshot().relayDials +
                        roundTripTelemetry.snapshot().relayDials +
                        roundTripStatusTelemetry.snapshot().relayDials,
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
                            // The host-side receipt must bind the accepted segment to
                            // this exact certificate, not to a mutable device label.
                            // `RelayPairing` derives this fingerprint from the leaf
                            // certificate after it verifies the pair response.
                            "client_cert_cid" to identity.clientCertFingerprint,
                        ),
                        "authenticated_status" to authenticated.status,
                        "capture" to linkedMapOf(
                            "visible_activity" to true,
                            "minimum_capture_ms" to PHYSICAL_CAPTURE_MINIMUM_MS,
                            "local_segment_id" to captured.id,
                            "day" to captured.day,
                            "segment" to captured.segment,
                            "source" to audio.sourceId,
                            "name" to audio.name,
                            "media_type" to audio.mediaType,
                            "byte_size" to audio.byteSize,
                            "sha256" to audio.sha256,
                            "queue_state_before_sync" to captured.state.name,
                        ),
                        "round_trip" to linkedMapOf(
                            "sync_enqueued" to true,
                            "sync_work_state" to WorkInfo.State.SUCCEEDED.name,
                            "queue_state_after_sync" to QueueState.UPLOADED.name,
                            "actual_bytes" to audio.byteSize,
                            "actual_sha256" to audio.sha256,
                            "segment_fetch_http_status" to response.status,
                            "parser_succeeded" to parsed.any { segment ->
                                segment.key == reconciledSegment.key && segment.files.contains(reconciled)
                            },
                        ),
                        "reconciliation" to linkedMapOf(
                            "server_segment" to reconciledSegment.key,
                            "server_name" to reconciled.name,
                            "submitted_name" to reconciled.submittedName,
                            "matched_name" to (reconciled.submittedName ?: reconciled.name),
                            "size" to reconciled.size,
                            "sha256" to reconciled.sha256,
                            "status" to reconciled.status,
                            "source" to audio.sourceId,
                            "local_segment_id" to captured.id,
                        ),
                    ),
                )
            }
        }

    private fun runG2(context: Context, invocation: GateInvocation, startedAt: String): GateResult {
        val stores = syncStores(context)
        val telemetry = GateTelemetry()
        val response = requestSegments(stores, invocation.observerDay!!, telemetry = telemetry)
        val parsed = SegmentReconciler(noRequestClient()).parseFetchResponse(response)
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
                "protocol_version" to INGEST_PROTOCOL_VERSION,
                "item_count" to parsed.size,
                "semantic_commitments_sha256" to semantic,
            ),
        )
    }

    private fun runG3(context: Context, invocation: GateInvocation, startedAt: String): GateResult {
        val stores = syncStores(context)
        val expectedBodyBytes = requireNotNull(invocation.expectedBodyBytes)
        val progress = GateProgressWriter(File(context.filesDir, "$GATE_PRIVATE_DIR/$GATE_PROGRESS_FILE"))
        val cutControl = GateCutControl(File(context.filesDir, "$GATE_PRIVATE_DIR/$GATE_CONTROL_FILE"))
        val progressOrder = G3ProgressOrder(expectedBodyBytes)
        val partialReady = CountDownLatch(1)
        val cutApplied = CountDownLatch(1)
        val firstPartial = AtomicInteger()
        val cutControlError = AtomicReference<Throwable?>()
        val interruptedTelemetry = GateTelemetry { cumulativeBytes ->
            if (
                cumulativeBytes > 0 &&
                cumulativeBytes < expectedBodyBytes &&
                firstPartial.compareAndSet(0, cumulativeBytes)
            ) {
                partialReady.countDown()
                try {
                    cutControl.await(invocation.runNonce, "network_cut_applied")
                } catch (throwable: Throwable) {
                    cutControlError.set(throwable)
                } finally {
                    cutApplied.countDown()
                }
            }
        }
        val executor = newGateExecutor()
        var partial = 0
        var activeAtPartial = 0
        val requestStarted = android.os.SystemClock.elapsedRealtime()
        try {
            val future = executor.submitBounded {
                requestSegments(stores, invocation.observerDay!!, telemetry = interruptedTelemetry)
            }
            while (!partialReady.await(10, TimeUnit.MILLISECONDS)) {
                if (future.isDone) {
                    error("interruption_not_achieved")
                }
                if (android.os.SystemClock.elapsedRealtime() - requestStarted > GATE_STAGE_TIMEOUT_MS) {
                    break
                }
            }
            partial = firstPartial.get()
            activeAtPartial = interruptedTelemetry.snapshot().activeStreams
            require(partial > 0 && partial < expectedBodyBytes) { "partial_signal_timeout" }
            progress.write(
                invocation.runNonce,
                progressOrder.advance(G3ProgressState.PARTIAL_RESPONSE_CONSUMED, partial),
            )
            val cutWaitMs = (
                GATE_STAGE_TIMEOUT_MS -
                    (android.os.SystemClock.elapsedRealtime() - requestStarted)
                ).coerceAtLeast(1L)
            require(cutApplied.await(cutWaitMs, TimeUnit.MILLISECONDS)) {
                "network_cut_control_timeout"
            }
            cutControlError.get()?.let { throw it }
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
            cutControl.await(
                invocation.runNonce,
                "interrupted_request_observed",
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

            cutControl.await(invocation.runNonce, "network_restore_applied")
            val restorationTelemetry = awaitRestoredProductionStatus(stores)
            progress.write(
                invocation.runNonce,
                progressOrder.advance(G3ProgressState.NETWORK_RESTORE_OBSERVED, partial),
            )

            val recoveryTelemetry = GateTelemetry()
            val recovery = requestSegments(stores, invocation.observerDay!!, telemetry = recoveryTelemetry)
            val parsed = SegmentReconciler(noRequestClient()).parseFetchResponse(recovery)
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
        val errorType = gateErrorType(throwable)
        val assertionFailure = invocation.action == GateAction.G3_INTERRUPT_RECOVER &&
            errorType in setOf(
                "interruption_not_achieved",
                "partial_signal_timeout",
                "interrupted_local_cleanup_unproven",
                "degraded_status_unproven",
                "network_restore_unverified",
                "old_session_identity_unavailable",
                "new_session_identity_unavailable",
            )
        return GateResult(
            runNonce = invocation.runNonce,
            action = invocation.action,
            actionSequence = invocation.actionSequence,
            result = if (assertionFailure) GateOutcome.FAIL else GateOutcome.ERROR,
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

    private fun gateErrorType(throwable: Throwable): String {
        var current: Throwable? = throwable
        repeat(8) {
            val value = current ?: return@repeat
            value.message
                ?.takeIf { it.matches(Regex("[a-z0-9_]{1,64}")) }
                ?.let { return it }
            current = value.cause
        }
        val message = generateSequence(throwable) { it.cause }
            .take(8)
            .mapNotNull(Throwable::message)
            .joinToString(" ")
        return when {
            "sealed physical audio segment" in message -> "physical_audio_capture_timeout"
            "normal sync completion" in message -> "normal_sync_timeout"
            else -> throwable.javaClass.simpleName
                .replace(Regex("([a-z0-9])([A-Z])"), "$1_$2")
                .lowercase()
                .take(64)
        }
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
            require(
                (root["driver_contract_version"] as? Number)?.toInt() == SPL_GATE_DRIVER_CONTRACT_VERSION,
            ) { "pair_authority_malformed" }
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
        day: String,
        sourceId: String = "",
        telemetry: GateTelemetry,
    ): HttpResponse {
        val identity = requirePairedIdentity(stores)
        val credential = requireNotNull(stores.credentialStore.load()) { "credential_absent" }
        return openRelaySyncClient(
            requireNotNull(identity.relayOrigin), identity.instanceId, requireNotNull(identity.deviceToken),
            credential, telemetry, telemetry,
        ).use { client ->
            client.request(
                "GET", "$SEGMENTS_PATH/$day" +
                    sourceId.takeIf(String::isNotBlank)?.let { "?source=$it" }.orEmpty(),
                mapOf(
                    PROTOCOL_VERSION_HEADER to INGEST_PROTOCOL_VERSION.toString(),
                ),
                null,
            )
        }
    }

    private fun grantCapturePermissions(context: Context) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        // Visible start currently gates on the complete declared capture permission set.
        // Camera and location are switched off before capture, so this scenario emits only audio.
        listOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.POST_NOTIFICATIONS,
        ).forEach { permission ->
            automation.grantRuntimePermission(context.packageName, permission)
        }
    }

    private fun awaitNormalSync(context: Context, segmentId: String) {
        val workManager = WorkManager.getInstance(context)
        waitUntil("normal sync completion", NORMAL_SYNC_TIMEOUT_MS) {
            val terminal = workManager.getWorkInfosForUniqueWork(SyncScheduler.NOW_WORK_NAME)
                .get()
                .lastOrNull { work -> work.state.isFinished }
                ?: return@waitUntil false
            require(terminal.state == WorkInfo.State.SUCCEEDED) {
                "normal_sync_${terminal.state.name.lowercase()}"
            }
            val database = openSolstonePersistenceDatabase(context)
            try {
                database.segmentDao().segmentById(segmentId)?.state == QueueState.UPLOADED
            } finally {
                database.close()
            }
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
            stores.identityStore.load() == null
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
                "device_token_persisted" to false, "client_cert_cid" to null,
            ),
            "authenticated_status" to null,
            "capture" to linkedMapOf(
                "visible_activity" to false, "minimum_capture_ms" to 0L,
                "local_segment_id" to null, "day" to null, "segment" to null,
                "source" to null, "name" to null, "media_type" to null,
                "byte_size" to null, "sha256" to null, "queue_state_before_sync" to null,
            ),
            "round_trip" to linkedMapOf(
                "sync_enqueued" to false, "sync_work_state" to null,
                "queue_state_after_sync" to null, "actual_bytes" to null,
                "actual_sha256" to null, "segment_fetch_http_status" to null,
                "parser_succeeded" to false,
            ),
            "reconciliation" to linkedMapOf(
                "server_segment" to null, "server_name" to null, "submitted_name" to null,
                "matched_name" to null, "size" to null, "sha256" to null, "status" to null,
                "source" to null, "local_segment_id" to null,
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
