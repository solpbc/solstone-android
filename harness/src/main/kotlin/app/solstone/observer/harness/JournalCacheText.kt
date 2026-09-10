// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.harness

import java.util.Locale

/**
 * The local-storage screen the owner reaches from `settings → this device`.
 *
 * ⚠ **This whole screen was in the operator register** — `Local cache` · `Cache usage: 4 GB` ·
 * `Attention: cache removal is incomplete` · `Unsafe cache paths left in place: 1`. Those are
 * accurate and they are diagnostics: they name an internal mechanism, capitalise like a log line,
 * and leave the owner to work out what it means for them. It is owner-reachable — `manage local
 * storage` on the source detail screen and the `this device` shelf pane both land here — so it owes
 * the same voice as every other screen: lowercase, second person, and no word the owner has to
 * translate.
 *
 * ⛔ **Not one fact was dropped in the conversion.** Every count, every byte figure and every
 * blocked reason still renders; only the words changed. A screen about storage that quietly stops
 * reporting a residual is worse than one that reports it awkwardly.
 *
 * 🔴 `needs attention` is the locked state word, so the attention lines use it rather than
 * inventing an `Attention:` prefix that appears nowhere else in the app.
 */
fun journalCacheText(state: HarnessJournalCacheState): String = buildList {
    add("space on this device")
    add("your limit: ${decimalBytes(state.configuredLimitBytes)}")
    when (state.limitFallback) {
        HarnessJournalCacheLimitFallback.ABSENT ->
            add("you haven't chosen a limit yet, so the solstone app is using its default.")
        HarnessJournalCacheLimitFallback.CORRUPT ->
            add("couldn't read the limit you chose, so the solstone app is using its default. choose one below to set it again.")
        null -> Unit
    }
    when (state.saveError) {
        HarnessJournalCacheSaveError.REJECTED ->
            add("that limit isn't one you can choose. your previous limit is still in place.")
        HarnessJournalCacheSaveError.FAILED ->
            add("couldn't save that limit. your previous limit is still in place.")
        null -> Unit
    }
    val pass = state.latestPass
    if (pass == null) {
        add("the solstone app hasn't checked this yet.")
    } else {
        val checkedCurrentLimit = pass.configuredLimitBytes == state.configuredLimitBytes
        if (pass.measuredUsageBytes == null) {
            add("needs attention: couldn't measure how much space is in use.")
        } else {
            add("in use: ${decimalBytes(pass.measuredUsageBytes)}")
        }
        pass.measuredFreeBytes?.let { add("free on this device: ${decimalBytes(it)}") }
        pass.blockedReason?.let { add("needs attention: ${blockedText(it)}") }
        // ⚠ TWO causes, and naming only one of them would be confidently wrong half the time:
        // `isUnderPressure` is `usage > limit || free < floor`, so this fires both when the app is
        // over the limit the owner set AND when the device itself is nearly full.
        //
        // 🔴 **And it is suppressed when a measurement failed, because then it is not a fact about
        // the device at all.** `JournalCacheEvictionService` hard-codes `pressureRemains = true` on
        // any measurement failure — a correct fail-safe for an eviction engine, which should assume
        // pressure it cannot rule out. ⛔ But rendering an engine's fail-safe as a statement to the
        // owner is confident wrongness: driven on real hardware, a fresh install whose free-space
        // read failed showed `in use: 0.0 MB` and `there still isn't enough room` on the same
        // screen. The `needs attention: couldn't measure…` line above already says the true thing.
        if (pass.pressureRemains && !pass.measurementFailed()) {
            add("there still isn't enough room — either this is over your limit, or the device is low on space.")
        }
        if (pass.durablyMarkedCount > 0) add("ready to remove: ${pass.durablyMarkedCount}")
        if (pass.reclaimedBytes > 0) add("freed up: ${decimalBytes(pass.reclaimedBytes)}")
        if (pass.retryableResidualCount > 0) {
            add("still to remove: ${pass.retryableResidualCount} — the solstone app will try again.")
        }
        if (pass.refusedPathCount > 0) {
            add("kept in place because removing them wouldn't be safe: ${pass.refusedPathCount}")
        }
        if (!checkedCurrentLimit) add("your new limit hasn't been checked yet.")
        if (
            checkedCurrentLimit &&
            pass.blockedReason == null &&
            !pass.pressureRemains &&
            pass.retryableResidualCount == 0 &&
            pass.refusedPathCount == 0
        ) {
            add("checked, and there's nothing to do.")
        }
    }
}.joinToString("\n")

/**
 * ⚠ Each of these is the second half of a `needs attention:` line, so it reads as one sentence with
 * it. ⛔ `cache`, `segment` and `transition` are internal words the owner never sees anywhere else
 * in the app; `no safely removable uploaded data is available` also stated a mechanism instead of
 * the consequence, which is that the app cannot free space without risking something unconfirmed.
 */
private fun blockedText(reason: HarnessJournalCacheBlockedReason): String = when (reason) {
    HarnessJournalCacheBlockedReason.MEASUREMENT_FAILED -> "couldn't measure how much space is in use."
    HarnessJournalCacheBlockedReason.FREE_SPACE_FAILED -> "couldn't measure how much space this device has left."
    HarnessJournalCacheBlockedReason.ARITHMETIC_OVERFLOW -> "couldn't work out the size safely."
    HarnessJournalCacheBlockedReason.TRANSITION_FAILED -> "couldn't update what it keeps on this device."
    HarnessJournalCacheBlockedReason.NO_SAFE_ELIGIBLE_SEGMENT ->
        "nothing can be removed safely yet. your journal hasn't confirmed it holds the rest, so the solstone app is keeping it."
    HarnessJournalCacheBlockedReason.REMOVAL_INCOMPLETE -> "didn't finish freeing space."
}

fun decimalBytes(bytes: Long): String {
    val gb = bytes / 1_000_000_000.0
    if (bytes >= 1_000_000_000L) {
        return if (bytes % 1_000_000_000L == 0L) "${bytes / 1_000_000_000L} GB" else String.format(Locale.US, "%.1f GB", gb)
    }
    return String.format(Locale.US, "%.1f MB", bytes / 1_000_000.0)
}

/**
 * Whether this pass's verdict rests on a measurement that did not happen.
 *
 * ⚠ These three reasons are the ones where the engine could not read the device, so every derived
 * quantity it reports — `pressureRemains` above all — is a fail-safe rather than an observation.
 */
private fun HarnessJournalCachePass.measurementFailed(): Boolean =
    blockedReason == HarnessJournalCacheBlockedReason.MEASUREMENT_FAILED ||
        blockedReason == HarnessJournalCacheBlockedReason.FREE_SPACE_FAILED ||
        blockedReason == HarnessJournalCacheBlockedReason.ARITHMETIC_OVERFLOW
