// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.calendar

import app.solstone.core.pl.parseJson

data class CalendarIntentRecord(
    val id: String,
    val day: String,
    val segment: String,
    val bytes: ByteArray,
    val sealed: Boolean,
    val identityKeys: List<Pair<String, String>>,
)

data class StageResult(
    val identities: List<CalendarIdentityRecord>,
    val intents: List<CalendarIntentRecord>,
)

data class ReopenStep(
    val intent: CalendarIntentRecord,
    val seal: Boolean,
)

/**
 * Writes a non-drainable intent that can rebuild the exact bytes, and records the pending
 * fingerprint. The published fingerprint is left where it was.
 */
fun stageNotes(
    identities: List<CalendarIdentityRecord>,
    intents: List<CalendarIntentRecord>,
    notes: List<CalendarNote>,
): StageResult {
    if (notes.isEmpty()) return StageResult(identities, intents)
    val files = encodeCalendarFiles(notes)
    val byKey = identities.associateBy { it.event to it.occurrence }.toMutableMap()
    val nextIntents = intents.toMutableList()
    files.forEach { file ->
        val lines = file.bytes.toString(Charsets.UTF_8).trimEnd('\n').split('\n').filter { it.isNotEmpty() }
        val keys = lines.map { line ->
            val parsed = parseJson(line) as Map<*, *>
            val event = parsed["event"] as String
            val occurrence = (parsed["occurrence"] as? String).orEmpty()
            event to occurrence
        }
        val existing = nextIntents.firstOrNull { !it.sealed && it.bytes.contentEquals(file.bytes) }
        val intent = existing ?: CalendarIntentRecord(
            id = file.name.removePrefix("calendar_").removeSuffix(".jsonl"),
            day = file.day,
            segment = file.segment,
            bytes = file.bytes,
            sealed = false,
            identityKeys = keys,
        )
        if (existing == null) nextIntents += intent
        keys.forEach { key ->
            val identity = byKey[key] ?: return@forEach
            byKey[key] = identity.copy(pendingIntentId = intent.id)
        }
    }
    return StageResult(byKey.values.sortedWith(compareBy({ it.event }, { it.occurrence })), nextIntents)
}

/** Copies the pending fingerprint onto the published one only after the bytes are durable. */
fun completeSeal(
    identities: List<CalendarIdentityRecord>,
    intents: List<CalendarIntentRecord>,
    intentId: String,
): StageResult {
    val intent = intents.firstOrNull { it.id == intentId } ?: return StageResult(identities, intents)
    val sealedIntents = intents.map { if (it.id == intentId) it.copy(sealed = true) else it }
    if (intent.sealed) return StageResult(identities, sealedIntents)
    val updated = identities.map { identity ->
        if (identity.pendingIntentId != intentId || identity.pendingCanonical == null) return@map identity
        val parsed = parseJson(identity.pendingCanonical) as Map<*, *>
        identity.copy(
            publishedFingerprint = identity.pendingFingerprint,
            publishedChangedAt = identity.pendingChangedAt,
            publishedChangedAtEpochMs = identity.pendingChangedAtEpochMs,
            lastTitle = parsed["title"] as? String,
            lastCalendar = parsed["calendar"] as? String,
            lastChange = parsed["change"] as? String,
            pendingFingerprint = null,
            pendingChangedAt = null,
            pendingChangedAtEpochMs = null,
            pendingCanonical = null,
            pendingIntentId = null,
        )
    }
    return StageResult(updated, sealedIntents)
}

fun reopenSteps(
    intents: List<CalendarIntentRecord>,
    segmentExists: (CalendarIntentRecord) -> Boolean,
): List<ReopenStep> =
    intents.filter { !it.sealed }.map { intent -> ReopenStep(intent, seal = !segmentExists(intent)) }
