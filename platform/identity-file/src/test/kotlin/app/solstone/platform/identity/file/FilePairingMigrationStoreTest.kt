// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.identity.file

import app.solstone.core.identity.PairingGeneration
import app.solstone.core.identity.StoreInspectResult
import app.solstone.core.pl.PairingMigrationChoice
import app.solstone.core.pl.PairingMigrationRecord
import app.solstone.core.pl.PairingMigrationStage
import app.solstone.core.pl.TARGET_UNAVAILABLE_REASON
import app.solstone.core.pl.canonicalMigrationDecisionPayload
import app.solstone.core.pl.parseJson
import app.solstone.core.pl.toJson
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FilePairingMigrationStoreTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun completeCanonicalOperationRoundTripsBeforeSubmission() {
        val cid = "sha256:${"a".repeat(64)}"
        val target = "sha256:${"b".repeat(64)}"
        val operation = "123e4567-e89b-42d3-a456-426614174004"
        val payload = canonicalMigrationDecisionPayload(operation, PairingMigrationChoice.REPLACE, target)
        val file = File(temp.root, "pairing_migration.json")
        val store = FilePairingMigrationStore(file)
        val record = PairingMigrationRecord(
            generation = PairingGeneration("journal-1", cid),
            callerCid = cid,
            stage = PairingMigrationStage.READY_TO_SUBMIT,
            requestSequence = 7,
            selectedCid = target,
            choice = PairingMigrationChoice.REPLACE,
            operationId = operation,
            canonicalPutPayload = payload.toString(Charsets.UTF_8),
        )

        store.save(record)
        assertEquals(record, assertIs<StoreInspectResult.Ready<PairingMigrationRecord>>(store.inspect()).value)
        assertTrue(file.readText().contains("canonical_put_payload"))
    }

    @Test
    fun duplicateKeysAndMalformedPayloadAreUnreadable() {
        val file = File(temp.root, "pairing_migration.json")
        val cid = "sha256:${"a".repeat(64)}"
        FilePairingMigrationStore(file).save(
            PairingMigrationRecord(PairingGeneration("journal-1", cid), cid, PairingMigrationStage.OFFER_NOT_SHOWN),
        )
        file.writeText(file.readText().replace("\"version\":1", "\"version\":1,\"version\":1"))
        assertIs<StoreInspectResult.Unreadable>(FilePairingMigrationStore(file).inspect())

        val operation = "123e4567-e89b-42d3-a456-426614174004"
        val payload = canonicalMigrationDecisionPayload(operation, PairingMigrationChoice.KEEP_BOTH, null)
        val encoded = payload.toString(Charsets.UTF_8)
        FilePairingMigrationStore(file).save(
            PairingMigrationRecord(
                generation = PairingGeneration("journal-1", cid),
                callerCid = cid,
                stage = PairingMigrationStage.READY_TO_SUBMIT,
                choice = PairingMigrationChoice.KEEP_BOTH,
                operationId = operation,
                canonicalPutPayload = encoded,
            ),
        )
        val root = parseJson(file.readText()) as MutableMap<*, *>
        @Suppress("UNCHECKED_CAST")
        (root as MutableMap<String, Any?>)["canonical_put_payload"] = "wrong"
        file.writeText(toJson(root))
        assertIs<StoreInspectResult.Unreadable>(FilePairingMigrationStore(file).inspect())
    }

    @Test
    fun exactMissingTargetRecoveryRoundTripsButCannotKeepSubmittedOperation() {
        val file = File(temp.root, "pairing_migration.json")
        val cid = "sha256:${"a".repeat(64)}"
        val store = FilePairingMigrationStore(file)
        val record = PairingMigrationRecord(
            generation = PairingGeneration("journal-1", cid),
            callerCid = cid,
            stage = PairingMigrationStage.SHOWN_DEFERRED,
            refusalReason = TARGET_UNAVAILABLE_REASON,
        )
        store.save(record)
        assertEquals(record, assertIs<StoreInspectResult.Ready<PairingMigrationRecord>>(store.inspect()).value)

        val operation = "123e4567-e89b-42d3-a456-426614174004"
        val submitted = record.copy(
            choice = PairingMigrationChoice.REPLACE,
            selectedCid = "sha256:${"b".repeat(64)}",
            operationId = operation,
            canonicalPutPayload = canonicalMigrationDecisionPayload(
                operation,
                PairingMigrationChoice.REPLACE,
                "sha256:${"b".repeat(64)}",
            ).toString(Charsets.UTF_8),
        )
        assertTrue(runCatching { store.save(submitted) }.isFailure)
    }
}
