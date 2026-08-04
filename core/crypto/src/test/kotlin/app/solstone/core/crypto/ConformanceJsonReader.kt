// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.crypto

internal fun parseConformanceJson(text: String): Any? = ConformanceJsonReader(text).parse()

private class ConformanceJsonReader(private val text: String) {
    private var index = 0

    fun parse(): Any? = readValue().also {
        whitespace()
        require(index == text.length) { "trailing JSON content at $index" }
    }

    private fun readValue(): Any? {
        whitespace()
        require(index < text.length) { "unexpected end of JSON" }
        return when (text[index]) {
            '{' -> readObject()
            '[' -> readArray()
            '"' -> readString()
            't' -> literal("true", true)
            'f' -> literal("false", false)
            'n' -> literal("null", null)
            '-', in '0'..'9' -> readInteger()
            else -> error("unexpected JSON character at $index")
        }
    }

    private fun readObject(): Map<String, Any?> {
        expect('{')
        whitespace()
        val result = linkedMapOf<String, Any?>()
        if (consume('}')) return result
        while (true) {
            whitespace()
            require(index < text.length && text[index] == '"') { "expected object key at $index" }
            result[readString()] = run {
                whitespace()
                expect(':')
                readValue()
            }
            whitespace()
            if (consume('}')) return result
            expect(',')
        }
    }

    private fun readArray(): List<Any?> {
        expect('[')
        whitespace()
        val result = mutableListOf<Any?>()
        if (consume(']')) return result
        while (true) {
            result += readValue()
            whitespace()
            if (consume(']')) return result
            expect(',')
        }
    }

    private fun readString(): String {
        expect('"')
        val result = StringBuilder()
        while (index < text.length) {
            when (val character = text[index++]) {
                '"' -> return result.toString()
                '\\' -> result.append(
                    when (val escaped = next()) {
                        '"', '\\', '/' -> escaped
                        'b' -> '\b'
                        'f' -> '\u000c'
                        'n' -> '\n'
                        'r' -> '\r'
                        't' -> '\t'
                        'u' -> unicode()
                        else -> error("invalid JSON escape $escaped")
                    },
                )
                else -> {
                    require(character.code >= 0x20) { "control character in JSON string" }
                    result.append(character)
                }
            }
        }
        error("unterminated JSON string")
    }

    private fun unicode(): Char {
        require(index + 4 <= text.length) { "short JSON unicode escape" }
        val value = text.substring(index, index + 4).toInt(16)
        index += 4
        return value.toChar()
    }

    private fun readInteger(): Long {
        val start = index
        consume('-')
        if (!consume('0')) digits()
        require(index == text.length || text[index] !in charArrayOf('.', 'e', 'E')) { "non-integral JSON number at $index" }
        return text.substring(start, index).toLong()
    }

    private fun digits() {
        val start = index
        while (index < text.length && text[index].isDigit()) index += 1
        require(start != index) { "expected JSON digits at $index" }
    }

    private fun literal(expected: String, value: Any?): Any? {
        require(text.startsWith(expected, index)) { "expected $expected at $index" }
        index += expected.length
        return value
    }

    private fun whitespace() {
        while (index < text.length && text[index].isWhitespace()) index += 1
    }

    private fun expect(character: Char) {
        require(consume(character)) { "expected $character at $index" }
    }

    private fun consume(character: Char): Boolean =
        (index < text.length && text[index] == character).also { if (it) index += 1 }

    private fun next(): Char {
        require(index < text.length) { "unexpected end of JSON" }
        return text[index++]
    }
}
