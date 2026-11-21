// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone

/**
 * The launch age check, as rules with no platform in them.
 *
 * 🔴 **The store's answer is reduced to an [AgeCategory] the moment it is read, and nothing past
 * that point holds an age.** No range, bound or source reaches a field, a log line, a file or a
 * request. The only thing that outlives the check is the derived fact that the app is closed
 * ([AgeGateMarker]), so work the system starts in a fresh process stays stopped too.
 */
internal enum class AgeCategory { ADULT_OR_UNKNOWN, TEEN, UNDER_13 }

/**
 * Reduces an age band to the one category the rule needs.
 *
 * A band that reaches below 13 counts as under 13, because it cannot establish 13 and over. A band
 * that starts at 13 to 17 counts as a teen. Everything else, including no band at all, is
 * adult-or-unknown.
 */
internal fun ageCategoryOf(lower: Int?, upper: Int?): AgeCategory = when {
    lower == null && upper == null -> AgeCategory.ADULT_OR_UNKNOWN
    (lower ?: 0) < 13 -> AgeCategory.UNDER_13
    (lower ?: 0) < 18 -> AgeCategory.TEEN
    else -> AgeCategory.ADULT_OR_UNKNOWN
}

/** What one launch check found, already reduced. */
internal sealed interface AgeSignalAnswer {
    /** The store says no law here requires the check. */
    data object NotRequired : AgeSignalAnswer

    /** The check applies, and no age was shared. */
    data object NotShared : AgeSignalAnswer

    /** The check applies, and the store needs the person to confirm their age with it first. */
    data object VerificationRequired : AgeSignalAnswer

    data class Shared(
        val category: AgeCategory,
        /** A parent has not approved, or has declined, a significant change to the app. */
        val parentApprovalOutstanding: Boolean,
    ) : AgeSignalAnswer
}

internal enum class AgeGateOutcome {
    RUN,

    /** 13 to 17, with a parent's approval of a significant change outstanding. */
    HOLD,

    /** Under 13. */
    STOP,

    /** The store needs the person to confirm their age. */
    VERIFY,
    ;

    val closesApp: Boolean get() = this != RUN
}

/** A null answer is a check that failed after its retries: the app carries on. */
internal fun ageGateOutcome(answer: AgeSignalAnswer?): AgeGateOutcome = when (answer) {
    null, AgeSignalAnswer.NotRequired, AgeSignalAnswer.NotShared -> AgeGateOutcome.RUN
    AgeSignalAnswer.VerificationRequired -> AgeGateOutcome.VERIFY
    is AgeSignalAnswer.Shared -> when (answer.category) {
        AgeCategory.UNDER_13 -> AgeGateOutcome.STOP
        AgeCategory.TEEN -> if (answer.parentApprovalOutstanding) AgeGateOutcome.HOLD else AgeGateOutcome.RUN
        AgeCategory.ADULT_OR_UNKNOWN -> AgeGateOutcome.RUN
    }
}

internal enum class AgeSignalAccess { SHARED, NOT_SHARED, VERIFICATION_REQUIRED, UNSPECIFIED }

/**
 * The store, one blocking call per step. Each call runs off the main thread and throws
 * [AgeSignalFailure] when the store cannot answer.
 */
internal interface AgeSignalStore {
    fun mandatorySharingApplies(): Boolean
    fun access(): AgeSignalAccess
    fun sharedSignal(): AgeSignalAnswer.Shared
}

internal class AgeSignalFailure(val transient: Boolean) : Exception()

/**
 * One pass over the store. ⚠ Nothing past the first step is asked where the store does not say the
 * law requires it: elsewhere the access step can show the store's own sharing prompt, and the rule
 * is no new prompt there.
 */
internal fun askAgeSignalStore(store: AgeSignalStore): AgeSignalAnswer {
    if (!store.mandatorySharingApplies()) return AgeSignalAnswer.NotRequired
    return when (store.access()) {
        AgeSignalAccess.SHARED -> store.sharedSignal()
        AgeSignalAccess.VERIFICATION_REQUIRED -> AgeSignalAnswer.VerificationRequired
        AgeSignalAccess.NOT_SHARED, AgeSignalAccess.UNSPECIFIED -> AgeSignalAnswer.NotShared
    }
}

/** Waits between attempts after a transient failure. Three retries, so four attempts at most. */
internal val AGE_SIGNAL_RETRY_DELAYS_MS = listOf(250L, 500L, 1_000L)

/**
 * Asks, retrying a transient failure a bounded number of times. A failure that will not change on
 * retry (no store, an app the store does not own, an unsupported device) is not retried. Null means
 * no answer, and the app carries on.
 */
internal fun askAgeSignalWithRetry(
    store: AgeSignalStore,
    sleep: (Long) -> Unit,
    delaysMs: List<Long> = AGE_SIGNAL_RETRY_DELAYS_MS,
): AgeSignalAnswer? {
    var attempt = 0
    while (true) {
        try {
            return askAgeSignalStore(store)
        } catch (failure: AgeSignalFailure) {
            if (!failure.transient || attempt >= delaysMs.size) return null
            sleep(delaysMs[attempt])
            attempt += 1
        }
    }
}

/** The one fact that outlives a check: whether the app is closed. Never an age. */
internal interface AgeGateMarker {
    fun closed(): Boolean

    /** ⚠ Durable before it returns: the process may be stopped right after. */
    fun write(closed: Boolean): Boolean
}

/**
 * Applies a launch check's outcome. A closing outcome is written before anything else happens, so
 * every gate reads it closed; then whatever already runs in this process is stopped. An outcome
 * that runs opens the marker again. Returns whether the marker took the write.
 */
internal fun applyAgeGateOutcome(
    outcome: AgeGateOutcome,
    marker: AgeGateMarker,
    workAlreadyRunning: () -> Boolean,
    stopRunningWork: () -> Unit,
): Boolean {
    if (!outcome.closesApp) return marker.write(false)
    val written = marker.write(true)
    if (workAlreadyRunning()) stopRunningWork()
    return written
}

/** What every background door asks. Off means the gate is never consulted at all. */
internal fun ageGateClosed(enabled: Boolean, marker: AgeGateMarker?): Boolean =
    enabled && marker?.closed() == true
