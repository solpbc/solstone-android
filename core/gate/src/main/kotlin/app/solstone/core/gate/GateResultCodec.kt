// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.gate

import app.solstone.core.pl.parseJson
import app.solstone.core.pl.toJson

object GateResultCodec {
    private val topKeys = setOf(
        "schema_version", "driver_contract_version", "run_nonce", "action", "action_sequence",
        "result", "started_at", "finished_at", "owner_status_checkpoints",
        "production_relay_dial_attempts", "caller_retry_attempts", "facts", "diagnostics",
    )

    fun encode(result: GateResult): ByteArray {
        require(GateResultVerifier.violations(result).isEmpty()) {
            GateResultVerifier.violations(result).joinToString()
        }
        val bytes = (toJson(result.toMap()) + "\n").toByteArray()
        require(bytes.size <= MAX_RESULT_BYTES)
        return bytes
    }

    fun decode(bytes: ByteArray): GateResult {
        require(bytes.size <= MAX_RESULT_BYTES)
        val root = parseJson(bytes.toString(Charsets.UTF_8)).asObject()
        root.requireExact(topKeys)
        return GateResult(
            schemaVersion = root.int("schema_version"),
            driverContractVersion = root.int("driver_contract_version"),
            runNonce = root.string("run_nonce"),
            action = requireNotNull(GateAction.fromWireName(root.string("action"))),
            actionSequence = root.int("action_sequence"),
            result = enumValueOf(root.string("result")),
            startedAt = root.string("started_at"),
            finishedAt = root.string("finished_at"),
            ownerStatusCheckpoints = root.list("owner_status_checkpoints").map(::decodeCheckpoint),
            productionRelayDialAttempts = root.int("production_relay_dial_attempts"),
            callerRetryAttempts = root.int("caller_retry_attempts"),
            facts = normalizeJson(root.value("facts")).asObject(),
            diagnostics = root.list("diagnostics").map {
                val item = it.asObject()
                item.requireExact(setOf("error_type", "stage"))
                GateDiagnostic(item.string("error_type"), item.string("stage"))
            },
        ).also { require(GateResultVerifier.violations(it).isEmpty()) }
    }

    private fun GateResult.toMap(): Map<String, Any?> = linkedMapOf(
        "schema_version" to schemaVersion,
        "driver_contract_version" to driverContractVersion,
        "run_nonce" to runNonce,
        "action" to action.wireName,
        "action_sequence" to actionSequence,
        "result" to result.name,
        "started_at" to startedAt,
        "finished_at" to finishedAt,
        "owner_status_checkpoints" to ownerStatusCheckpoints.map { it.toMap() },
        "production_relay_dial_attempts" to productionRelayDialAttempts,
        "caller_retry_attempts" to callerRetryAttempts,
        "facts" to facts,
        "diagnostics" to diagnostics.map {
            linkedMapOf("error_type" to it.errorType, "stage" to it.stage)
        },
    )

    private fun GateCheckpoint.toMap() = linkedMapOf(
        "checkpoint" to checkpoint,
        "probe_id" to probeId,
        "variant" to variant.wireName,
        "reason" to reason,
        "status" to status,
        "http_result" to httpResult?.let {
            linkedMapOf(
                "probe_id" to it.probeId, "attempted" to it.attempted, "completed" to it.completed,
                "status" to it.status, "production_path" to it.productionPath,
            )
        },
    )

    private fun decodeCheckpoint(value: Any?): GateCheckpoint {
        val map = value.asObject()
        map.requireExact(setOf("checkpoint", "probe_id", "variant", "reason", "status", "http_result"))
        val probeId = map.string("probe_id")
        return GateCheckpoint(
            map.string("checkpoint"),
            probeId,
            PlCheckpointKind.entries.single { it.wireName == map.string("variant") },
            map.nullableString("reason"),
            map.nullableInt("status"),
            map.value("http_result")?.let {
                val http = it.asObject()
                http.requireExact(setOf("probe_id", "attempted", "completed", "status", "production_path"))
                GateHttpResult(
                    http.string("probe_id"), http.boolean("attempted"), http.boolean("completed"),
                    http.nullableInt("status"), http.string("production_path"),
                )
            },
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun Any?.asObject(): Map<String, Any?> =
        this as? Map<String, Any?> ?: error("expected object")
    private fun Map<String, Any?>.requireExact(keys: Set<String>) = require(this.keys == keys)
    private fun Map<String, Any?>.value(key: String): Any? {
        require(containsKey(key))
        return this[key]
    }
    private fun Map<String, Any?>.string(key: String) = value(key) as? String ?: error("$key string")
    private fun Map<String, Any?>.nullableString(key: String) = value(key)?.let { it as? String ?: error("$key string") }
    private fun Map<String, Any?>.boolean(key: String) = value(key) as? Boolean ?: error("$key boolean")
    private fun Map<String, Any?>.int(key: String): Int {
        val number = value(key) as? Number ?: error("$key number")
        return number.toInt().also { require(number.toDouble() == it.toDouble()) }
    }
    private fun Map<String, Any?>.nullableInt(key: String) = value(key)?.let {
        val number = it as? Number ?: error("$key number")
        number.toInt().also { value -> require(number.toDouble() == value.toDouble()) }
    }
    private fun Map<String, Any?>.list(key: String) = value(key) as? List<*> ?: error("$key list")

    private fun normalizeJson(value: Any?): Any? = when (value) {
        is Map<*, *> -> value.entries.associate { (key, item) ->
            (key as? String ?: error("object key string")) to normalizeJson(item)
        }
        is List<*> -> value.map(::normalizeJson)
        is Number -> {
            val long = value.toLong()
            require(value.toDouble() == long.toDouble()) { "integer required" }
            if (long in Int.MIN_VALUE..Int.MAX_VALUE) long.toInt() else long
        }
        else -> value
    }
}

data class GateAcceptanceExpectation(
    val runNonce: String,
    val action: GateAction,
    val exactNextSequence: Int,
)

fun acceptsGateResult(bytes: ByteArray, expected: GateAcceptanceExpectation): Boolean = runCatching {
    val result = GateResultCodec.decode(bytes)
    result.schemaVersion == 1 &&
        result.driverContractVersion == 1 &&
        result.runNonce == expected.runNonce &&
        result.action == expected.action &&
        result.actionSequence == expected.exactNextSequence
}.getOrDefault(false)
