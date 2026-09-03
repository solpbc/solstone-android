// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import app.solstone.observer.harness.LoadState

enum class PhoneStatusCapture {
    LOADING,
    FAILED,
    UNPAIRED,
    PAIRED_OFFLINE,
    PAIRED_CAUGHT_UP,
}

fun decodePhoneStatusCapture(raw: String?): PhoneStatusCapture? = when (raw) {
    "loading" -> PhoneStatusCapture.LOADING
    "failed" -> PhoneStatusCapture.FAILED
    "unpaired" -> PhoneStatusCapture.UNPAIRED
    "paired-offline" -> PhoneStatusCapture.PAIRED_OFFLINE
    "paired-caught-up" -> PhoneStatusCapture.PAIRED_CAUGHT_UP
    else -> null
}

fun resolvePhoneStatusCapture(
    raw: String?,
    debuggable: Boolean,
): LoadState<PhoneStatusSnapshot>? {
    if (!debuggable) return null
    return when (decodePhoneStatusCapture(raw)) {
        PhoneStatusCapture.LOADING -> LoadState.Loading
        PhoneStatusCapture.FAILED -> LoadState.Failed(CapturedPhoneStatusFailure)
        PhoneStatusCapture.UNPAIRED -> capturedStatusState(paired = false, online = false, pendingCount = 0)
        PhoneStatusCapture.PAIRED_OFFLINE -> capturedStatusState(paired = true, online = false, pendingCount = 1)
        PhoneStatusCapture.PAIRED_CAUGHT_UP -> capturedStatusState(paired = true, online = true, pendingCount = 0)
        null -> null
    }
}

private fun capturedStatusState(
    paired: Boolean,
    online: Boolean,
    pendingCount: Int,
): LoadState<PhoneStatusSnapshot> = LoadState.Loaded(
    PhoneStatusSnapshot(
        status = PhoneStatusModel(
            paired = paired,
            online = online,
            pendingCount = pendingCount,
            hasContentPending = false,
        ),
        waiting = emptyList(),
    ),
)

private object CapturedPhoneStatusFailure : IllegalStateException()
