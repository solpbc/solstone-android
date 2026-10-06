// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.platform.identity.file

import app.solstone.core.identity.PairingGeneration
import app.solstone.core.identity.PersistenceIssue
import app.solstone.core.identity.StoreInspectResult
import app.solstone.core.identity.atomicWriteOwnerOnly
import app.solstone.core.pl.PairingMigrationChoice
import app.solstone.core.pl.PairingMigrationRecord
import app.solstone.core.pl.PairingMigrationStage
import app.solstone.core.pl.PairingMigrationStore
import app.solstone.core.pl.TARGET_UNAVAILABLE_REASON
import app.solstone.core.pl.canonicalMigrationDecisionPayload
import app.solstone.core.pl.isDefiniteMigrationPrecommitRefusal
import app.solstone.core.pl.parseJson
import app.solstone.core.pl.toJson
import java.io.File

class FilePairingMigrationStore(private val file: File) : PairingMigrationStore {
    override fun inspect(): StoreInspectResult<PairingMigrationRecord> {
        if (!file.exists()) return StoreInspectResult.Missing
        return try {
            val root = parseJson(file.readText(Charsets.UTF_8)) as? Map<*, *>
                ?: return unreadable("migration root")
            if (root.keys != KEYS || (root["version"] as? Number)?.toString()?.toLongOrNull() != 1L) return unreadable("migration fields")
            val generation = PairingGeneration(
                instanceId = root.string("instance_id") ?: return unreadable("migration instance"),
                clientCertFingerprint = root.string("client_cert_fingerprint") ?: return unreadable("migration CID"),
            )
            if (generation.instanceId.isBlank()) return unreadable("migration instance")
            val callerCid = root.string("caller_cid") ?: return unreadable("migration caller")
            if (callerCid != generation.clientCertFingerprint || !CID.matches(callerCid)) return unreadable("migration generation mismatch")
            val stage = root.string("stage")?.let { runCatching { PairingMigrationStage.valueOf(it) }.getOrNull() }
                ?: return unreadable("migration stage")
            val choice = root.nullableString("choice")?.let { runCatching { PairingMigrationChoice.valueOf(it) }.getOrNull() }
            if (root.nullableString("choice") != null && choice == null) return unreadable("migration choice")
            val sequence = (root["request_sequence"] as? Number)?.toString()?.toLongOrNull()?.takeIf { it >= 0 }
                ?: return unreadable("migration sequence")
            val target = root.nullableString("selected_cid")
            if (target != null && !CID.matches(target)) return unreadable("migration target")
            val operationId = root.nullableString("operation_id")
            val payload = root.nullableString("canonical_put_payload")
            val echoMismatch = root["echo_mismatch"] as? Boolean ?: return unreadable("migration echo state")
            val refusal = root.nullableString("refusal_reason")
            when {
                stage == PairingMigrationStage.TERMINAL_REFUSED && !isDefiniteMigrationPrecommitRefusal(refusal) ->
                    return unreadable("migration refusal")
                refusal == TARGET_UNAVAILABLE_REASON && stage == PairingMigrationStage.SHOWN_DEFERRED &&
                    choice == null && operationId == null && payload == null && !echoMismatch && target == null -> Unit
                refusal == null -> Unit
                else -> return unreadable("unexpected migration refusal")
            }
            val requestStages = setOf(
                PairingMigrationStage.READY_TO_SUBMIT,
                PairingMigrationStage.SUBMITTED_UNKNOWN,
                PairingMigrationStage.TERMINAL_KEEP_BOTH,
                PairingMigrationStage.TERMINAL_REPLACED,
                PairingMigrationStage.TERMINAL_REFUSED,
            )
            if (stage in requestStages &&
                (operationId == null || payload == null || choice == null)
            ) return unreadable("migration request")
            if (stage !in requestStages && (operationId != null || payload != null || choice != null)) {
                return unreadable("unexpected migration request")
            }
            if (operationId != null || payload != null || choice != null) {
                if (operationId == null || payload == null || choice == null) return unreadable("migration request fields")
                val bytes = payload.toByteArray(Charsets.UTF_8)
                if (!bytes.contentEquals(canonicalMigrationDecisionPayload(operationId, choice, target))) {
                    return unreadable("migration request mismatch")
                }
            }
            if (stage == PairingMigrationStage.AWAITING_SELECTION && target == null) return unreadable("migration selection")
            if (stage == PairingMigrationStage.TERMINAL_KEEP_BOTH &&
                (choice != PairingMigrationChoice.KEEP_BOTH || target != null || echoMismatch)
            ) return unreadable("migration keep-both terminal")
            if (stage == PairingMigrationStage.TERMINAL_REPLACED &&
                (choice != PairingMigrationChoice.REPLACE || target == null || echoMismatch)
            ) return unreadable("migration replacement terminal")
            if (echoMismatch && stage != PairingMigrationStage.SUBMITTED_UNKNOWN) return unreadable("migration echo state")
            StoreInspectResult.Ready(
                PairingMigrationRecord(
                    generation = generation,
                    callerCid = callerCid,
                    stage = stage,
                    requestSequence = sequence,
                    selectedCid = target,
                    choice = choice,
                    operationId = operationId,
                    canonicalPutPayload = payload,
                    echoMismatch = echoMismatch,
                    refusalReason = refusal,
                ),
            )
        } catch (t: Throwable) {
            StoreInspectResult.Unreadable(PersistenceIssue.PERSISTENCE_FAILED, t.javaClass.simpleName)
        }
    }

