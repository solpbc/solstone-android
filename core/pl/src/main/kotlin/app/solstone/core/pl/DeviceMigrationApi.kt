// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.pl

private const val CLIENTS_PATH = "/app/network/api/clients"
private const val MIGRATION_PATH = "/app/network/api/clients/self/migration"
private val CID_PATTERN = Regex("sha256:[0-9a-f]{64}")
private val UUID_PATTERN = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")

data class MigrationClient(val cid: String, val displayLabel: String, val reported: ClientReportedDescription?)

data class MigrationStateResponse(
    val operationId: String?,
    val previousCid: String?,
    val state: String,
    val replacedCid: String?,
)

data class MigrationDecisionResponse(
    val operationId: String,
    val state: String,
    val previousCid: String?,
    val cid: String,
    val replacedCid: String?,
    val displayLabel: String,
)

sealed interface MigrationApiResult<out T> {
    data class Success<T>(val value: T) : MigrationApiResult<T>
    data class Refused(val status: Int, val reasonCode: String) : MigrationApiResult<Nothing>
    data class TargetUnavailable(val status: Int, val reasonCode: String) : MigrationApiResult<Nothing>
    data class Unknown(val status: Int?, val reasonCode: String? = null) : MigrationApiResult<Nothing>
}

/** The clients list and decision routes already authenticate with the current PL client. */
fun getMigrationClients(client: PlHttpClient): MigrationApiResult<List<MigrationClient>> {
    val response = try {
        client.request(
            method = "GET",
            path = CLIENTS_PATH,
            headers = mapOf("Accept" to "application/json", "Cache-Control" to "no-cache"),
            body = null,
            maxResponseBytes = 512 * 1024,
        )
    } catch (_: Exception) {
        return MigrationApiResult.Unknown(null)
    }
    if (response.status != 200) return MigrationApiResult.Unknown(response.status, response.errorReasonCode())
    return try {
        val root = parseJson(response.bodyText()) as? Map<*, *>
            ?: throw IllegalArgumentException("clients response must be an object")
        val clients = root["clients"] as? List<*> ?: throw IllegalArgumentException("clients must be a list")
        val parsed = clients.map { raw ->
            val row = raw as? Map<*, *> ?: throw IllegalArgumentException("client row must be an object")
            val cid = row["cid"] as? String ?: throw IllegalArgumentException("client CID missing")
            require(CID_PATTERN.matches(cid)) { "invalid client CID" }
            val label = row["display_label"] as? String ?: throw IllegalArgumentException("display label missing")
            require(label.isNotBlank()) { "display label is blank" }
            val reported = parseOptionalReported(row["reported"])
            MigrationClient(cid, label, reported)
        }
        require(parsed.map(MigrationClient::cid).toSet().size == parsed.size) { "duplicate client CID" }
        MigrationApiResult.Success(parsed)
    } catch (_: Exception) {
        MigrationApiResult.Unknown(response.status)
    }
}

fun getMigrationState(client: PlHttpClient): MigrationApiResult<MigrationStateResponse> {
    return try {
        val response = client.request(
            method = "GET",
            path = MIGRATION_PATH,
            headers = mapOf("Accept" to "application/json", "Cache-Control" to "no-cache"),
            body = null,
            maxResponseBytes = 64 * 1024,
        )
        if (response.status != 200) return MigrationApiResult.Unknown(response.status, response.errorReasonCode())
        val root = strictJsonObject(
            response.bodyText(),
            setOf("protocol_version", "rekey_operation_id", "previous_cid", "state", "replaced_cid"),
        )
        require(root.requiredLong("protocol_version") == 1L)
        val operation = root.nullableString("rekey_operation_id")?.also { require(UUID_PATTERN.matches(it)) }
        val previous = root.nullableCid("previous_cid")
        val replaced = root.nullableCid("replaced_cid")
        val state = root.requiredString("state").also { require(it in MIGRATION_STATES) }
        MigrationApiResult.Success(MigrationStateResponse(operation, previous, state, replaced))
    } catch (_: Exception) {
        MigrationApiResult.Unknown(200)
    }
}

