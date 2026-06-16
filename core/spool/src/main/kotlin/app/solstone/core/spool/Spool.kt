// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.spool

import app.solstone.core.model.BundleFile
import app.solstone.core.model.BundleManifest
import app.solstone.core.model.GapEvent
import app.solstone.core.model.SegmentKey
import app.solstone.core.segment.SealedSegment
import app.solstone.core.segment.SegmentPayload
import java.io.InputStream
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

enum class SealState { DRAFT, WRITING_PAYLOADS, WRITING_MANIFEST, SEALED, FAILED }

fun interface PayloadBytesProvider {
    fun open(payload: SegmentPayload): InputStream
}

data class SealResult(val manifest: BundleManifest, val directory: Path?, val state: SealState)

data class ParsedManifest(
    val manifest: BundleManifest,
    val startEpochMs: Long,
    val endEpochMs: Long,
    val zoneId: String,
    val utcOffsetSeconds: Int,
)

interface SpoolWriter {
    fun seal(segment: SealedSegment, payloadBytes: PayloadBytesProvider): SealResult
}

class FileSpoolWriter(private val baseDir: Path) : SpoolWriter {
    override fun seal(segment: SealedSegment, payloadBytes: PayloadBytesProvider): SealResult {
        val draftDir = baseDir.resolve(".draft").resolve(segment.key.day).resolve(segment.stream).resolve(segment.key.segment)
        val finalDir = baseDir.resolve(segment.key.day).resolve(segment.stream).resolve(segment.key.segment)
        Files.createDirectories(draftDir.parent)
        if (Files.exists(draftDir)) draftDir.deleteRecursively()
        if (Files.exists(finalDir)) finalDir.deleteRecursively()
        Files.createDirectories(draftDir)

        val files = segment.payloads.map { payload ->
            val target = draftDir.resolve(payload.ref.name)
            require(target.normalize().parent == draftDir.normalize()) { "payload name must not contain path separators: ${payload.ref.name}" }
            val checksum = MessageDigest.getInstance("SHA-256")
            var byteSize = 0L
            payloadBytes.open(payload).use { input ->
                Files.newOutputStream(target).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        checksum.update(buffer, 0, read)
                        byteSize += read.toLong()
                    }
                }
            }
            BundleFile(
                sourceId = payload.sourceId,
                name = payload.ref.name,
                sha256 = checksum.digest().joinToString("") { "%02x".format(it) },
                byteSize = byteSize,
                mediaType = payload.ref.mediaType,
                captureStartEpochMs = payload.captureStartEpochMs,
                captureEndEpochMs = payload.captureEndEpochMs,
            )
        }.sortedWith(bundleFileComparator)

        val manifest = BundleManifest(
            key = segment.key,
            files = files,
            gaps = segment.gaps.sortedWith(gapComparator),
        )
        Files.writeString(draftDir.resolve("manifest"), serializeManifest(segment, manifest), StandardCharsets.UTF_8)
        Files.createDirectories(finalDir.parent)
        try {
            Files.move(draftDir, finalDir, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(draftDir, finalDir)
        }
        cleanupEmptyDraftParents(draftDir)
        return SealResult(manifest = manifest, directory = finalDir, state = SealState.SEALED)
    }

    private fun cleanupEmptyDraftParents(draftDir: Path) {
        generateSequence(draftDir.parent) { it.parent }
            .takeWhile { it != baseDir.resolve(".draft").parent }
            .forEach { dir ->
                if (Files.exists(dir) && Files.isDirectory(dir) && Files.list(dir).use { !it.findAny().isPresent }) {
                    Files.delete(dir)
                }
            }
    }
}

class CountingSpoolWriter : SpoolWriter {
    var sealedCount: Int = 0
        private set
    var bytesWritten: Long = 0
        private set
    val manifests: List<BundleManifest>
        get() = mutableManifests.toList()

    private val mutableManifests = mutableListOf<BundleManifest>()

    override fun seal(segment: SealedSegment, payloadBytes: PayloadBytesProvider): SealResult {
        val files = segment.payloads.map { payload ->
            val checksum = MessageDigest.getInstance("SHA-256")
            var byteSize = 0L
            payloadBytes.open(payload).use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    checksum.update(buffer, 0, read)
                    byteSize += read.toLong()
                }
            }
            BundleFile(
                sourceId = payload.sourceId,
                name = payload.ref.name,
                sha256 = checksum.digest().joinToString("") { "%02x".format(it) },
                byteSize = byteSize,
                mediaType = payload.ref.mediaType,
                captureStartEpochMs = payload.captureStartEpochMs,
                captureEndEpochMs = payload.captureEndEpochMs,
            )
        }.sortedWith(bundleFileComparator)
        val manifest = BundleManifest(segment.key, files, segment.gaps.sortedWith(gapComparator))
        sealedCount += 1
        bytesWritten += files.sumOf { it.byteSize }
        mutableManifests += manifest
        return SealResult(manifest = manifest, directory = null, state = SealState.SEALED)
    }
}

