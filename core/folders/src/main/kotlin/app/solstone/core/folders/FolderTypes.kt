// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.folders

import app.solstone.core.pl.parseJson
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

const val FOLDER_PROTOCOL_VERSION = "3"
const val FOLDER_WINDOW_SECONDS = 300L
const val FOLDER_WINDOW_MS = FOLDER_WINDOW_SECONDS * 1_000L
const val DEFAULT_MAX_FILE_BYTES = 64L * 1024L * 1024L
const val DEFAULT_MAX_NOTE_BYTES = 4L * 1024L * 1024L
const val DEFAULT_MAX_IMAGE_PIXELS = 67_108_864L
const val DEFAULT_REVISION_COOLDOWN_SECONDS = 3_600L
const val MAX_UPLOAD_CONTENT_PARTS = 63
const val MAX_ACTIVITY_LINES = 5_000
const val MAX_ESTIMATE_ITEMS = 50_000
const val DAY_MS = 86_400_000L

/** The six owner-facing file kinds in the folder source sorting table. */
enum class FileKind(val wireName: String, val kindHint: String?) {
    NOTE("notes", "notes"),
    DOCUMENT("documents", "documents"),
    TRANSCRIPT("transcripts", "transcripts"),
    SCREENSHOT("screenshots", "screenshots"),
    PHOTO("photos", "photos"),
    OTHER("other", null);

    companion object {
        fun fromWire(value: String?): FileKind = entries.firstOrNull { it.wireName == value } ?: OTHER
    }
}

/** Per-folder enabled-kind switches. All six default on; at least one must remain on. */
data class FolderKinds(
    val notes: Boolean = true,
    val documents: Boolean = true,
    val transcripts: Boolean = true,
    val screenshots: Boolean = true,
    val photos: Boolean = true,
    val other: Boolean = true,
) {
    fun anySelected(): Boolean = notes || documents || transcripts || screenshots || photos || other

    fun enabledFor(kind: FileKind): Boolean = when (kind) {
        FileKind.NOTE -> notes
        FileKind.DOCUMENT -> documents
        FileKind.TRANSCRIPT -> transcripts
        FileKind.SCREENSHOT -> screenshots
        FileKind.PHOTO -> photos
        FileKind.OTHER -> other
    }

    fun enabledNames(): List<String> = buildList {
        if (notes) add("notes")
        if (documents) add("documents")
        if (transcripts) add("transcripts")
        if (screenshots) add("screenshots")
        if (photos) add("photos")
        if (other) add("other")
    }

    fun withKind(kind: FileKind, enabled: Boolean): FolderKinds = when (kind) {
        FileKind.NOTE -> copy(notes = enabled)
        FileKind.DOCUMENT -> copy(documents = enabled)
        FileKind.TRANSCRIPT -> copy(transcripts = enabled)
        FileKind.SCREENSHOT -> copy(screenshots = enabled)
        FileKind.PHOTO -> copy(photos = enabled)
        FileKind.OTHER -> copy(other = enabled)
    }
}

enum class DeliveryMode(val wire: String) {
    CONTENT("content"),
    METADATA_ONLY("metadata_only"),
    IGNORE("ignore");

    companion object {
        fun fromWire(value: String?): DeliveryMode = entries.firstOrNull { it.wire == value } ?: IGNORE
    }
}

enum class ActivityVerb(val wire: String, val wireEvent: String) {
    ADDED("added", "added"),
    EDITED("edited", "changed"),
    REVISED("revised", "changed"),
    RENAMED("renamed", "renamed"),
    REMOVED("removed", "removed");

    companion object {
        fun fromWire(value: String?): ActivityVerb = entries.firstOrNull { it.wire == value } ?: ADDED
    }
}

enum class FolderMode(val wire: String) {
    SETTING_UP("setting_up"),
    ON("on"),
    PAUSED("paused"),
    OFF("off");

    companion object {
        fun fromWire(value: String?): FolderMode = entries.firstOrNull { it.wire == value } ?: SETTING_UP
    }
}

