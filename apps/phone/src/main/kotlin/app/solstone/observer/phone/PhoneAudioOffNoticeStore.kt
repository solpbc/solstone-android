// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone

import android.content.Context
import app.solstone.core.model.ReasonCode

class PhoneAudioOffNoticeStore(context: Context) {
    private val preferences =
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun read(): ReasonCode {
        immediateNotice?.let { return it }
        val reason = preferences.getString(KEY_REASON, null) ?: return ReasonCode.NONE
        return ReasonCode.entries.firstOrNull { it.name == reason } ?: ReasonCode.NONE
    }

    fun recordNotice(reason: ReasonCode) {
        // The failure that needs this warning may also prevent a preference write.
        // Keep it visible to the widget and the next screen in this process.
        immediateNotice = reason
        preferences.edit().putString(KEY_REASON, reason.name).commit()
    }

    fun clear() {
        immediateNotice = ReasonCode.NONE
        preferences.edit().remove(KEY_REASON).commit()
    }

    private companion object {
        @Volatile var immediateNotice: ReasonCode? = null
        const val PREFERENCES_NAME = "phone_audio_off_notice"
        const val KEY_REASON = "reason"
    }
}
