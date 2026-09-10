// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.scaffold

/**
 * Whether a launch of the observer harness is the shell (or an App Link) handing over **one owner
 * task**, rather than a bare launch of the harness itself.
 *
 * 🔴 **The distinction decides whether the owner can reach operator instrumentation.** The harness
 * menu lists a permission probe, a transport probe, raw start/stop, queue counters and an evidence
 * browser that prints spool paths, wire segment ids and SHA-256s. On an owner task the menu does not
 * exist and leaving the task returns to the shell.
 *
 * ⚠ **A bare launch is a real case and must keep the menu:** `apps/watch` declares `ObserverActivity`
 * as its `LAUNCHER`, so on that surface the harness *is* the app. `apps/phone` declares it with the
 * App Links filter only — no `LAUNCHER` — so on the phone every launch is an owner task and the menu
 * is correctly unreachable.
 *
 * Takes primitives rather than an `Intent` so the decision is testable off-device; the activity owns
 * the one-line read of the intent.
 */
fun isOwnerTaskLaunch(
    scansPairQr: Boolean,
    showsLocalCache: Boolean,
    isViewAction: Boolean,
    hasData: Boolean,
    handlesPairLinks: Boolean,
    firstLaunch: Boolean,
): Boolean {
    if (scansPairQr || showsLocalCache) return true
    // ⚠ `firstLaunch` guards the pair-link arm only, matching where the router itself is gated: a
    // configuration change must not re-dispatch a pair link, and must not silently change which
    // surface the owner is on either.
    return firstLaunch && handlesPairLinks && isViewAction && hasData
}