enum class FolderAttention(val wire: String) {
    PERMISSION_LOST("permission_lost"),
    MISSING("missing"),
    UNREADABLE("unreadable");

    companion object {
        fun fromWire(value: String?): FolderAttention? = entries.firstOrNull { it.wire == value }
    }
}

enum class FolderSpan(val wire: String) {
    LAST_30_DAYS("last_30_days"),
    LAST_6_MONTHS("last_6_months"),
    LAST_YEAR("last_year"),
    ALL("all"),
    TODAY("today");

    companion object {
        fun fromWire(value: String?): FolderSpan = entries.firstOrNull { it.wire == value } ?: LAST_30_DAYS
    }
}

data class FacetOption(
    val name: String,
    val title: String,
    val color: String? = null,
)

enum class FacetsAvailability {
    LOADING,
    LOADED,
    EMPTY,
    UNAVAILABLE,
}

sealed class OverlapTarget {
    abstract val name: String

    data class Obsidian(override val name: String) : OverlapTarget()
    data class Screenshots(override val name: String) : OverlapTarget()
    data class Folder(val id: String, override val name: String) : OverlapTarget()
}

data class FolderWindow(
    val day: String,
    val segment: String,
) : Comparable<FolderWindow> {
    override fun compareTo(other: FolderWindow): Int {
        val d = day.compareTo(other.day)
        return if (d != 0) d else segment.compareTo(other.segment)
    }
}

data class FolderStamp(
    val epochMs: Long,
    val offsetSeconds: Int,
) {
    fun window(): FolderWindow {
        val zone = ZoneOffset.ofTotalSeconds(offsetSeconds)
        val zdt = Instant.ofEpochMilli(epochMs).atOffset(zone)
        val day = DAY_FMT.format(zdt)
        val secOfDay = zdt.hour * 3600 + zdt.minute * 60 + zdt.second
        val bucketSec = (secOfDay / 300) * 300
        val h = bucketSec / 3600
        val m = (bucketSec % 3600) / 60
        val s = bucketSec % 60
        val seg = String.format(Locale.ROOT, "%02d%02d%02d_300", h, m, s)
        return FolderWindow(day, seg)
    }

    fun windowEndMs(): Long {
        val localMs = epochMs + offsetSeconds * 1_000L
        val bucketEndLocal = Math.floorDiv(localMs, FOLDER_WINDOW_MS) * FOLDER_WINDOW_MS + FOLDER_WINDOW_MS
        return bucketEndLocal - offsetSeconds * 1_000L
    }

    fun rfc3339(): String {
        val zone = ZoneOffset.ofTotalSeconds(offsetSeconds)
        val instant = Instant.ofEpochSecond(Math.floorDiv(epochMs, 1_000L))
        return RFC3339_FMT.format(instant.atOffset(zone))
    }

    companion object {
        private val DAY_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd", Locale.ROOT)
        private val RFC3339_FMT: DateTimeFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME
    }
}

interface FolderClock {
    fun nowMs(): Long
    fun offsetSecondsAt(epochMs: Long): Int

    fun now(): FolderStamp {
        val ms = nowMs()
        return FolderStamp(ms, offsetSecondsAt(ms))
    }

    fun stampAt(epochMs: Long): FolderStamp = FolderStamp(epochMs, offsetSecondsAt(epochMs))
}

object SystemFolderClock : FolderClock {
    override fun nowMs(): Long = System.currentTimeMillis()
    override fun offsetSecondsAt(epochMs: Long): Int =
        ZoneId.systemDefault().rules.getOffset(Instant.ofEpochMilli(epochMs)).totalSeconds
}

fun spanStartMs(span: FolderSpan, nowMs: Long, offsetSeconds: Int): Long = when (span) {
    FolderSpan.LAST_30_DAYS -> nowMs - 30L * DAY_MS
    FolderSpan.LAST_6_MONTHS -> nowMs - 183L * DAY_MS
    FolderSpan.LAST_YEAR -> nowMs - 365L * DAY_MS
    FolderSpan.ALL -> Long.MIN_VALUE
    FolderSpan.TODAY -> {
        val zone = ZoneOffset.ofTotalSeconds(offsetSeconds)
        val localDate: LocalDate = Instant.ofEpochMilli(nowMs).atOffset(zone).toLocalDate()
        ZonedDateTime.of(localDate.atStartOfDay(), zone).toInstant().toEpochMilli()
    }
}

