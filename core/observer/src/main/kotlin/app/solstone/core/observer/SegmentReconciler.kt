// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.observer

import app.solstone.core.model.BundleManifest
import app.solstone.core.model.SegmentKey
import app.solstone.core.pl.PlHttpClient
import app.solstone.core.pl.parseJson

class SegmentReconciler(private val http: PlHttpClient) {
    fun fetch(day: String): List<ServerSegment> {
        val response = http.request(
            method = "GET",
            path = "$SEGMENTS_PATH/$day",
            headers = mapOf(PROTOCOL_VERSION_HEADER to OBSERVER_PROTOCOL_VERSION.toString()),
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
            val localFiles = manifest.files.associate { file -> file.name to file.sha256 }
            ReconcileVerdict(
                key = manifest.key,
                needsUpload = remoteByKey[manifest.key.segment]?.files != localFiles,
            )
        }
    }

    private fun segmentFiles(segment: Map<*, *>): Map<String, String> {
        val files = segment["files"] as? List<*> ?: throw IllegalArgumentException("segment item missing files")
        return files.associate { item ->
            val file = item as? Map<*, *> ?: throw IllegalArgumentException("segment file must be an object")
            requiredString(file, "name") to requiredString(file, "sha256")
        }
    }

    private fun requiredString(root: Map<*, *>, key: String): String =
        root[key] as? String ?: throw IllegalArgumentException("segments response missing $key")
}

data class ServerSegment(val key: String, val files: Map<String, String>)

data class ReconcileVerdict(val key: SegmentKey, val needsUpload: Boolean)
