// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.harness

import app.solstone.core.model.ReasonCode

/**
 * The one reason → diagnosis table. A renderer adds its own **action**; never its own diagnosis.
 *
 * 🔴 **This exists because the same table was rendered from two independent copies of the strings,
 * and one of them went stale without anything noticing.** A September sweep corrected
 * `FOREGROUND_START_NOT_ALLOWED` in the phone's source-detail renderer and left the other copy
 * reading `open sol to resume observing` — a product name that no longer exists — on a surface the
 * shipped shell could reach. Its test asserted that string as correct, so the gate defended it.
 *
 * ⛔ **Do not add a local string for any of these anywhere.** Two copies of a locked table is not a
 * duplication smell to tidy up later; it is a standing guarantee that one of the two is wrong.
 *
 * `null` means the reason adds nothing the state word does not already carry: repeating the state
 * as a reason is noise, so `DESIRED_OFF` and `NONE` render as the bare state word.
 */
fun reasonDiagnosis(reason: ReasonCode): String? = when (reason) {
    ReasonCode.PERMISSION_REVOKED -> "permissions needed"
    ReasonCode.AUTH_REVOKED -> "access to your journal was revoked"
    ReasonCode.SERVICE_KILLED -> "intake was stopped by the system"
    ReasonCode.STORAGE_FULL -> "storage is full"
    ReasonCode.UNPAIRED -> "not paired with your journal"
    ReasonCode.PROVIDER_SILENT -> "nothing has come in recently"
    ReasonCode.REBOOTED -> "this device restarted and intake didn't resume on its own"
    ReasonCode.TRANSPORT_UNAVAILABLE -> "can't reach your journal"
    ReasonCode.FOREGROUND_START_NOT_ALLOWED -> "intake couldn't start from the background"
    ReasonCode.FOREGROUND_TYPE_NOT_HELD -> "this source needs intake to restart"
    ReasonCode.PERSISTENCE_FAILED -> "couldn't save journal access on this phone"
    ReasonCode.DESIRED_OFF,
    ReasonCode.NONE -> null
}
