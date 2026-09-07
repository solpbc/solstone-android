// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.pl

data class ClientReportedDescription(
    val name: String? = null,
    val platform: String? = null,
    val deviceType: String? = null,
    val appId: String? = null,
    val appVersion: String? = null,
)

data class ClientsSelfResponse(
    val protocolVersion: Int,
    val revision: Long,
    val reported: ClientReportedDescription?,
    val ownerLabel: String?,
    val displayLabel: String?,
    val updatedAt: String?,
    val journalName: String?,
    val journalVersion: String?,
)

data class ClientsSelfPutRequest(
    val protocolVersion: Int = 1,
    val expectedRevision: Long?,
    val reported: ClientReportedDescription,
)

sealed interface ClientsSelfGetResult {
    data class Success(val response: ClientsSelfResponse) : ClientsSelfGetResult
    data object NotFound : ClientsSelfGetResult
    data class Failure(val status: Int?, val message: String?) : ClientsSelfGetResult
}

sealed interface ClientsSelfPutResult {
    data class Success(val response: ClientsSelfResponse) : ClientsSelfPutResult
    data object Conflict : ClientsSelfPutResult
    data class Failure(val status: Int?, val message: String?) : ClientsSelfPutResult
}

fun sanitizeField(value: String?, maxUtf8Bytes: Int): String? {
    if (value == null) return null
    val trimmed = value.trim()
    if (trimmed.isEmpty()) return null
    if (trimmed.any { it in '\u0000'..'\u001F' || it == '\u007F' }) return null
    val bytes = trimmed.toByteArray(Charsets.UTF_8)
    if (bytes.size > maxUtf8Bytes) return null
    return trimmed
}

fun sanitizeReportedDescription(raw: ClientReportedDescription): ClientReportedDescription =
    ClientReportedDescription(
        name = sanitizeField(raw.name, 80),
        platform = sanitizeField(raw.platform, 64),
        deviceType = sanitizeField(raw.deviceType, 64),
        appId = sanitizeField(raw.appId, 64),
        appVersion = sanitizeField(raw.appVersion, 64),
    )

fun parseClientsSelfResponse(bodyText: String): ClientsSelfResponse? = runCatching {
    val root = parseJson(bodyText) as? Map<*, *> ?: return null
    val pvNum = root["protocol_version"] as? Number ?: return null
    if (pvNum.toDouble() != 1.0 || pvNum.toLong() != 1L) return null
    val protocolVersion = pvNum.toInt()

    val revNum = root["revision"] as? Number ?: return null
    val revDouble = revNum.toDouble()
    val revLong = revNum.toLong()
    if (revDouble != revLong.toDouble() || revLong < 0) return null
    val revision = revLong

    val reportedRaw = root["reported"]
    val reported = if (reportedRaw is Map<*, *>) {
        val nameRaw = reportedRaw["name"]
        if (nameRaw != null && nameRaw !is String) return null
        val platRaw = reportedRaw["platform"]
        if (platRaw != null && platRaw !is String) return null
        val dtRaw = reportedRaw["device_type"]
        if (dtRaw != null && dtRaw !is String) return null
        val appRaw = reportedRaw["app_id"]
        if (appRaw != null && appRaw !is String) return null
        val verRaw = reportedRaw["app_version"]
        if (verRaw != null && verRaw !is String) return null
        ClientReportedDescription(
            name = nameRaw as? String,
            platform = platRaw as? String,
            deviceType = dtRaw as? String,
            appId = appRaw as? String,
            appVersion = verRaw as? String,
        )
    } else if (reportedRaw == null) {
        null
    } else {
        return null
    }

    val ownerLabel = root["owner_label"]?.let { (it as? String) ?: return null }
    val displayLabel = root["display_label"]?.let { (it as? String) ?: return null }
    val updatedAt = root["updated_at"]?.let { (it as? String) ?: return null }

    val journalRaw = root["journal"]
    val (journalName, journalVersion) = if (journalRaw is Map<*, *>) {
        val jnRaw = journalRaw["name"]
        if (jnRaw != null && jnRaw !is String) return null
        val jvRaw = journalRaw["version"]
        if (jvRaw != null && jvRaw !is String) return null
        val jn = (jnRaw as? String)?.let { sanitizeField(it, 128) }
        val jv = (jvRaw as? String)?.let { sanitizeJournalVersion(it) ?: return null }
        jn to jv
    } else if (journalRaw == null) {
        null to null
    } else {
        return null
    }

    ClientsSelfResponse(
        protocolVersion = protocolVersion,
        revision = revision,
        reported = reported?.let(::sanitizeReportedDescription),
        ownerLabel = ownerLabel,
        displayLabel = displayLabel,
        updatedAt = updatedAt,
        journalName = journalName,
        journalVersion = journalVersion,
    )
}.getOrNull()