data class PlatformMetadata(
    val sizeBytes: Long,
    val modifiedMs: Long,
    val createdMs: Long? = null,
    val extension: String? = null,
    val mimeType: String? = null,
    val originUrl: String? = null,
    val referrerUrl: String? = null,
    val downloadedBy: String? = null,
    val sourceApp: String? = null,
)

/** Dynamic sorting & limit table from `GET /app/devices/ingest/folder/supported`. */
data class SupportedList(
    val version: Int = 1,
    val rulesetVersion: Int = 8,
    val windowSeconds: Long = FOLDER_WINDOW_SECONDS,
    val maxContentBytes: Long = DEFAULT_MAX_FILE_BYTES,
    val maxNoteBytes: Long = DEFAULT_MAX_NOTE_BYTES,
    val maxImagePixels: Long = DEFAULT_MAX_IMAGE_PIXELS,
    val documentRevisionCooldownMs: Long = DEFAULT_REVISION_COOLDOWN_SECONDS * 1_000L,
    val noteExtensions: Set<String> = setOf("md", "markdown", "txt", "log"),
    val documentExtensions: Set<String> = setOf("pdf"),
    val transcriptExtensions: Set<String> = setOf("vtt", "srt"),
    val acceptedImageExtensions: Set<String> = setOf("png", "jpg", "jpeg", "webp"),
    val convertibleImageExtensions: Set<String> = setOf("heic", "heif", "tif", "tiff", "gif", "bmp"),
    val noteStructuredWords: Int = 1_500,
    val noteHeadingLevels: Int = 2,
    val noteLongWords: Int = 5_000,
) {
    companion object {
        fun parse(jsonText: String): SupportedList? {
            val root = runCatching { parseJson(jsonText) as? Map<*, *> }.getOrNull() ?: return null
            val defaults = SupportedList()
            val kinds = root["kinds"] as? Map<*, *> ?: return defaults

            fun stringSet(obj: Any?, key: String, fallback: Set<String>): Set<String> {
                val list = (obj as? Map<*, *>)?.get(key) as? List<*> ?: return fallback
                return list.mapNotNull { (it as? String)?.lowercase(Locale.ROOT)?.takeIf(String::isNotBlank) }.toSet()
            }

            val notesObj = kinds["notes"] as? Map<*, *>
            val docsObj = kinds["documents"] as? Map<*, *>
            val transcriptsObj = kinds["transcripts"] as? Map<*, *>
            val screenshotsObj = kinds["screenshots"] as? Map<*, *>
            val photosObj = kinds["photos"] as? Map<*, *>
            val thresholds = docsObj?.get("thresholds") as? Map<*, *>

            val noteExts = stringSet(notesObj, "extensions", defaults.noteExtensions)
            // Documents/transcripts advertise text extensions too; keep non-note dedicated extensions distinct
            // while still checking text thresholds for note candidates.
            val docExts = stringSet(docsObj, "extensions", defaults.documentExtensions) - noteExts
            val transcriptExts = (stringSet(transcriptsObj, "extensions", defaults.transcriptExtensions) - noteExts)
                .filterNot { it in setOf("json", "csv", "tsv", "html") }
                .toSet()
                .ifEmpty { defaults.transcriptExtensions }
            val acceptedImages = stringSet(screenshotsObj, "extensions", defaults.acceptedImageExtensions) +
                stringSet(photosObj, "extensions", emptySet())
            val convertibleImages = stringSet(screenshotsObj, "convert_to_png_on_device", defaults.convertibleImageExtensions) +
                stringSet(photosObj, "convert_to_png_on_device", emptySet())

            return SupportedList(
                version = (root["version"] as? Number)?.toInt() ?: defaults.version,
                rulesetVersion = (root["ruleset_version"] as? Number)?.toInt() ?: defaults.rulesetVersion,
                windowSeconds = (root["window_seconds"] as? Number)?.toLong()?.takeIf { it > 0 } ?: defaults.windowSeconds,
                maxContentBytes = (root["max_file_bytes"] as? Number)?.toLong()?.takeIf { it > 0 } ?: defaults.maxContentBytes,
                maxNoteBytes = (root["max_note_bytes"] as? Number)?.toLong()?.takeIf { it > 0 } ?: defaults.maxNoteBytes,
                maxImagePixels = (root["max_image_pixels"] as? Number)?.toLong()?.takeIf { it > 0 } ?: defaults.maxImagePixels,
                documentRevisionCooldownMs = ((root["document_revision_cooldown_seconds"] as? Number)?.toLong()
                    ?.takeIf { it > 0 } ?: DEFAULT_REVISION_COOLDOWN_SECONDS) * 1_000L,
                noteExtensions = noteExts.ifEmpty { defaults.noteExtensions },
                documentExtensions = docExts.ifEmpty { defaults.documentExtensions },
                transcriptExtensions = transcriptExts,
                acceptedImageExtensions = acceptedImages.ifEmpty { defaults.acceptedImageExtensions },
                convertibleImageExtensions = convertibleImages,
                noteStructuredWords = (thresholds?.get("structured_prose_min_words") as? Number)?.toInt()
                    ?: defaults.noteStructuredWords,
                noteHeadingLevels = (thresholds?.get("structured_prose_min_heading_levels") as? Number)?.toInt()
                    ?: defaults.noteHeadingLevels,
                noteLongWords = (thresholds?.get("long_prose_min_words") as? Number)?.toInt()
                    ?: defaults.noteLongWords,
            )
        }
    }
}

