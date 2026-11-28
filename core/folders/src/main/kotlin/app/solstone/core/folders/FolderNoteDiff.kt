// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.folders

private const val MAX_LCS_CELLS = 250_000

/**
 * Computes the net line diff between [before] and [after] for a watched note.
 * Returns null when the net change is empty (e.g., edited and reverted within the same
 * 5-minute window).
 */
fun netLineDiff(before: String, after: String): String? {
    if (before == after) return null
    val oldLines = before.split('\n')
    val newLines = after.split('\n')

    var start = 0
    while (start < oldLines.size && start < newLines.size && oldLines[start] == newLines[start]) {
        start++
    }
    var oldEnd = oldLines.size
    var newEnd = newLines.size
    while (oldEnd > start && newEnd > start && oldLines[oldEnd - 1] == newLines[newEnd - 1]) {
        oldEnd--
        newEnd--
    }
    if (start == oldEnd && start == newEnd) return null

    val oldSlice = oldLines.subList(start, oldEnd)
    val newSlice = newLines.subList(start, newEnd)

    val out = StringBuilder()
    if (oldSlice.size.toLong() * newSlice.size.toLong() <= MAX_LCS_CELLS) {
        val m = oldSlice.size
        val n = newSlice.size
        val dp = Array(m + 1) { IntArray(n + 1) }
        for (i in m - 1 downTo 0) {
            for (j in n - 1 downTo 0) {
                dp[i][j] = if (oldSlice[i] == newSlice[j]) {
                    dp[i + 1][j + 1] + 1
                } else {
                    maxOf(dp[i + 1][j], dp[i][j + 1])
                }
            }
        }
        var i = 0
        var j = 0
        while (i < m && j < n) {
            when {
                oldSlice[i] == newSlice[j] -> {
                    i++
                    j++
                }
                dp[i + 1][j] >= dp[i][j + 1] -> {
                    out.append('-').append(oldSlice[i]).append('\n')
                    i++
                }
                else -> {
                    out.append('+').append(newSlice[j]).append('\n')
                    j++
                }
            }
        }
        while (i < m) {
            out.append('-').append(oldSlice[i]).append('\n')
            i++
        }
        while (j < n) {
            out.append('+').append(newSlice[j]).append('\n')
            j++
        }
    } else {
        for (line in oldSlice) out.append('-').append(line).append('\n')
        for (line in newSlice) out.append('+').append(line).append('\n')
    }
    return out.toString().takeIf { it.isNotEmpty() }
}
