// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.model

enum class SourceKind { OBSERVER, IMPORTER, BOTH }
enum class SourceGroup { EXPERIENCING_ALONGSIDE, BRINGING_IN }
enum class SourceState { OFF, SETTING_UP, ON, PAUSED, NEEDS_ATTENTION }
enum class ReasonCode { NONE, PERMISSION_REVOKED, SERVICE_KILLED, REBOOTED, UNPAIRED, STORAGE_FULL, PROVIDER_SILENT, AUTH_REVOKED, EXEMPTION_UNVERIFIED, TRANSPORT_UNAVAILABLE, FOREGROUND_START_NOT_ALLOWED }
enum class QueueState { RECORDING, SEALED, UPLOADING, UPLOADED, FAILED, EVICTED }
data class Source(val id: String, val kind: SourceKind, val group: SourceGroup, val label: String)
data class SegmentKey(val day: String /* YYYYMMDD */, val segment: String /* HHMMSS_LEN */)
data class GapEvent(val kind: String, val atEpochMs: Long, val detail: String?)
data class WireKeys(val day: String, val segment: String, val startEpochMs: Long, val endEpochMs: Long, val zoneId: String, val utcOffsetSeconds: Int)
data class BundleFile(val sourceId: String, val name: String, val sha256: String, val byteSize: Long, val mediaType: String, val captureStartEpochMs: Long, val captureEndEpochMs: Long)
data class BundleManifest(val key: SegmentKey, val files: List<BundleFile>, val gaps: List<GapEvent>)
enum class IdentityState { UNPAIRED, PAIRED, REVOKED }
data class PairedHome(val instanceId: String, val homeLabel: String, val relayOrigin: String?, val caChainFingerprint: String, val clientCertFingerprint: String, val observerHandle: String?, val deviceToken: String?, val expiresAt: String?, val state: IdentityState)
