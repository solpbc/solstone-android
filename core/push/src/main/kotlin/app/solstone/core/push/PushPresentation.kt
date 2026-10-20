// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.push

import app.solstone.core.pl.parseJson
import java.math.BigDecimal

data class PushCatalog(
    val sourceLocale: String,
    val locales: List<String>,
    val messages: Map<String, PushMessageSpec>,
)

data class PushMessageSpec(
    val id: String,
    val arguments: Map<String, PushArgumentSpec>,
    val selector: PushSelectorSpec?,
)

data class PushArgumentSpec(
    val name: String,
    val type: String,
    val numericStyle: String?,
    val min: BigDecimal?,
    val max: BigDecimal?,
)

data class PushSelectorSpec(
    val type: String,
    val enumValues: List<String>,
    val enumDefault: String?,
)

data class PushRenderCall(
    val messageId: String,
    val isCardinal: Boolean,
    val quantity: Int?,
    val enumArg: String?,
    val numericStyle: String?,
)

fun parsePushCatalog(irJson: String): PushCatalog {
    val root = parseJson(irJson) as? Map<*, *> ?: error("Invalid IR JSON root")
    val sourceLocale = root["source_locale"] as? String ?: ""
    val locales = (root["locales"] as? List<*>)?.filterIsInstance<String>() ?: emptyList()
    val rawMessages = root["messages"] as? List<*> ?: emptyList<Any?>()

    val messages = mutableMapOf<String, PushMessageSpec>()
    for (m in rawMessages) {
        val mMap = m as? Map<*, *> ?: continue
        val id = mMap["id"] as? String ?: continue

        val rawArgs = mMap["arguments"] as? List<*> ?: emptyList<Any?>()
        val arguments = mutableMapOf<String, PushArgumentSpec>()
        for (a in rawArgs) {
            val aMap = a as? Map<*, *> ?: continue
            val name = aMap["name"] as? String ?: continue
            val type = aMap["type"] as? String ?: continue
            val numericStyle = aMap["numeric_style"] as? String
            val min = aMap["min"] as? BigDecimal
            val max = aMap["max"] as? BigDecimal
            arguments[name] = PushArgumentSpec(name, type, numericStyle, min, max)
        }

        val selector = (mMap["selector"] as? Map<*, *>)?.let { sMap ->
            val type = sMap["type"] as? String ?: ""
            val enumValues = (sMap["enum_values"] as? List<*>)?.filterIsInstance<String>() ?: emptyList()
            val enumDefault = sMap["enum_default"] as? String
            PushSelectorSpec(type, enumValues, enumDefault)
        }

        messages[id] = PushMessageSpec(id, arguments, selector)
    }

    return PushCatalog(sourceLocale, locales, messages)
}

private val CARDINAL_MIN = BigDecimal(Int.MIN_VALUE)
private val CARDINAL_MAX = BigDecimal(Int.MAX_VALUE)
private val JS_EXACT_MIN = BigDecimal("-9007199254740991")
private val JS_EXACT_MAX = BigDecimal("9007199254740991")

fun applyPresentation(
    legacyTitle: String,
    legacyBody: String,
    presentation: Any?,
    catalog: PushCatalog,
    render: (PushRenderCall) -> String,
): Pair<String, String> {
    if (presentation !is Map<*, *>) {
        return legacyTitle to legacyBody
    }

    val title = applySide(legacyTitle, presentation["title"], catalog, render)
    val body = applySide(legacyBody, presentation["body"], catalog, render)
    return title to body
}

private fun applySide(
    legacy: String,
    entry: Any?,
    catalog: PushCatalog,
    render: (PushRenderCall) -> String,
): String {
    if (entry == null) return legacy
    if (entry !is Map<*, *>) return legacy

    val fallbackEn = entry["fallback_en"] as? String ?: return legacy
    fun fail(): String = fallbackEn

    val v = entry["v"] as? BigDecimal ?: return fail()
    if (v.compareTo(BigDecimal.ONE) != 0) return fail()

    val epoch = entry["catalog_epoch"] as? BigDecimal ?: return fail()
    if (epoch.compareTo(BigDecimal.ONE) != 0) return fail()

    val code = entry["code"] as? String ?: return fail()
    val args = entry["args"] as? Map<*, *> ?: return fail()

    val spec = catalog.messages[code] ?: return fail()

    val argKeys = args.keys.mapNotNull { it as? String }.toSet()
    if (argKeys.size != args.size) return fail()
    if (argKeys != spec.arguments.keys) return fail()

    var cardinalQuantity: Int? = null
    var cardinalStyle: String? = null
    var enumArg: String? = null

    for ((argName, argSpec) in spec.arguments) {
        val rawVal = args[argName]
        when (argSpec.type) {
            "string" -> {
                val strVal = rawVal as? String ?: return fail()
                if (spec.selector?.type != "cardinal") {
                    enumArg = strVal
                }
            }
            "cardinal" -> {
                if (rawVal is Boolean) return fail()
                val numVal = rawVal as? BigDecimal ?: return fail()
                if (numVal.scale() > 0) return fail()
                if (numVal < CARDINAL_MIN || numVal > CARDINAL_MAX) return fail()
                if (argSpec.min != null && numVal.compareTo(argSpec.min) < 0) return fail()
                if (argSpec.max != null && numVal.compareTo(argSpec.max) > 0) return fail()
                cardinalQuantity = numVal.intValueExact()
                cardinalStyle = argSpec.numericStyle
            }
            "integer" -> {
                if (rawVal is Boolean) return fail()
                val numVal = rawVal as? BigDecimal ?: return fail()
                if (numVal.scale() > 0) return fail()
                if (numVal < JS_EXACT_MIN || numVal > JS_EXACT_MAX) return fail()
                if (argSpec.min != null && numVal.compareTo(argSpec.min) < 0) return fail()
                if (argSpec.max != null && numVal.compareTo(argSpec.max) > 0) return fail()
            }
            else -> return fail()
        }
    }

    val isCardinal = spec.selector?.type == "cardinal"
    return try {
        val call = PushRenderCall(
            messageId = code,
            isCardinal = isCardinal,
            quantity = cardinalQuantity,
            enumArg = enumArg,
            numericStyle = cardinalStyle,
        )
        render(call)
    } catch (_: Throwable) {
        fail()
    }
}
