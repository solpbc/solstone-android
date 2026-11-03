// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.pl

import app.solstone.core.crypto.jidFromCaPem
import app.solstone.core.crypto.journalMarkDigest
import app.solstone.core.identity.JournalMark
import app.solstone.core.identity.JournalMarkIcon
import java.nio.ByteBuffer

private const val GLYPH_COUNT = 60
private const val COLOR_COUNT = 16
private const val WORD_COUNT = 7776
private const val ASSET_DIR = "mark_assets"

/** Spare heap a derivation needs: argon2's 64 MiB, plus what the JVM spends on holding it. */
private const val DERIVATION_HEAP_BYTES = 96L * 1024 * 1024

/** The mark of the journal whose CA this is. Costly: call it off the main thread, and keep it. */
fun journalMarkFromCaPem(caPem: String): JournalMark = journalMarkFromJid(jidFromCaPem(caPem))

/** The same mark `spl_core::mark::mark_from_jid` gives for [jid], from the same locked tables. */
fun journalMarkFromJid(jid: String): JournalMark {
    val assets = MarkAssets.load()
    val jidBytes = jidBytes(jid)
    requireHeapForDerivation()
    val words = digestWords(journalMarkDigest(jidBytes))

    val icon1 = pick(words[0], assets.glyphs.size)
    val icon2 = pickDistinct(words[1], assets.glyphs.size, icon1)
    val color1 = pick(words[2], assets.colors.size)
    val color2 = pickDistinct(words[3], assets.colors.size, color1)
    val word1 = pick(words[4], assets.words.size)
    val word2 = pickDistinct(words[5], assets.words.size, word1)

    return JournalMark(
        icon1 = assets.icon(icon1, color1, rot = if (words[6] and 1L == 0L) 0 else 45),
        icon2 = assets.icon(icon2, color2, rot = if ((words[6] shr 1) and 1L == 0L) 0 else 45),
        words = listOf(assets.words[word1], assets.words[word2]),
    )
}

// An out-of-memory error in a capture app can land on whichever thread allocates next, so a phone
// without the room reports the mark as unavailable rather than risking the rest of the app.
private fun requireHeapForDerivation() {
    val runtime = Runtime.getRuntime()
    fun spare() = runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory())
    if (spare() < DERIVATION_HEAP_BYTES) runtime.gc()
    check(spare() >= DERIVATION_HEAP_BYTES) { "not enough heap to derive the journal mark" }
}

private fun digestWords(digest: ByteArray): LongArray {
    val buffer = ByteBuffer.wrap(digest)
    return LongArray(7) { buffer.getInt().toLong() and 0xFFFF_FFFFL }
}

private fun pick(word: Long, count: Int): Int = (word % count).toInt()

private fun pickDistinct(word: Long, count: Int, excluded: Int): Int {
    val index = (word % (count - 1)).toInt()
    return if (index >= excluded) index + 1 else index
}

private fun jidBytes(jid: String): ByteArray {
    val hyphens = listOf(8, 13, 18, 23)
    require(jid.length == 36 && hyphens.all { jid[it] == '-' }) { "expected a hyphenated UUID" }
    val hex = jid.filterIndexed { index, _ -> index !in hyphens }
    return ByteArray(16) { index ->
        val high = Character.digit(hex[index * 2], 16)
        val low = Character.digit(hex[index * 2 + 1], 16)
        require(high >= 0 && low >= 0) { "expected hexadecimal digits" }
        ((high shl 4) or low).toByte()
    }
}

private class MarkAssets(
    val glyphs: List<Pair<String, String>>,
    val colors: List<Pair<String, String>>,
    val words: List<String>,
) {
    fun icon(glyph: Int, color: Int, rot: Int) = JournalMarkIcon(
        name = glyphs[glyph].first,
        svg = glyphs[glyph].second,
        colorName = colors[color].first,
        colorHex = colors[color].second,
        rot = rot,
    )

    companion object {
        // Index position is load-bearing, so these files are read in their own order, and their
        // sizes are checked against the locked ones.
        fun load(): MarkAssets {
            val glyphs = (parseJson(asset("glyphs.json")) as Map<*, *>).entries.map { (name, svg) ->
                name as String to svg as String
            }
            val colors = (parseJson(asset("colors.json")) as List<*>).map { entry ->
                val (name, hex) = entry as List<*>
                name as String to hex as String
            }
            val words = (parseJson(asset("words.json")) as List<*>).map { it as String }
            check(glyphs.size == GLYPH_COUNT && colors.size == COLOR_COUNT && words.size == WORD_COUNT) {
                "journal mark tables are not the locked sizes"
            }
            return MarkAssets(glyphs, colors, words)
        }

        private fun asset(name: String): String {
            val stream = checkNotNull(MarkAssets::class.java.classLoader?.getResourceAsStream("$ASSET_DIR/$name")) {
                "missing journal mark table $name"
            }
            return stream.use { it.readBytes().toString(Charsets.UTF_8) }
        }
    }
}
