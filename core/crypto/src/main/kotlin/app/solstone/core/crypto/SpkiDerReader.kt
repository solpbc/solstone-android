// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.crypto

internal class SpkiDerReader(private val bytes: ByteArray) {
    private var index = 0

    fun read(tag: Int): SpkiDerTlv {
        val start = index
        require(next() == tag) { "expected DER tag 0x${tag.toString(16)}" }
        val length = readLength()
        require(length <= bytes.size - index) { "truncated DER content" }
        val contentStart = index
        index += length
        return SpkiDerTlv(bytes.copyOfRange(start, index), bytes.copyOfRange(contentStart, index))
    }

    fun requireExhausted() {
        require(index == bytes.size) { "trailing DER content" }
    }

    private fun readLength(): Int {
        val first = next()
        if (first < 0x80) return first
        require(first != 0x80) { "indefinite DER length" }
        val count = first and 0x7f
        require(count in 1..4) { "unsupported DER length" }
        require(count <= bytes.size - index) { "truncated DER length" }
        require(bytes[index] != 0.toByte()) { "non-minimal DER length" }
        var length = 0L
        repeat(count) {
            length = (length shl 8) or next().toLong()
        }
        require(length >= 0x80) { "non-minimal DER length" }
        require(length <= Int.MAX_VALUE) { "DER length overflow" }
        return length.toInt()
    }

    private fun next(): Int {
        require(index < bytes.size) { "truncated DER" }
        return bytes[index++].toInt() and 0xff
    }
}

internal data class SpkiDerTlv(val encoded: ByteArray, val content: ByteArray)