fun encodeClientsSelfPutBody(request: ClientsSelfPutRequest): ByteArray {
    val sanitized = sanitizeReportedDescription(request.reported)
    fun jsonStringOrNull(s: String?): String = if (s == null) "null" else "\"${escapeJson(s)}\""
    val reportedJson = """{"name":${jsonStringOrNull(sanitized.name)},"platform":${jsonStringOrNull(sanitized.platform)},"device_type":${jsonStringOrNull(sanitized.deviceType)},"app_id":${jsonStringOrNull(sanitized.appId)},"app_version":${jsonStringOrNull(sanitized.appVersion)}}"""
    val expectedRevStr = request.expectedRevision?.toString() ?: "null"
    val json = """{"protocol_version":${request.protocolVersion},"expected_revision":$expectedRevStr,"reported":$reportedJson}"""
    return json.toByteArray(Charsets.UTF_8)
}

fun escapeJson(s: String): String = buildString {
    for (ch in s) {
        when (ch) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\b' -> append("\\b")
            '\u000C' -> append("\\f")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(ch)
        }
    }
}

fun fetchClientsSelf(
    client: PlHttpClient,
    maxResponseBytes: Int = 64 * 1024,
): ClientsSelfGetResult = try {
    val response = client.request(
        method = "GET",
        path = "/app/network/api/clients/self",
        headers = mapOf("Accept" to "application/json", "Cache-Control" to "no-cache"),
        body = null,
        maxResponseBytes = maxResponseBytes,
    )
    when (response.status) {
        200 -> {
            val parsed = parseClientsSelfResponse(response.bodyText())
            if (parsed != null) ClientsSelfGetResult.Success(parsed)
            else ClientsSelfGetResult.Failure(200, "corrupt JSON body")
        }
        404 -> ClientsSelfGetResult.NotFound
        else -> ClientsSelfGetResult.Failure(response.status, response.bodyText())
    }
} catch (e: Exception) {
    ClientsSelfGetResult.Failure(null, e.message)
}

fun putClientsSelf(
    client: PlHttpClient,
    request: ClientsSelfPutRequest,
    maxResponseBytes: Int = 64 * 1024,
): ClientsSelfPutResult = try {
    val bodyBytes = encodeClientsSelfPutBody(request)
    val response = client.request(
        method = "PUT",
        path = "/app/network/api/clients/self",
        headers = mapOf("Content-Type" to "application/json", "Accept" to "application/json"),
        body = bodyBytes,
        maxResponseBytes = maxResponseBytes,
    )
    when (response.status) {
        in 200..299 -> {
            val parsed = parseClientsSelfResponse(response.bodyText())
            if (parsed != null) ClientsSelfPutResult.Success(parsed)
            else ClientsSelfPutResult.Failure(response.status, "corrupt JSON body")
        }
        409 -> ClientsSelfPutResult.Conflict
        else -> ClientsSelfPutResult.Failure(response.status, response.bodyText())
    }
} catch (e: Exception) {
    ClientsSelfPutResult.Failure(null, e.message)
}