fun serializeManifest(segment: SealedSegment, manifest: BundleManifest): String {
    val builder = StringBuilder()
    builder.appendLine("solstone-bundle-manifest-v1")
    builder.appendLine("day=${escape(manifest.key.day)}")
    builder.appendLine("segment=${escape(manifest.key.segment)}")
    builder.appendLine("startEpochMs=${segment.wireKeys.startEpochMs}")
    builder.appendLine("endEpochMs=${segment.wireKeys.endEpochMs}")
    builder.appendLine("zoneId=${escape(segment.wireKeys.zoneId)}")
    builder.appendLine("utcOffsetSeconds=${segment.wireKeys.utcOffsetSeconds}")
    builder.appendLine("[files]")
    manifest.files.sortedWith(bundleFileComparator).forEach { file ->
        builder.appendLine(
            listOf(
                file.sourceId,
                file.name,
                file.sha256,
                file.byteSize.toString(),
                file.mediaType,
                file.captureStartEpochMs.toString(),
                file.captureEndEpochMs.toString(),
            ).joinToString("\t") { escape(it) },
        )
    }
    builder.appendLine("[gaps]")
    manifest.gaps.sortedWith(gapComparator).forEach { gap ->
        builder.appendLine(
            listOf(
                gap.kind,
                gap.atEpochMs.toString(),
                gap.detail ?: "",
            ).joinToString("\t") { escape(it) },
        )
    }
    return builder.toString()
}

fun parseManifest(text: String): ParsedManifest {
    val lines = text.lineSequence().filter { it.isNotEmpty() }.toList()
    require(lines.firstOrNull() == "solstone-bundle-manifest-v1") { "missing solstone bundle manifest header" }

    val filesIndex = lines.indexOf("[files]")
    val gapsIndex = lines.indexOf("[gaps]")
    require(filesIndex > 0 && gapsIndex > filesIndex) { "manifest sections are malformed" }

    val headers = lines.subList(1, filesIndex).associate { line ->
        val separator = line.indexOf('=')
        require(separator > 0) { "manifest header is malformed: $line" }
        line.substring(0, separator) to unescape(line.substring(separator + 1))
    }

    fun header(name: String): String =
        headers[name] ?: throw IllegalArgumentException("manifest header missing: $name")

    val key = SegmentKey(
        day = header("day"),
        segment = header("segment"),
    )
    val files = lines.subList(filesIndex + 1, gapsIndex).map { line ->
        val fields = tabFields(line, expected = 7)
        require(fields.size == 7) { "manifest file row is malformed" }
        BundleFile(
            sourceId = fields[0],
            name = fields[1],
            sha256 = fields[2],
            byteSize = fields[3].toLong(),
            mediaType = fields[4],
            captureStartEpochMs = fields[5].toLong(),
            captureEndEpochMs = fields[6].toLong(),
        )
    }
    val gaps = lines.subList(gapsIndex + 1, lines.size).map { line ->
        val fields = tabFields(line, expected = 3)
        require(fields.size == 3) { "manifest gap row is malformed" }
        GapEvent(
            kind = fields[0],
            atEpochMs = fields[1].toLong(),
            detail = fields[2].ifEmpty { null },
        )
    }

    return ParsedManifest(
        manifest = BundleManifest(key = key, files = files, gaps = gaps),
        startEpochMs = header("startEpochMs").toLong(),
        endEpochMs = header("endEpochMs").toLong(),
        zoneId = header("zoneId"),
        utcOffsetSeconds = header("utcOffsetSeconds").toInt(),
    )
}

private val bundleFileComparator = compareBy<BundleFile> { it.sourceId }
    .thenBy { it.name }
    .thenBy { it.captureStartEpochMs }
    .thenBy { it.captureEndEpochMs }

private val gapComparator = compareBy<GapEvent> { it.atEpochMs }
    .thenBy { it.kind }
    .thenBy { it.detail ?: "" }

private fun escape(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20")

private fun unescape(value: String): String = URLDecoder.decode(value, StandardCharsets.UTF_8)

private fun tabFields(line: String, expected: Int): List<String> {
    val fields = line.split('\t').toMutableList()
    while (fields.size < expected && line.endsWith('\t')) fields += ""
    return fields.map(::unescape)
}

private fun Path.deleteRecursively() {
    Files.walk(this).use { paths ->
        paths.sorted(Comparator.reverseOrder()).forEach(Files::delete)
    }
}
