// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.observer

import app.solstone.core.model.BundleFile
import app.solstone.core.model.BundleManifest
import app.solstone.core.model.SegmentKey
import app.solstone.core.pl.PlHttpClient
import app.solstone.core.pl.HttpResponse
import app.solstone.core.pl.parseJson

// Per-file statuses that prove reconcile convergence after name and SHA match.
// "processed" means the journal intentionally consumed the raw byte after verified
// processing and deliberately does not keep that raw file on journal disk; it is still
// terminal proof the byte arrived, which is what makes re-uploading it pointless.
private val HELD_STATUSES = setOf("present", "processed")

sealed class ReconcileException(message: String, cause: Throwable? = null) : Exception(message, cause)

class ReconcileAuthException(val status: Int) : ReconcileException("reconcile auth failed: $status")

class ReconcileUnavailableException(val status: Int?, cause: Throwable? = null) :
    ReconcileException("reconcile unavailable: $status", cause)

class SegmentReconciler(private val http: PlHttpClient) {
    fun fetch(day: String): List<ServerSegment> {
        val response = http.request(
            method = "GET",
            path = "$SEGMENTS_PATH/$day",
            headers = mapOf(
                PROTOCOL_VERSION_HEADER to INGEST_PROTOCOL_VERSION.toString(),
            ),
            body = null,
        )
        return parseFetchResponse(response)
    }

    fun parseFetchResponse(response: HttpResponse): List<ServerSegment> {
        when (response.status) {
            200 -> return try {
                val root = parseJson(response.bodyText()) as? Map<*, *> ?: throw IllegalArgumentException("segments response must be an object")
                val items = root["items"] as? List<*> ?: throw IllegalArgumentException("segments response missing items")
                val protocolVersion = requiredNonNegativeInt(root, "protocol_version")
                require(protocolVersion == INGEST_PROTOCOL_VERSION) { "unsupported segments protocol version" }
                val total = requiredNonNegativeInt(root, "total")
                require(total == items.size) { "segments response total does not match items" }
                val segments = items.map(::segment)
                require(segments.map(ServerSegment::key).toSet().size == segments.size) {
                    "segments response has duplicate keys"
                }
                val originalKeys = segments.mapNotNull(ServerSegment::originalKey)
                require(originalKeys.toSet().size == originalKeys.size) {
                    "segments response has duplicate original keys"
                }
                segments
            } catch (e: Exception) {
                throw ReconcileUnavailableException(200, e)
            }
            401, 403 -> throw ReconcileAuthException(response.status)
            else -> throw ReconcileUnavailableException(response.status)
        }
    }

    fun diff(localManifests: List<BundleManifest>, day: String): List<ReconcileVerdict> {
        val remote = fetch(day)
        val remoteByKey = remote.associateBy { it.key }
        val remoteByOriginalKey = remote
            .filter { it.originalKey != null }
            .associateBy { requireNotNull(it.originalKey) }
        return localManifests.map { manifest ->
            val remoteFiles = (remoteByKey[manifest.key.segment] ?: remoteByOriginalKey[manifest.key.segment])
                ?.files
                .orEmpty()
            ReconcileVerdict(
                key = manifest.key,
                needsUpload = !manifest.files.all { local -> isProvenHeld(local, remoteFiles) },
            )
        }
    }

    private fun isProvenHeld(local: BundleFile, remoteFiles: List<ServerFile>): Boolean =
        remoteFiles.any { remote ->
            (remote.submittedName ?: remote.name) == local.name &&
                remote.size == local.byteSize &&
                remote.sha256.isNotEmpty() &&
                remote.sha256.equals(local.sha256, ignoreCase = true) &&
                remote.status in HELD_STATUSES
        }

    private fun segment(value: Any?): ServerSegment {
        val segment = value as? Map<*, *> ?: throw IllegalArgumentException("segment item must be an object")
        return ServerSegment(
            key = requiredNonBlankString(segment, "key"),
            files = segmentFiles(segment),
            observed = requiredBoolean(segment, "observed"),
            originalKey = optionalNonBlankString(segment, "original_key"),
        )
    }

    private fun segmentFiles(segment: Map<*, *>): List<ServerFile> {
        val files = segment["files"] as? List<*> ?: throw IllegalArgumentException("segment item missing files")
        return files.map { item ->
            val file = item as? Map<*, *> ?: throw IllegalArgumentException("segment file must be an object")
            ServerFile(
                name = requiredNonBlankString(file, "name"),
                size = requiredNonNegativeLong(file, "size"),
                sha256 = requiredSha256(file),
                status = requiredStatus(file),
                submittedName = optionalNonBlankString(file, "submitted_name"),
            )
        }
    }

    private fun requiredNonBlankString(root: Map<*, *>, key: String): String =
        (root[key] as? String)?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("segments response missing $key")

    private fun optionalNonBlankString(root: Map<*, *>, key: String): String? {
        if (!root.containsKey(key)) return null
        return (root[key] as? String)?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("segments response invalid $key")
    }

    private fun requiredBoolean(root: Map<*, *>, key: String): Boolean =
        root[key] as? Boolean ?: throw IllegalArgumentException("segments response missing $key")

    private fun requiredNonNegativeLong(root: Map<*, *>, key: String): Long {
        val value = root[key] as? Number ?: throw IllegalArgumentException("segments response missing $key")
        val number = value.toDouble()
        require(number.isFinite() && number >= 0 && number <= MAX_EXACT_JSON_INTEGER.toDouble() && number % 1.0 == 0.0) {
            "segments response invalid $key"
        }
        return number.toLong()
    }

    private fun requiredNonNegativeInt(root: Map<*, *>, key: String): Int {
        val value = root[key] as? Number ?: throw IllegalArgumentException("segments response missing $key")
        val number = value.toDouble()
        require(number.isFinite() && number >= 0 && number <= Int.MAX_VALUE.toDouble() && number % 1.0 == 0.0) {
            "segments response invalid $key"
        }
        return number.toInt()
    }

    private fun requiredSha256(root: Map<*, *>): String =
        requiredNonBlankString(root, "sha256").also {
            require(SHA256.matches(it)) { "segments response invalid sha256" }
        }

    private fun requiredStatus(root: Map<*, *>): String =
        requiredNonBlankString(root, "status").also {
            require(it in RESPONSE_STATUSES) { "segments response invalid status" }
        }

    private companion object {
        const val MAX_EXACT_JSON_INTEGER = 9_007_199_254_740_991L
        val SHA256 = Regex("[0-9a-fA-F]{64}")
        val RESPONSE_STATUSES = setOf("present", "processed", "missing")
    }
}

data class ServerFile(
    val name: String,
    val size: Long,
    val sha256: String,
    val status: String,
    val submittedName: String?,
)

data class ServerSegment(
    val key: String,
    val files: List<ServerFile>,
    val observed: Boolean,
    val originalKey: String?,
)

data class ReconcileVerdict(val key: SegmentKey, val needsUpload: Boolean)
