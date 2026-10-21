// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.identity.file

import app.solstone.core.identity.atomicWriteOwnerOnly
import app.solstone.core.pl.HandshakeStop
import app.solstone.core.pl.RefusalRecord
import app.solstone.core.pl.RefusalRecordRead
import app.solstone.core.pl.RefusalRecordStore
import app.solstone.core.pl.parseJson
import app.solstone.core.pl.toJson
import java.io.File

/**
 * The handshake-refusal record in a file of its own, keyed on the client certificate fingerprint.
 *
 * A missing file is [RefusalRecordRead.Absent]. A file that exists but cannot be read or parsed is
 * [RefusalRecordRead.Failed], never absent, so a record that could not be read is never mistaken
 * for zero and written over.
 */
class FileRefusalRecordStore(private val file: File) : RefusalRecordStore {
    override fun read(): RefusalRecordRead {
        if (!file.exists()) return RefusalRecordRead.Absent
        return try {
            RefusalRecordRead.Present(decode(file.readText(Charsets.UTF_8)))
        } catch (e: Exception) {
            RefusalRecordRead.Failed(e)
        }
    }

    override fun write(record: RefusalRecord) {
        val body = mapOf(
            "version" to 1L,
            "credential" to record.credential,
            "refusals" to record.refusals.toLong(),
            "last_counted_at_ms" to record.lastCountedAtMillis,
            "stop" to record.stop?.let(::stopKey),
        )
        atomicWriteOwnerOnly(file, toJson(body).toByteArray(Charsets.UTF_8))
    }

    private fun decode(text: String): RefusalRecord {
        val root = parseJson(text) as? Map<*, *> ?: error("refusal record is not an object")
        require(wholeNumber(root["version"]) == 1L) { "unknown refusal record version" }
        val credential = root["credential"] as? String ?: error("refusal record has no credential")
        val refusals = wholeNumber(root["refusals"])?.toInt() ?: error("refusal record has no count")
        require(refusals >= 0) { "negative refusal count" }
        val lastCounted = root["last_counted_at_ms"]?.let { wholeNumber(it) ?: error("refusal record time is not a number") }
        val stop = when (val value = root["stop"]) {
            null -> null
            is String -> stopFromKey(value)
            else -> error("refusal record stop is not a string")
        }
        return RefusalRecord(credential, refusals, lastCounted, stop)
    }

    private fun wholeNumber(value: Any?): Long? = when (value) {
        is java.math.BigDecimal -> value.longValueExact()
        is Long -> value
        is Int -> value.toLong()
        else -> null
    }

    private fun stopKey(stop: HandshakeStop): String = when (stop) {
        HandshakeStop.TLS_ACCESS_DENIED -> "tls_access_denied"
        HandshakeStop.REFUSALS_EXHAUSTED -> "refusals_exhausted"
    }

    private fun stopFromKey(key: String): HandshakeStop = when (key) {
        "tls_access_denied" -> HandshakeStop.TLS_ACCESS_DENIED
        "refusals_exhausted" -> HandshakeStop.REFUSALS_EXHAUSTED
        else -> error("unknown stop")
    }
}