data class BackfillEstimate(
    val segments: Long,
    val days: Long,
    val thoughts: Long,
    val alreadyInJournal: Long = 0L,
    val backfillPaused: Boolean = false,
    val fromJournal: Boolean = false,
)

data class PreviewSummary(
    val span: FolderSpan,
    val totalItems: Long = 0L,
    val notes: Long = 0L,
    val documents: Long = 0L,
    val transcripts: Long = 0L,
    val screenshots: Long = 0L,
    val photos: Long = 0L,
    val other: Long = 0L,
    val contentItems: Long = 0L,
    val metadataOnlyItems: Long = 0L,
    val contentBytes: Long = 0L,
    val unreadable: Long = 0L,
    val oldestMs: Long? = null,
    val newestMs: Long? = null,
    val estimate: BackfillEstimate? = null,
    val failed: Boolean = false,
)

data class EstimateItem(
    val path: String,
    val at: String,
    val fileType: String?,
    val kindHint: String?,
    val contentHash: String?,
    val hasContent: Boolean,
)

data class TrackedFile(
    val relPath: String,
    val documentId: String?,
    val sizeBytes: Long,
    val modifiedMs: Long,
    val contentHash: String?,
    val kind: FileKind,
    val delivery: DeliveryMode,
    val deliveredToJournal: Boolean,
    val excludedBeforeSpan: Boolean,
    val lastSentHash: String? = null,
    val lastNoteText: String? = null,
    val pendingRevisionSaveMs: Long? = null,
    val pendingRevisionHash: String? = null,
)

data class PendingEvent(
    val id: String,
    val relPath: String,
    val documentId: String?,
    val previousPath: String? = null,
    val verb: ActivityVerb,
    val kind: FileKind,
    val delivery: DeliveryMode,
    val eventMs: Long,
    val observedMs: Long,
    val offsetSeconds: Int,
    val window: FolderWindow,
    val windowEndMs: Long,
    val metadata: PlatformMetadata,
    val contentHash: String? = null,
    val notePayload: String? = null,
    val isRevision: Boolean = false,
    val backfill: Boolean = false,
    val retryAtMs: Long? = null,
    val tries: Int = 0,
)

/**
 * Durable per-folder state.
 * Holds ZERO journal identity (no instance ID, URL, or certificate fingerprint).
 */
