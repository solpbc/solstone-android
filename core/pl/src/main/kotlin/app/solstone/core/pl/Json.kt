// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.pl

fun parseJson(text: String): Any? = JsonParser(text).parse()

fun toJson(value: Any?): String = when (value) {
    null -> "null"
    is String -> jsonString(value)
    is Boolean -> value.toString()
    is Number -> {
        val text = value.toString()
        require(text != "NaN" && text != "Infinity" && text != "-Infinity") { "invalid JSON number: $text" }
        text
    }
    is Map<*, *> -> value.entries.joinToString(separator = ",", prefix = "{", postfix = "}") { (key, item) ->
        require(key is String) { "JSON object keys must be strings" }
        jsonString(key) + ":" + toJson(item)
    }
    is Iterable<*> -> value.joinToString(separator = ",", prefix = "[", postfix = "]") { toJson(it) }
    is Array<*> -> value.joinToString(separator = ",", prefix = "[", postfix = "]") { toJson(it) }
    else -> throw IllegalArgumentException("unsupported JSON value: ${value::class}")
}

private fun jsonString(value: String): String {
    val out = StringBuilder(value.length + 2)
    out.append('"')
    for (char in value) {
        when (char) {
            '"' -> out.append("\\\"")
            '\\' -> out.append("\\\\")
            '\b' -> out.append("\\b")
            '\u000c' -> out.append("\\f")
            '\n' -> out.append("\\n")
            '\r' -> out.append("\\r")
            '\t' -> out.append("\\t")
            else -> {
                if (char.code < 0x20) {
                    out.append("\\u").append(char.code.toString(16).padStart(4, '0'))
                } else {
                    out.append(char)
                }
            }
        }
    }
    out.append('"')
    return out.toString()
}

private class JsonParser(private val text: String) {
    private var index = 0

    fun parse(): Any? {
        val value = readValue()
        skipWhitespace()
        if (index != text.length) {
            throw IllegalArgumentException("trailing JSON content at $index")
        }
        return value
    }

    private fun readValue(): Any? {
        skipWhitespace()
        if (index >= text.length) {
            throw IllegalArgumentException("unexpected end of JSON")
        }
        return when (text[index]) {
            '{' -> readObject()
            '[' -> readArray()
            '"' -> readString()
            't' -> readLiteral("true", true)
            'f' -> readLiteral("false", false)
            'n' -> readLiteral("null", null)
            '-', in '0'..'9' -> readNumber()
            else -> throw IllegalArgumentException("unexpected JSON char '${text[index]}' at $index")
        }
    }

    private fun readObject(): Map<String, Any?> {
        expect('{')
        skipWhitespace()
        val out = LinkedHashMap<String, Any?>()
        if (consume('}')) {
            return out
        }
        while (true) {
            skipWhitespace()
            if (index >= text.length || text[index] != '"') {
                throw IllegalArgumentException("expected JSON object key at $index")
            }
            val key = readString()
            skipWhitespace()
            expect(':')
            out[key] = readValue()
            skipWhitespace()
            if (consume('}')) {
                return out
            }
            expect(',')
        }
    }

    private fun readArray(): List<Any?> {
        expect('[')
        skipWhitespace()
        val out = ArrayList<Any?>()
        if (consume(']')) {
            return out
        }
        while (true) {
            out += readValue()
            skipWhitespace()
            if (consume(']')) {
                return out
            }
            expect(',')
        }
    }

    private fun readString(): String {
        expect('"')
        val out = StringBuilder()
        while (index < text.length) {
            val char = text[index++]
            when (char) {
                '"' -> return out.toString()
                '\\' -> {
                    if (index >= text.length) {
                        throw IllegalArgumentException("unterminated JSON escape")
                    }
                    out.append(
                        when (val escaped = text[index++]) {
                            '"' -> '"'
                            '\\' -> '\\'
                            '/' -> '/'
                            'b' -> '\b'
                            'f' -> '\u000c'
                            'n' -> '\n'
                            'r' -> '\r'
                            't' -> '\t'
                            'u' -> readUnicode()
                            else -> throw IllegalArgumentException("bad JSON escape: $escaped")
                        },
                    )
                }
                else -> {
                    if (char.code < 0x20) {
                        throw IllegalArgumentException("control char in JSON string")
                    }
                    out.append(char)
                }
            }
        }
        throw IllegalArgumentException("unterminated JSON string")
    }

    private fun readUnicode(): Char {
        if (index + 4 > text.length) {
            throw IllegalArgumentException("short JSON unicode escape")
        }
        val hex = text.substring(index, index + 4)
        index += 4
        return hex.toInt(16).toChar()
    }

    private fun readNumber(): Double {
        val start = index
        consume('-')
        if (consume('0')) {
            // A leading zero must stand alone before any fraction/exponent.
        } else {
            readDigits()
        }
        if (consume('.')) {
            readDigits()
        }
        if (index < text.length && (text[index] == 'e' || text[index] == 'E')) {
            index += 1
            if (index < text.length && (text[index] == '+' || text[index] == '-')) {
                index += 1
            }
            readDigits()
        }
        return text.substring(start, index).toDouble()
    }

    private fun readDigits() {
        val start = index
        while (index < text.length && text[index] in '0'..'9') {
            index += 1
        }
        if (start == index) {
            throw IllegalArgumentException("expected JSON digits at $index")
        }
    }

    private fun readLiteral(literal: String, value: Any?): Any? {
        if (!text.startsWith(literal, index)) {
            throw IllegalArgumentException("expected JSON literal $literal at $index")
        }
        index += literal.length
        return value
    }

    private fun skipWhitespace() {
        while (index < text.length && text[index].isWhitespace()) {
            index += 1
        }
    }

    private fun expect(char: Char) {
        if (!consume(char)) {
            throw IllegalArgumentException("expected '$char' at $index")
        }
    }

    private fun consume(char: Char): Boolean {
        if (index < text.length && text[index] == char) {
            index += 1
            return true
        }
        return false
    }
}
