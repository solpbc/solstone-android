// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.gate

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class GateSemanticCommitmentTest {
    private val a = GateSemanticSegment(
        "segment-a",
        listOf(
            GateSemanticFile("b", "BB", "present", null),
            GateSemanticFile("a", "AA", "processed", "submitted"),
        ),
    )
    private val b = GateSemanticSegment("segment-b", emptyList())

    @Test
    fun orderingDoesNotChangeCanonicalDigest() {
        val reordered = a.copy(files = a.files.reversed())
        assertEquals(semanticCommitmentSha256(listOf(a, b)), semanticCommitmentSha256(listOf(b, reordered)))
    }

    @Test
    fun changedParsedMiddleFactChangesDigest() {
        val changed = a.copy(files = a.files.toMutableList().also { it[1] = it[1].copy(status = "missing") })
        assertNotEquals(semanticCommitmentSha256(listOf(a, b)), semanticCommitmentSha256(listOf(changed, b)))
    }

    @Test
    fun nullAndEmptyOptionalValuesCanonicalizeIdentically() {
        val empty = a.copy(files = a.files.map { it.copy(submittedName = it.submittedName ?: "") })
        assertEquals(semanticCommitmentSha256(listOf(a)), semanticCommitmentSha256(listOf(empty)))
    }
}
