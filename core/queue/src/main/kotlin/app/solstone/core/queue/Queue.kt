// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.queue

import app.solstone.core.model.QueueState

enum class QueueEvent { SEAL, START_UPLOAD, MARK_UPLOADED, MARK_FAILED, RETRY, FINISH }

enum class RetryDecision { STOP_AUTH, RETRY, HARD_FAIL }

fun transition(from: QueueState, event: QueueEvent): QueueState =
    when (from to event) {
        QueueState.RECORDING to QueueEvent.SEAL -> QueueState.SEALED
        QueueState.SEALED to QueueEvent.START_UPLOAD -> QueueState.UPLOADING
        QueueState.UPLOADING to QueueEvent.MARK_UPLOADED -> QueueState.UPLOADED
        QueueState.UPLOADING to QueueEvent.MARK_FAILED -> QueueState.FAILED
        QueueState.FAILED to QueueEvent.RETRY -> QueueState.UPLOADING
        QueueState.UPLOADED to QueueEvent.FINISH -> QueueState.EVICTED
        QueueState.EVICTED to QueueEvent.FINISH -> QueueState.EVICTED
        else -> throw IllegalStateException("Illegal queue transition from $from on $event")
    }

fun canTransition(from: QueueState, event: QueueEvent): Boolean =
    runCatching { transition(from, event) }.isSuccess

fun classify(httpStatus: Int?, ioError: Boolean): RetryDecision =
    when {
        httpStatus == 401 || httpStatus == 403 -> RetryDecision.STOP_AUTH
        ioError -> RetryDecision.RETRY
        httpStatus in 500..599 -> RetryDecision.RETRY
        httpStatus == 408 || httpStatus == 425 || httpStatus == 429 -> RetryDecision.RETRY
        httpStatus in 400..499 -> RetryDecision.HARD_FAIL
        else -> RetryDecision.HARD_FAIL
    }

private const val SECOND_MS = 1_000L
private const val MINUTE_MS = 60L * SECOND_MS
private const val HOUR_MS = 60L * MINUTE_MS
private const val RETRY_BACKOFF_BASE_MS = 15L * MINUTE_MS
private const val RETRY_BACKOFF_CAP_MS = 4L * HOUR_MS
private const val HARD_FAIL_BACKOFF_BASE_MS = 2L * HOUR_MS
private const val HARD_FAIL_BACKOFF_CAP_MS = 6L * HOUR_MS
private const val IMPORT_RETRY_BACKOFF_BASE_MS = 15L * SECOND_MS
private const val IMPORT_RETRY_BACKOFF_CAP_MS = HOUR_MS
private val RESUMABLE_CHUNK_RETRY_LADDER_MS = longArrayOf(
    1L * SECOND_MS,
    2L * SECOND_MS,
    5L * SECOND_MS,
    10L * SECOND_MS,
    30L * SECOND_MS,
    60L * SECOND_MS,
)

fun retryBackoffMs(attemptCount: Int, decision: RetryDecision): Long {
    val exponent = (attemptCount - 1).coerceAtLeast(0)
    return when (decision) {
        RetryDecision.RETRY -> (RETRY_BACKOFF_BASE_MS shl exponent.coerceAtMost(4)).coerceAtMost(RETRY_BACKOFF_CAP_MS)
        RetryDecision.HARD_FAIL -> (HARD_FAIL_BACKOFF_BASE_MS shl exponent.coerceAtMost(2)).coerceAtMost(HARD_FAIL_BACKOFF_CAP_MS)
        RetryDecision.STOP_AUTH -> 0L
    }
}

/**
 * Retry backoff for one-shot owner-shared import uploads: 15s, 30s, 1m, 2m, 4m, 8m, 16m, 32m,
 * capped at 1h. Kept separate from [retryBackoffMs], which owns segment sync's 15m..4h schedule.
 */
fun importRetryBackoffMs(attemptCount: Int): Long {
    val exponent = (attemptCount - 1).coerceAtLeast(0).coerceAtMost(8)
    return (IMPORT_RETRY_BACKOFF_BASE_MS shl exponent).coerceAtMost(IMPORT_RETRY_BACKOFF_CAP_MS)
}

/**
 * Transient chunk retry ladder for resumable import uploads: `1s -> 2s -> 5s -> 10s -> 30s -> 60s`
 * with `+-20%` jitter ([jitterUnit] in `0.0..1.0` maps linearly to `0.8..1.2`, with `0.5` yielding
 * the exact unjittered step). Kept separate from both segment sync and one-shot import backoffs.
 */
fun resumableChunkRetryDelayMs(attemptCount: Int, jitterUnit: Double = 0.5): Long {
    val index = (attemptCount - 1).coerceIn(0, RESUMABLE_CHUNK_RETRY_LADDER_MS.lastIndex)
    val base = RESUMABLE_CHUNK_RETRY_LADDER_MS[index]
    val clamped = jitterUnit.coerceIn(0.0, 1.0)
    val factor = 0.8 + (clamped * 0.4)
    return Math.round(base * factor).coerceAtLeast(1L)
}