data class FolderRecord(
    val id: String,
    val treeUri: String,
    val name: String,
    val label: String,
    val canonicalPath: String?,
    val mode: FolderMode = FolderMode.SETTING_UP,
    val returning: Boolean = false,
    val pauseDuringSetup: Boolean = false,
    val kinds: FolderKinds = FolderKinds(),
    val facet: FacetOption? = null,
    val overlap: OverlapTarget? = null,
    val attention: FolderAttention? = null,
    val spanFromMs: Long? = null,
    val selectedSpan: FolderSpan = FolderSpan.LAST_30_DAYS,
    val lastCheckedMs: Long? = null,
    val lastTakenInMs: Long? = null,
    val itemsTakenIn: Long = 0L,
    val backfillDone: Long = 0L,
    val backfillTotal: Long = 0L,
    val cantRead: Long = 0L,
    val fallbackToDetails: Long = 0L,
    val preview: PreviewSummary? = null,
    val files: MutableMap<String, TrackedFile> = LinkedHashMap(),
    val pending: MutableList<PendingEvent> = ArrayList(),
)

data class StagedContentFile(
    val eventId: String,
    val relPath: String,
    val documentId: String?,
    val kind: FileKind,
    val contentHash: String?,
    val notePayload: String?,
    val isRevision: Boolean,
    val needsImageConvert: Boolean,
)

data class SegmentDelivery(
    val folderId: String,
    val treeUri: String,
    val folderName: String,
    val facet: String?,
    val kinds: FolderKinds,
    val window: FolderWindow,
    val events: List<PendingEvent>,
    val contentFiles: List<StagedContentFile>,
)

sealed class DeliveryOutcome {
    data object Delivered : DeliveryOutcome()
    data class DeliveredWithRefusedContent(val refusedPaths: List<String>) : DeliveryOutcome()
    data class AllFilesRefused(val refusedPaths: List<String>) : DeliveryOutcome()
    data object InvalidPermanent : DeliveryOutcome()
    data object Hold : DeliveryOutcome()
}

data class FolderSummary(
    val id: String,
    val treeUri: String,
    val name: String,
    val label: String,
    val mode: FolderMode,
    val returning: Boolean,
    val kinds: FolderKinds,
    val facet: FacetOption?,
    val overlap: OverlapTarget?,
    val attention: FolderAttention?,
    val selectedSpan: FolderSpan,
    val preview: PreviewSummary?,
    val backfillDone: Long,
    val backfillTotal: Long,
    val waiting: Int,
    val itemsTakenIn: Long,
    val lastTakenInMs: Long?,
    val lastCheckedMs: Long?,
    val cantRead: Long,
    val fallbackToDetails: Long,
    val pausedByApp: Boolean,
)

data class WalkedFile(
    val relPath: String,
    val documentId: String?,
    val sizeBytes: Long,
    val modifiedMs: Long,
    val metadata: PlatformMetadata,
    val isScreenshot: Boolean,
)

sealed class WalkOutcome {
    data class Ok(
        val files: List<WalkedFile>,
        val unreadable: Long,
        val deferredPartials: Long,
        val complete: Boolean,
    ) : WalkOutcome()

    data object PermissionLost : WalkOutcome()
    data object Missing : WalkOutcome()
    data object Unreadable : WalkOutcome()
}

sealed class FileExistence {
    data object Present : FileExistence()
    data object Missing : FileExistence()
    data object Unknown : FileExistence()
}

/** Platform filesystem abstraction: SAF on Android, local files in JVM tests. */
interface FolderFileSystem {
    fun walk(treeUri: String): WalkOutcome
    fun readBytes(treeUri: String, documentId: String?, relPath: String, maxBytes: Long): ByteArray?
    fun sha256Hex(treeUri: String, documentId: String?, relPath: String, maxBytes: Long): String?
    fun stat(treeUri: String, documentId: String?, relPath: String): PlatformMetadata?
    fun checkExists(treeUri: String, documentId: String?, relPath: String): FileExistence
}
