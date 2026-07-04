// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.work

import app.solstone.core.observer.ObserverHealth
import app.solstone.core.observer.ObserverHealthClient
import app.solstone.core.pl.BeaconState
import app.solstone.core.pl.PlHttpClient
import app.solstone.platform.persistence.room.SyncStateRow

data class DrainReport(
    val workOutcome: SyncOutcome,
    val cleanDrain: Boolean,
    val failedThisRun: Boolean,
    val lastErrorReason: String?,
)

fun advanceLastSuccess(prior: Long?, cleanDrain: Boolean, now: Long): Long? =
    if (cleanDrain) now else prior

fun nextRecentErrorCount(previous: Int, cleanDrain: Boolean, failedThisRun: Boolean): Int =
    when {
        cleanDrain -> 0
        failedThisRun -> (previous + 1).coerceIn(0, 99)
        else -> previous
    }

fun redactErrorReason(raw: String?): String? {
    if (raw == null) {
        return null
    }
    val cleaned = buildString(raw.length) {
        var lastWasSpace = false
        for (char in raw) {
            val replacement = if (char.isISOControl() || char == '\n' || char == '\r' || char == '\t') ' ' else char
            if (replacement == ' ') {
                if (!lastWasSpace) {
                    append(replacement)
                }
                lastWasSpace = true
            } else {
                append(replacement)
                lastWasSpace = false
            }
        }
    }.trim()
    if (cleaned.isBlank()) {
        return null
    }
    return cleaned.take(200)
}

fun buildObserverHealth(
    name: String,
    streamType: String,
    version: String,
    startedAt: Long,
    now: Long,
    lastSuccessAt: Long?,
    pendingCount: Int,
    recentErrorCount: Int,
    rawErrorReason: String?,
): ObserverHealth =
    ObserverHealth(
        name = name,
        streamType = streamType,
        version = version,
        uptime = maxOf(0L, (now - startedAt) / 1000),
        lastSuccessfulSync = lastSuccessAt,
        pendingQueueDepth = pendingCount,
        recentErrorCount = recentErrorCount.coerceIn(0, 99),
        lastErrorReason = redactErrorReason(rawErrorReason),
    )

enum class BeaconEmitResult { DELIVERED, FAILED }

fun emitObserverHealth(
    client: PlHttpClient,
    priorState: BeaconState?,
    persist: (BeaconState) -> Unit,
    streamType: String,
    handle: String,
    version: String,
    now: Long,
    syncRow: SyncStateRow?,
    cleanDrain: Boolean,
    failedThisRun: Boolean,
    rawErrorReason: String?,
    log: (String, Throwable?) -> Unit,
): BeaconEmitResult =
    try {
        val startedAt = priorState?.startedAt ?: now
        val nextCount = nextRecentErrorCount(priorState?.recentErrorCount ?: 0, cleanDrain, failedThisRun)
        persist(BeaconState(startedAt, nextCount))
        val health = buildObserverHealth(
            name = handle,
            streamType = streamType,
            version = version,
            startedAt = startedAt,
            now = now,
            lastSuccessAt = syncRow?.lastSuccessAt,
            pendingCount = syncRow?.pendingCount ?: 0,
            recentErrorCount = nextCount,
            rawErrorReason = rawErrorReason,
        )
        ObserverHealthClient(client).report(health)
        BeaconEmitResult.DELIVERED
    } catch (e: Exception) {
        log("observer health emit failed", e)
        BeaconEmitResult.FAILED
    }
