// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.gate

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest

data class GateSemanticFile(
    val name: String,
    val sha256: String,
    val status: String?,
    val submittedName: String?,
)

data class GateSemanticSegment(val key: String, val files: List<GateSemanticFile>)

fun semanticCommitmentSha256(segments: List<GateSemanticSegment>): String {
    val bytes = ByteArrayOutputStream()
    DataOutputStream(bytes).use { out ->
        out.write("SPLGATE-SEGMENTS-V1\n".toByteArray(Charsets.US_ASCII))
        val sortedSegments = segments.sortedWith(compareByUtf8 { it.key })
        out.writeInt(sortedSegments.size)
        sortedSegments.forEach { segment ->
            out.writeLengthPrefixed(segment.key)
            val files = segment.files.sortedWith(
                compareByUtf8<GateSemanticFile> { it.name }
                    .then(compareByUtf8 { it.sha256.lowercase() })
                    .then(compareByUtf8 { it.status.orEmpty() })
                    .then(compareByUtf8 { it.submittedName.orEmpty() }),
            )
            out.writeInt(files.size)
            files.forEach { file ->
                out.writeLengthPrefixed(file.name)
                out.writeLengthPrefixed(file.sha256.lowercase())
                out.writeLengthPrefixed(file.status.orEmpty())
                out.writeLengthPrefixed(file.submittedName.orEmpty())
            }
        }
    }
    return MessageDigest.getInstance("SHA-256")
        .digest(bytes.toByteArray())
        .joinToString("") { "%02x".format(it) }
}

private fun DataOutputStream.writeLengthPrefixed(value: String) {
    val encoded = value.toByteArray(Charsets.UTF_8)
    writeInt(encoded.size)
    write(encoded)
}

private fun <T> compareByUtf8(selector: (T) -> String): Comparator<T> =
    Comparator { left, right -> compareUtf8(selector(left), selector(right)) }

private fun compareUtf8(left: String, right: String): Int {
    val a = left.toByteArray(Charsets.UTF_8)
    val b = right.toByteArray(Charsets.UTF_8)
    for (index in 0 until minOf(a.size, b.size)) {
        val compared = (a[index].toInt() and 0xff).compareTo(b[index].toInt() and 0xff)
        if (compared != 0) return compared
    }
    return a.size.compareTo(b.size)
}