fun putMigrationDecision(
    client: PlHttpClient,
    canonicalPayload: ByteArray,
): MigrationApiResult<MigrationDecisionResponse> {
    return try {
        val response = client.request(
            method = "PUT",
            path = MIGRATION_PATH,
            headers = mapOf("Content-Type" to "application/json", "Accept" to "application/json"),
            body = canonicalPayload,
            maxResponseBytes = 64 * 1024,
        )
        if (response.status !in 200..299) {
            val reason = response.errorReasonCode()
            if (response.status == 400 && reason != null && reason in DEFINITE_PRECOMMIT_REFUSALS) {
                return MigrationApiResult.Refused(response.status, reason)
            }
            if (response.status == 404 && reason == TARGET_UNAVAILABLE_REASON) {
                return MigrationApiResult.TargetUnavailable(response.status, reason)
            }
            return MigrationApiResult.Unknown(response.status, reason)
        }
        val root = strictJsonObject(
            response.bodyText(),
            setOf("protocol_version", "operation_id", "state", "previous_cid", "cid", "replaced_cid", "display_label"),
        )
        require(root.requiredLong("protocol_version") == 1L)
        val operation = root.requiredString("operation_id").also { require(UUID_PATTERN.matches(it)) }
        val previous = root.nullableCid("previous_cid")
        val state = root.requiredString("state").also { require(it in MIGRATION_STATES) }
        val cid = root.requiredString("cid").also { require(CID_PATTERN.matches(it)) }
        val replaced = root.nullableCid("replaced_cid")
        val label = root.requiredString("display_label")
        MigrationApiResult.Success(MigrationDecisionResponse(operation, state, previous, cid, replaced, label))
    } catch (_: Exception) {
        MigrationApiResult.Unknown(null)
    }
}

fun canonicalMigrationDecisionPayload(
    operationId: String,
    choice: PairingMigrationChoice,
    target: String?,
): ByteArray {
    require(UUID_PATTERN.matches(operationId))
    val body = linkedMapOf<String, Any?>(
        "protocol_version" to 1,
        "operation_id" to operationId,
        "choice" to choice.wire,
    )
    if (choice == PairingMigrationChoice.REPLACE) {
        require(target != null && CID_PATTERN.matches(target))
        body["replaces_cid"] = target
    } else {
        require(target == null)
    }
    return toJson(body).toByteArray(Charsets.UTF_8)
}

private fun HttpResponse.errorReasonCode(): String? = runCatching {
    (parseJson(bodyText()) as? Map<*, *>)?.get("reason_code") as? String
}.getOrNull()

private fun parseOptionalReported(value: Any?): ClientReportedDescription? = when (value) {
    null -> null
    is Map<*, *> -> ClientReportedDescription(
        name = value.optionalReportedString("name"),
        platform = value.optionalReportedString("platform"),
        deviceType = value.optionalReportedString("device_type"),
        appId = value.optionalReportedString("app_id"),
        appVersion = value.optionalReportedString("app_version"),
    )
    else -> throw IllegalArgumentException("invalid reported client data")
}

private fun Map<*, *>.optionalReportedString(key: String): String? = when (val field = this[key]) {
    null -> null
    is String -> field
    else -> throw IllegalArgumentException("invalid reported $key")
}

private fun strictJsonObject(text: String, required: Set<String>): Map<String, Any?> {
    val root = parseJson(text) as? Map<*, *> ?: throw IllegalArgumentException("expected JSON object")
    require(root.keys.all { it is String }) { "non-string JSON key" }
    val stringMap = root.entries.associate { (key, value) -> key as String to value }
    require(stringMap.keys == required) { "unexpected response fields" }
    return stringMap
}

private fun Map<String, Any?>.requiredString(key: String): String =
    this[key] as? String ?: throw IllegalArgumentException("missing $key")

private fun Map<String, Any?>.nullableString(key: String): String? = when (val value = this[key]) {
    null -> null
    is String -> value
    else -> throw IllegalArgumentException("invalid $key")
}

private fun Map<String, Any?>.nullableCid(key: String): String? = nullableString(key)?.also {
    require(CID_PATTERN.matches(it)) { "invalid CID" }
}

private fun Map<String, Any?>.requiredLong(key: String): Long = exactJsonInteger(this[key])
    ?: throw IllegalArgumentException("missing integer $key")

private val MIGRATION_STATES = setOf("none", "pending", "new_device", "same_device", "replaced_device")
private val DEFINITE_PRECOMMIT_REFUSALS = setOf("migration_request_invalid", "migration_protocol_unsupported")
const val TARGET_UNAVAILABLE_REASON = "paired_device_not_found"
fun isDefiniteMigrationPrecommitRefusal(reason: String?): Boolean = reason != null && reason in DEFINITE_PRECOMMIT_REFUSALS