    override fun save(record: PairingMigrationRecord) {
        require(record.callerCid == record.generation.clientCertFingerprint && CID.matches(record.callerCid))
        require(record.requestSequence >= 0)
        if (record.stage == PairingMigrationStage.AWAITING_SELECTION) require(record.selectedCid?.let(CID::matches) == true)
        when {
            record.stage == PairingMigrationStage.TERMINAL_REFUSED -> require(isDefiniteMigrationPrecommitRefusal(record.refusalReason))
            record.refusalReason == TARGET_UNAVAILABLE_REASON -> require(
                record.stage == PairingMigrationStage.SHOWN_DEFERRED && record.selectedCid == null &&
                    record.choice == null && record.operationId == null && record.canonicalPutPayload == null &&
                    !record.echoMismatch,
            )
            else -> require(record.refusalReason == null)
        }
        if (record.stage in setOf(PairingMigrationStage.READY_TO_SUBMIT, PairingMigrationStage.SUBMITTED_UNKNOWN)) {
            require(record.operationId != null && record.canonicalPutPayload != null && record.choice != null)
        }
        if (record.operationId != null || record.canonicalPutPayload != null || record.choice != null) {
            require(record.operationId != null && record.canonicalPutPayload != null && record.choice != null)
            val operationId = record.operationId ?: error("migration operation id missing")
            val choice = record.choice ?: error("migration choice missing")
            val bytes = record.canonicalPutPayload!!.toByteArray(Charsets.UTF_8)
            require(bytes.contentEquals(canonicalMigrationDecisionPayload(operationId, choice, record.selectedCid)))
        }
        val json = toJson(
            linkedMapOf(
                "version" to 1,
                "instance_id" to record.generation.instanceId,
                "client_cert_fingerprint" to record.generation.clientCertFingerprint,
                "caller_cid" to record.callerCid,
                "stage" to record.stage.name,
                "request_sequence" to record.requestSequence,
                "selected_cid" to record.selectedCid,
                "choice" to record.choice?.name,
                "operation_id" to record.operationId,
                "canonical_put_payload" to record.canonicalPutPayload,
                "echo_mismatch" to record.echoMismatch,
                "refusal_reason" to record.refusalReason,
            ),
        )
        atomicWriteOwnerOnly(file, json.toByteArray(Charsets.UTF_8))
    }

    override fun clear() {
        if (file.exists() && !file.delete()) throw IllegalStateException("could not clear migration state")
    }

    private fun unreadable(detail: String) =
        StoreInspectResult.Unreadable(PersistenceIssue.PERSISTENCE_FAILED, detail)

    private fun Map<*, *>.string(key: String): String? = this[key] as? String

    private fun Map<*, *>.nullableString(key: String): String? = when (val value = this[key]) {
        null -> null
        is String -> value
        else -> throw IllegalArgumentException("invalid $key")
    }

    private companion object {
        val CID = Regex("sha256:[0-9a-f]{64}")
        val KEYS = setOf(
            "version",
            "instance_id",
            "client_cert_fingerprint",
            "caller_cid",
            "stage",
            "request_sequence",
            "selected_cid",
            "choice",
            "operation_id",
            "canonical_put_payload",
            "echo_mismatch",
            "refusal_reason",
        )
    }
}
