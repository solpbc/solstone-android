// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.queue

import app.solstone.core.model.QueueState

enum class QueueEvent { SEAL, START_UPLOAD, MARK_UPLOADED, MARK_FAILED, RETRY, EVICT }

enum class RetryDecision { STOP_AUTH, RETRY, HARD_FAIL }

fun transition(from: QueueState, event: QueueEvent): QueueState =
    when (from to event) {
        QueueState.RECORDING to QueueEvent.SEAL -> QueueState.SEALED
        QueueState.SEALED to QueueEvent.START_UPLOAD -> QueueState.UPLOADING
        QueueState.UPLOADING to QueueEvent.MARK_UPLOADED -> QueueState.UPLOADED
        QueueState.UPLOADING to QueueEvent.MARK_FAILED -> QueueState.FAILED
        QueueState.FAILED to QueueEvent.RETRY -> QueueState.UPLOADING
        QueueState.SEALED to QueueEvent.EVICT -> QueueState.EVICTED
        QueueState.UPLOADED to QueueEvent.EVICT -> QueueState.EVICTED
        QueueState.FAILED to QueueEvent.EVICT -> QueueState.EVICTED
        else -> throw IllegalStateException("Illegal queue transition from $from on $event")
    }

fun canTransition(from: QueueState, event: QueueEvent): Boolean =
    runCatching { transition(from, event) }.isSuccess

fun classify(httpStatus: Int?, ioError: Boolean): RetryDecision =
    when {
        httpStatus == 401 || httpStatus == 403 -> RetryDecision.STOP_AUTH
        ioError -> RetryDecision.RETRY
        httpStatus in 500..599 -> RetryDecision.RETRY
        httpStatus in 400..499 -> RetryDecision.HARD_FAIL
        else -> RetryDecision.HARD_FAIL
    }
