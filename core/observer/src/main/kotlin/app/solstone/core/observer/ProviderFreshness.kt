// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.observer

const val PROVIDER_STALE_MS = 310_000L

fun isProviderFresh(
    startedEpochMs: Long?,
    lastEmissionEpochMs: Long?,
    nowEpochMs: Long,
    staleMs: Long = PROVIDER_STALE_MS,
): Boolean {
    require(staleMs >= 0L) { "staleMs must not be negative" }
    val freshnessAnchor = lastEmissionEpochMs ?: startedEpochMs ?: return false
    return nowEpochMs - freshnessAnchor in 0L..staleMs
}
