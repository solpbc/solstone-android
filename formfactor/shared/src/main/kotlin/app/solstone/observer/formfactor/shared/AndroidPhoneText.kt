// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc
package app.solstone.observer.formfactor.shared

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import app.solstone.core.i18n.PhoneText
import app.solstone.core.i18n.PhoneTextReader
import java.util.Locale

/** Resources selects a supported bundle locale before its plural rules are applied. */
class AndroidPhoneText(private val context: Context) : PhoneTextReader {
    fun resources(): Resources {
        val current = context.resources
        val selected = Locale.forLanguageTag(current.getString(R.string.phone_catalog_locale))
        if (current.configuration.locales[0].toLanguageTag() == selected.toLanguageTag()) return current
        return context.createConfigurationContext(Configuration(current.configuration).apply { setLocale(selected) }).resources
    }

    override fun render(id: String, key: String, quantity: Int?, arguments: Array<out Any?>): String {
        val res = resources()
        val resource = phoneResource(key)
        if (res.getResourceTypeName(resource) == "raw") {
            val template = res.openRawResource(resource).bufferedReader(Charsets.UTF_8).use { it.readText() }
            return String.format(res.configuration.locales[0], template, *arguments)
        }
        return if (quantity == null) res.getString(resource, *arguments)
        else res.getQuantityString(resource, quantity, *arguments)
    }

    fun install() {
        PhoneText.reader = this
        PhoneText.locale = { resources().configuration.locales[0] }
    }
}
