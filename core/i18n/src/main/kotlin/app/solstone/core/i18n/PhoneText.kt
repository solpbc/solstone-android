// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc
package app.solstone.core.i18n

import java.text.NumberFormat
import java.util.Locale

internal data class PhoneArgument(val name: String, val type: String, val numericStyle: String)
internal data class PhoneMessage(
    val key: String,
    val arguments: List<PhoneArgument>,
    val selector: String,
    val selectorType: String,
    val defaultVariant: String,
    val variants: Set<String>,
    val english: Map<String, Map<String, String>>,
)

/** The Android application installs its native reader; shared JVM tests use the same catalog. */
object PhoneText {
    @Volatile var reader: PhoneTextReader = PhoneTextReader { id, key, quantity, args ->
        val message = phoneMessages.getValue(id)
        val variant = if (message.selectorType == "string") {
            message.variants.firstOrNull { key.endsWith("_v" + it.toByteArray().joinToString("") { b -> "%02x".format(b) }) } ?: message.defaultVariant
        } else ""
        val forms = message.english.getValue(variant)
        String.format(Locale.US, forms[if (quantity == 1) "one" else "other"] ?: forms.getValue("other"), *args)
    }
    @Volatile var locale: () -> Locale = { Locale.US }
}

fun interface PhoneTextReader {
    fun render(id: String, key: String, quantity: Int?, arguments: Array<out Any?>): String
}

fun phoneLocale(): Locale = PhoneText.locale()

fun phoneText(id: String, vararg arguments: Pair<String, Any?>): String {
    val message = phoneMessages.getValue(id)
    val supplied = arguments.toMap()
    val variant = if (message.selectorType == "string") {
        // Kotlin's original nullable when expressions used their else branch for absent facts.
        supplied[message.selector]?.toString()?.takeIf { it in message.variants } ?: message.defaultVariant
    } else ""
    val key = if (message.selectorType == "string") message.key + "_v" + variant.toByteArray().joinToString("") { "%02x".format(it) } else message.key
    val quantity = if (message.selectorType == "cardinal") (supplied.getValue(message.selector) as Number).toLong().coerceIn(Int.MIN_VALUE.toLong(), Int.MAX_VALUE.toLong()).toInt() else null
    val values = message.arguments.map { arg ->
        val value = if (message.selectorType == "string" && arg.name == message.selector) supplied[arg.name] ?: variant else supplied.getValue(arg.name)
        when (arg.type) {
            "cardinal", "integer" -> NumberFormat.getIntegerInstance(supplied["_locale"] as? Locale ?: phoneLocale()).apply {
                isGroupingUsed = arg.numericStyle == "locale-formatted"
            }.format(value)
            else -> value?.toString() ?: "null"
        }
    }.toTypedArray()
    return PhoneText.reader.render(id, key, quantity, values)
}
