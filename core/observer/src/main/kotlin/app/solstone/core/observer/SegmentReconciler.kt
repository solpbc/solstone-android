// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.observer

import app.solstone.core.model.BundleFile
import app.solstone.core.model.BundleManifest
import app.solstone.core.model.SegmentKey
import app.solstone.core.pl.PlHttpClient
import app.solstone.core.pl.parseJson

private val HELD_STATUSES = setOf("present", "relocated")

class SegmentReconciler(private val http: PlHttpClient, private val observerHandle: String) {
    fun fetch(day: String): List<ServerSegment> {
        val response = http.request(
            method = "GET",
            path = "$SEGMENTS_PATH/$day",
            headers = mapOf(
                OBSERVER_HANDLE_HEADER to observerHandle,
                PROTOCOL_VERSION_HEADER to OBSERVER_PROTOCOL_VERSION.toString(),
            ),
            body = null,
        )
        val root = parseJson(response.bodyText()) as? Map<*, *> ?: throw IllegalArgumentException("segments response must be an object")
        val items = root["items"] as? List<*> ?: throw IllegalArgumentException("segments response missing items")
        return items.map { item ->
            val segment = item as? Map<*, *> ?: throw IllegalArgumentException("segment item must be an object")
            val files = segmentFiles(segment)
            ServerSegment(key = requiredString(segment, "key"), files = files)
        }
    }

    fun diff(localManifests: List<BundleManifest>, day: String): List<ReconcileVerdict> {
        val remoteByKey = fetch(day).associateBy { it.key }
        return localManifests.map { manifest ->
            val remoteFiles = remoteByKey[manifest.key.segment]?.files.orEmpty()
            ReconcileVerdict(
                key = manifest.key,
                needsUpload = !manifest.files.all { local -> isProvenHeld(local, remoteFiles) },
            )
        }
    }

    private fun isProvenHeld(local: BundleFile, remoteFiles: List<ServerFile>): Boolean =
        remoteFiles.any { remote ->
            (remote.submittedName ?: remote.name) == local.name &&
                remote.sha256 == local.sha256 &&
                remote.status in HELD_STATUSES
        }

    private fun segmentFiles(segment: Map<*, *>): List<ServerFile> {
        val files = segment["files"] as? List<*> ?: throw IllegalArgumentException("segment item missing files")
        return files.map { item ->
            val file = item as? Map<*, *> ?: throw IllegalArgumentException("segment file must be an object")
            ServerFile(
                name = requiredString(file, "name"),
                sha256 = requiredString(file, "sha256"),
                status = file["status"] as? String,
                submittedName = file["submitted_name"] as? String,
            )
        }
    }

    private fun requiredString(root: Map<*, *>, key: String): String =
        root[key] as? String ?: throw IllegalArgumentException("segments response missing $key")
}

data class ServerFile(
    val name: String,
    val sha256: String,
    val status: String?,
    val submittedName: String?,
)

data class ServerSegment(val key: String, val files: List<ServerFile>)

data class ReconcileVerdict(val key: SegmentKey, val needsUpload: Boolean)
