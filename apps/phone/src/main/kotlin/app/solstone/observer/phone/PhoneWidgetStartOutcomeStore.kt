// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone

import android.content.Context
import app.solstone.core.model.ReasonCode
import app.solstone.observer.formfactor.phone.PhoneWidgetStartOutcome

class PhoneWidgetStartOutcomeStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun read(): PhoneWidgetStartOutcome {
        val reason = preferences.getString(KEY_REASON, null) ?: return PhoneWidgetStartOutcome.None
        return ReasonCode.entries.firstOrNull { it.name == reason }
            ?.let(PhoneWidgetStartOutcome::Refused)
            ?: PhoneWidgetStartOutcome.None
    }

    fun recordRefusal(reason: ReasonCode) {
        preferences.edit().putString(KEY_REASON, reason.name).apply()
    }

    fun clear() {
        preferences.edit().remove(KEY_REASON).apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "phone_widget_start_outcome"
        const val KEY_REASON = "reason"
    }
}
