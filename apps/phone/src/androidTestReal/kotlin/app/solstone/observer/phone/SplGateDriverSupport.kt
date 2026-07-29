// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone

import app.solstone.core.gate.GateCheckpoint
import app.solstone.core.gate.G3ProgressRecord
import app.solstone.core.gate.GateHttpResult
import app.solstone.core.gate.PlCheckpointKind
import app.solstone.core.crypto.sha256Hex
import app.solstone.core.identity.atomicWriteOwnerOnly
import app.solstone.core.pl.PlStreamObserver
import app.solstone.core.pl.RelayDialObserver
import app.solstone.core.pl.toJson
import app.solstone.observer.harness.HarnessPlStatus
import java.io.File
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.UUID

internal const val GATE_STAGE_TIMEOUT_MS = 35_000L
internal const val GATE_PRIVATE_DIR = "solstone-android-gate/v1"
internal const val GATE_AUTHORITY_FILE = "pair-authority.json"
internal const val GATE_RESULT_FILE = "action-result.json"
internal const val GATE_PROGRESS_FILE = "action-progress.json"

internal fun utcSecond(): String = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString()

internal fun checkpointFromProductionStatus(
    checkpoint: String,
    probeId: String,
    status: HarnessPlStatus,
): GateCheckpoint = when (status) {
    HarnessPlStatus.NotPaired ->
        GateCheckpoint(checkpoint, probeId, PlCheckpointKind.NOT_PAIRED, null, null, null)
    is HarnessPlStatus.PairedButUnreachable ->
        GateCheckpoint(
            checkpoint,
            probeId,
            PlCheckpointKind.PAIRED_UNREACHABLE,
            status.reason?.takeIf(String::isNotBlank) ?: "production_probe_unreachable",
            null,
            GateHttpResult(probeId, attempted = true, completed = false, status = null),
        )
    is HarnessPlStatus.Reachable ->
        GateCheckpoint(
            checkpoint,
            probeId,
            PlCheckpointKind.REACHABLE,
            null,
            status.status,
            GateHttpResult(
                probeId,
                attempted = true,
                completed = status.status == 200,
                status = status.status,
            ),
        )
}

internal data class GateTelemetrySnapshot(
    val relayDials: Int,
    val activeStreams: Int,
    val maxActiveStreams: Int,
    val consumedBytes: Int,
    val terminated: Boolean,
    val sessionIdSha256: String?,
)

internal class GateTelemetry : PlStreamObserver, RelayDialObserver {
    private val relayDials = AtomicInteger()
    private val active = AtomicInteger()
    private val maxActive = AtomicInteger()
    private val consumed = AtomicInteger()
    private val terminated = AtomicBoolean()
    private val sessionIdSha256 = AtomicReference<String?>()

    override fun onRelayDialAttempt(attemptNumber: Int, host: String, port: Int) {
        relayDials.incrementAndGet()
    }

    override fun onStreamOpened(streamId: Int) {
        sessionIdSha256.compareAndSet(
            null,
            sha256Hex(UUID.randomUUID().toString().toByteArray()),
        )
        val count = active.incrementAndGet()
        maxActive.accumulateAndGet(count) { left, right -> maxOf(left, right) }
    }

    override fun onResponseDataConsumed(streamId: Int, deltaBytes: Int, cumulativeBytes: Int) {
        consumed.set(cumulativeBytes)
    }

    override fun onStreamTerminated(streamId: Int, successful: Boolean) {
        active.decrementAndGet()
        terminated.set(true)
    }

    fun snapshot() = GateTelemetrySnapshot(
        relayDials.get(), active.get(), maxActive.get(), consumed.get(), terminated.get(),
        sessionIdSha256.get(),
    )
}

internal class GateProgressWriter(private val target: File) {
    fun write(
        runNonce: String,
        record: G3ProgressRecord,
    ) {
        val value = linkedMapOf<String, Any?>(
            "schema_version" to 1,
            "driver_contract_version" to 1,
            "run_nonce" to runNonce,
            "action" to "g3_interrupt_recover",
            "action_sequence" to 3,
            "progress_sequence" to record.sequence,
            "state" to record.state,
            "partial_body_bytes" to record.partialBodyBytes,
            "recorded_at" to utcSecond(),
        )
        atomicWriteOwnerOnly(target, (toJson(value) + "\n").toByteArray())
    }
}

internal fun <T> ExecutorService.submitBounded(block: () -> T): Future<T> = submit(Callable(block))

internal fun <T> Future<T>.awaitBounded(timeoutMs: Long = GATE_STAGE_TIMEOUT_MS): T = try {
    get(timeoutMs.coerceAtLeast(1L), TimeUnit.MILLISECONDS)
} catch (timeout: TimeoutException) {
    cancel(true)
    throw timeout
}

internal fun newGateExecutor(): ExecutorService = Executors.newSingleThreadExecutor()
