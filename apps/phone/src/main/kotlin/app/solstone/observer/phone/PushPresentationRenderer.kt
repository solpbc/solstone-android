// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import app.solstone.core.push.PushCatalog
import app.solstone.core.push.PushRenderCall
import app.solstone.core.push.applyPresentation
import solstone.i18n.Select
import java.util.Locale

class PushPresentationRenderer(
    private val context: Context,
    private val catalog: PushCatalog,
) {
    fun render(call: PushRenderCall): String {
        // Resources.getQuantityString picks the plural category from the configuration locale and then falls back the string values, so a fr-FR configuration would select English quantity="one" for zero.
        val res = resolveResources()

        val resName = Select.resolveResourceKey(call.messageId, call.enumArg)
        val resType = if (call.isCardinal) "plurals" else "string"
        val resId = res.getIdentifier(resName, resType, context.packageName)
        if (resId == 0) {
            error("Resource not found: $resName of type $resType in ${context.packageName}")
        }

        return if (call.isCardinal) {
            val q = call.quantity ?: error("Cardinal call missing quantity for ${call.messageId}")
            if (call.numericStyle != null && call.numericStyle != "ungrouped") {
                error("Unsupported numeric style '${call.numericStyle}' for ${call.messageId}")
            }
            res.getQuantityString(resId, q, Select.formatIntegerUngrouped(q.toLong()))
        } else {
            res.getString(resId)
        }
    }

    fun renderPresentation(title: String, body: String, presentation: Any?): Pair<String, String> {
        return applyPresentation(
            legacyTitle = title,
            legacyBody = body,
            presentation = presentation,
            catalog = catalog,
            render = ::render,
        )
    }

    private fun resolveResources(): Resources {
        val configuration = context.resources.configuration
        val localesList = configuration.locales

        val finalTag = context.getString(R.string.push_catalog_locale)
        val targetLocale = Locale.forLanguageTag(finalTag)
        val primary = if (localesList.isEmpty) null else localesList[0]

        return if (primary != null && primary == targetLocale) {
            context.resources
        } else {
            val newConfig = Configuration(configuration)
            newConfig.setLocale(targetLocale)
            context.createConfigurationContext(newConfig).resources
        }
    }
}
