// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.crypto

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters

private val MARK_SALT = "solstone-journal-mark-v1".toByteArray(Charsets.US_ASCII)
private const val MARK_MEMORY_KIB = 65_536
private const val MARK_ITERATIONS = 3
private const val MARK_PARALLELISM = 1
private const val MARK_DIGEST_BYTES = 32

/**
 * The argon2id digest a journal's mark is chosen from. It is deliberately costly: 64 MiB and a
 * noticeable pause on a phone, so call it off the main thread and keep what it gave.
 */
fun journalMarkDigest(jidBytes: ByteArray): ByteArray {
    val parameters = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
        .withVersion(Argon2Parameters.ARGON2_VERSION_13)
        .withMemoryAsKB(MARK_MEMORY_KIB)
        .withIterations(MARK_ITERATIONS)
        .withParallelism(MARK_PARALLELISM)
        .withSalt(MARK_SALT)
        .build()
    val generator = Argon2BytesGenerator()
    generator.init(parameters)
    val digest = ByteArray(MARK_DIGEST_BYTES)
    generator.generateBytes(jidBytes, digest)
    return digest
}
