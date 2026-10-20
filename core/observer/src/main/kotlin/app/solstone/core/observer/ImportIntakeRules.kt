// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.observer

/** What intake does with one shared or picked item, decided from its resolved type. */
sealed interface IntakeDecision {
    /** Copy it as it is. */
    data class Accept(val mime: String) : IntakeDecision

    /** Convert it to JPEG first (HEIC/HEIF photos, which the journal can't read as images). */
    data object ConvertToJpeg : IntakeDecision

    /** Leave it on the device; [reason] says why, for the owner. */
    data class Refuse(val reason: IntakeRefusal) : IntakeDecision
}

enum class IntakeRefusal {
    /** Video, or a type the journal doesn't import. */
    UNSUPPORTED_TYPE,

    /** A HEIC photo on a phone too old to convert it (before Android 9). */
    CANNOT_CONVERT,

    /** Not a content URI. */
    NOT_SHAREABLE,

    /** The stream couldn't be read, or the app's access to it ended. */
    UNREADABLE,

    /** Larger than the journal takes. */
    TOO_LARGE,

    /** Not enough room on this device to hold a copy. */
    NO_SPACE,
}

/** The images the journal imports as images (its image source). */
private val JOURNAL_IMAGES = setOf("image/png", "image/jpeg", "image/webp", "image/gif")
private val HEIC = setOf("image/heic", "image/heif", "image/heic-sequence", "image/heif-sequence")

/**
 * The type decision for one item: PDF, any audio, plain text and the journal's images are taken;
 * HEIC/HEIF is converted where the phone can ([canConvertHeic], Android 9+); everything else,
 * video included, is refused. A missing type is refused rather than guessed.
 */
fun intakeDecision(scheme: String?, resolvedMime: String?, canConvertHeic: Boolean): IntakeDecision {
    if (scheme != "content") return IntakeDecision.Refuse(IntakeRefusal.NOT_SHAREABLE)
    val mime = resolvedMime?.substringBefore(';')?.trim()?.lowercase() ?: return IntakeDecision.Refuse(IntakeRefusal.UNSUPPORTED_TYPE)
    return when {
        mime == "application/pdf" -> IntakeDecision.Accept(mime)
        mime == "text/plain" -> IntakeDecision.Accept(mime)
        mime.startsWith("audio/") -> IntakeDecision.Accept(mime)
        mime in JOURNAL_IMAGES -> IntakeDecision.Accept(mime)
        mime in HEIC -> if (canConvertHeic) IntakeDecision.ConvertToJpeg else IntakeDecision.Refuse(IntakeRefusal.CANNOT_CONVERT)
        else -> IntakeDecision.Refuse(IntakeRefusal.UNSUPPORTED_TYPE)
    }
}

private val EXTENSION_FOR_MIME = mapOf(
    "application/pdf" to "pdf",
    "text/plain" to "txt",
    "image/png" to "png",
    "image/jpeg" to "jpg",
    "image/webp" to "webp",
    "image/gif" to "gif",
    "audio/mpeg" to "mp3",
    "audio/mp4" to "m4a",
    "audio/aac" to "aac",
    "audio/ogg" to "ogg",
    "audio/opus" to "opus",
    "audio/flac" to "flac",
    "audio/x-flac" to "flac",
    "audio/wav" to "wav",
    "audio/x-wav" to "wav",
    "audio/webm" to "webm",
    "audio/amr" to "amr",
    "audio/3gpp" to "3gp",
)

/**
 * The name an item is sent under. The journal decides an item's kind from its extension, so a
 * name with none (or none at all, as photo pickers and some providers give) gets one from its
 * type. A converted HEIC photo is sent as `.jpg`.
 */
fun intakeName(displayName: String?, mime: String, convertedToJpeg: Boolean = false): String {
    val sendMime = if (convertedToJpeg) "image/jpeg" else mime.substringBefore(';').trim().lowercase()
    val wanted = EXTENSION_FOR_MIME[sendMime] ?: sendMime.substringAfter('/').filter { it.isLetterOrDigit() }.ifEmpty { "bin" }
    val base = displayName?.substringAfterLast('/')?.substringAfterLast('\\')?.trim().orEmpty()
    if (base.isEmpty()) return "file.$wanted"
    val dot = base.lastIndexOf('.')
    val hasExtension = dot in 1 until base.length - 1
    if (convertedToJpeg) return (if (hasExtension) base.substring(0, dot) else base) + ".jpg"
    return if (hasExtension) base else "$base.$wanted"
}

/** The most an import may be: the journal's limit for one file. */
const val INTAKE_MAX_BYTES: Long = 4L * 1024 * 1024 * 1024

/**
 * Space that must stay free on the device after a copy: capture's screen floor, so an import never
 * starves the owner's own capture of room.
 */
const val INTAKE_MIN_FREE_BYTES: Long = 256L * 1024 * 1024

/** Whether copying [nextChunk] more bytes, after [copiedSoFar], may go ahead. */
fun intakeMayCopy(copiedSoFar: Long, nextChunk: Long, usableSpace: Long, maxBytes: Long = INTAKE_MAX_BYTES, minFree: Long = INTAKE_MIN_FREE_BYTES): IntakeRefusal? =
    when {
        copiedSoFar + nextChunk > maxBytes -> IntakeRefusal.TOO_LARGE
        usableSpace - nextChunk < minFree -> IntakeRefusal.NO_SPACE
        else -> null
    }

/** Whether an item may start copying at all, given the size its source reports (if any). */
fun intakeMayStart(reportedSize: Long?, usableSpace: Long, maxBytes: Long = INTAKE_MAX_BYTES, minFree: Long = INTAKE_MIN_FREE_BYTES): IntakeRefusal? =
    when {
        reportedSize != null && reportedSize > maxBytes -> IntakeRefusal.TOO_LARGE
        reportedSize != null && usableSpace - reportedSize < minFree -> IntakeRefusal.NO_SPACE
        usableSpace < minFree -> IntakeRefusal.NO_SPACE
        else -> null
    }
