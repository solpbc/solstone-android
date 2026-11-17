// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.harness

import app.solstone.core.i18n.phoneText

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
    ReasonCode.PERMISSION_REVOKED -> phoneText("android.source.permissions.needed")
    ReasonCode.AUTH_REVOKED -> phoneText("android.source.access.journal.was.revoked")
    ReasonCode.SERVICE_KILLED -> phoneText("android.source.intake.was.stopped.by.system")
    ReasonCode.STORAGE_FULL -> phoneText("android.source.storage.full")
    ReasonCode.UNPAIRED -> phoneText("android.source.not.paired.with.journal")
    ReasonCode.PROVIDER_SILENT -> phoneText("android.source.nothing.has.come.recently")
    ReasonCode.REBOOTED -> phoneText("android.source.device.restarted.intake.didn.t")
    ReasonCode.TRANSPORT_UNAVAILABLE -> phoneText("android.migration.can.t.reach.journal")
    ReasonCode.FOREGROUND_START_NOT_ALLOWED -> phoneText("android.source.intake.couldn.t.start.from")
    ReasonCode.FOREGROUND_TYPE_NOT_HELD -> phoneText("android.source.source.needs.intake.restart")
    ReasonCode.PERSISTENCE_FAILED -> phoneText("android.source.couldn.t.save.journal.access")
    ReasonCode.AUDIO_CHOICE_NOT_SAVED -> phoneText("android.source.audio.off.setting.may.not")
    ReasonCode.AUDIO_CHOICE_NOT_SAVED_STOP_NOT_DURABLE -> phoneText("android.source.audio.intake.may.resume.after")
    ReasonCode.AUDIO_CHOICE_SAVED_STOP_NOT_DURABLE -> phoneText("android.source.couldn.t.confirm.stop.was")
    ReasonCode.INTAKE_STOPPED_UNEXPECTEDLY -> phoneText("android.source.other.sources.stopped.unexpectedly")
    ReasonCode.MICROPHONE_SILENCED -> phoneText("android.source.another.app.has.microphone.or")
    ReasonCode.SCREEN_CAPTURE_FAILED -> phoneText("android.source.recording.couldn.t.be.saved")
    ReasonCode.AUDIO_START_FAILED -> phoneText("android.source.intake.couldn.t.start")
    ReasonCode.DESIRED_OFF,
    ReasonCode.NONE -> null
}
