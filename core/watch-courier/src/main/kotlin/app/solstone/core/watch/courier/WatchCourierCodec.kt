// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.watch.courier

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.security.DigestInputStream
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

data class CourierFilePayload(
    val name: String,
    val mediaType: String,
    val sha256: String,
    val byteSize: Long,
    val bytes: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CourierFilePayload) return false
        return name == other.name && mediaType == other.mediaType &&
            sha256 == other.sha256 && byteSize == other.byteSize &&
            bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int {
        var result = name.hashCode()
        result = 31 * result + mediaType.hashCode()
        result = 31 * result + sha256.hashCode()
        result = 31 * result + byteSize.hashCode()
        result = 31 * result + bytes.contentHashCode()
        return result
    }
}

data class CourierBundleHeader(
    val protocolVersion: Int,
    val bundleId: String,
    val day: String,
    val segment: String,
    val startEpochMs: Long,
    val endEpochMs: Long,
    val zoneId: String,
    val utcOffsetSeconds: Int,
    val sealGeneration: Long,
)

data class DecodedBundle(
    val header: CourierBundleHeader,
    val bundleSha256: String,
    val files: List<CourierFilePayload>,
)

data class WatchBundleReceipt(
    val protocolVersion: Int,
    val bundleId: String,
    val sha256: String,
    val sealGeneration: Long,
    val segmentId: String,
    val committedAt: Long,
)

sealed interface CodecResult<out T> {
    data class Success<T>(val value: T) : CodecResult<T>
    data class VersionMismatch(val version: Int) : CodecResult<Nothing>
    data class Malformed(val reason: String) : CodecResult<Nothing>
}

object WatchCourierCodec {
    private const val BUNDLE_MAGIC = 0x534F4C57 // "SOLW"
    private const val MAX_FILE_SIZE_BYTES = 16L * 1024L * 1024L // 16 MiB
    private const val MAX_FILE_COUNT = 2

    fun encodeBundle(
        header: CourierBundleHeader,
        files: List<CourierFilePayload>,
        output: OutputStream,
    ) {
        require(files.isNotEmpty() && files.size <= MAX_FILE_COUNT) {
            "Files count must be between 1 and $MAX_FILE_COUNT"
        }
        val dos = DataOutputStream(output)
        dos.writeInt(BUNDLE_MAGIC)
        dos.writeInt(header.protocolVersion)
        dos.writeUTF(header.bundleId)
        dos.writeUTF(header.day)
        dos.writeUTF(header.segment)
        dos.writeLong(header.startEpochMs)
        dos.writeLong(header.endEpochMs)
        dos.writeUTF(header.zoneId)
        dos.writeInt(header.utcOffsetSeconds)
        dos.writeLong(header.sealGeneration)
        dos.writeInt(files.size)

        for (file in files) {
            require(file.name == WatchCourierProtocol.FILE_NAME_AUDIO || file.name == WatchCourierProtocol.FILE_NAME_LOCATION) {
                "Disallowed file name: ${file.name}"
            }
            require(file.bytes.size.toLong() == file.byteSize) { "Declared size mismatch for ${file.name}" }
            require(file.byteSize <= MAX_FILE_SIZE_BYTES) { "File exceeds maximum size: ${file.byteSize}" }
            dos.writeUTF(file.name)
            dos.writeUTF(file.mediaType)
            dos.writeUTF(file.sha256)
            dos.writeLong(file.byteSize)
            dos.write(file.bytes)
        }
        dos.flush()
    }

    fun decodeBundle(input: InputStream): CodecResult<DecodedBundle> = runCatching {
        val digest = MessageDigest.getInstance("SHA-256")
        val dis = DataInputStream(DigestInputStream(input, digest))
        val header = when (val decoded = readHeader(dis)) {
            is CodecResult.Success -> decoded.value
            is CodecResult.VersionMismatch -> return decoded
            is CodecResult.Malformed -> return decoded
        }

        val fileCount = dis.readInt()
        if (fileCount <= 0 || fileCount > MAX_FILE_COUNT) {
            return CodecResult.Malformed("Invalid file count: $fileCount")
        }

        val files = mutableListOf<CourierFilePayload>()
        val fileNames = mutableSetOf<String>()

        for (i in 0 until fileCount) {
            val name = readBoundedUtf(dis, 32)
            if (name != WatchCourierProtocol.FILE_NAME_AUDIO && name != WatchCourierProtocol.FILE_NAME_LOCATION) {
                return CodecResult.Malformed("Disallowed file name: $name")
            }
            if (!fileNames.add(name)) {
                return CodecResult.Malformed("Duplicate file name: $name")
            }
            val mediaType = readBoundedUtf(dis, 128)
            val declaredSha = readBoundedUtf(dis, 64)
            val expectedMediaType = if (name == WatchCourierProtocol.FILE_NAME_AUDIO) {
                WatchCourierProtocol.MEDIA_TYPE_AUDIO
            } else {
                WatchCourierProtocol.MEDIA_TYPE_LOCATION
            }
            require(mediaType == expectedMediaType) { "Unexpected media type" }
            val size = dis.readLong()
            if (size <= 0 || size > MAX_FILE_SIZE_BYTES) {
                return CodecResult.Malformed("File size out of bounds: $size")
            }
            val bytes = ByteArray(size.toInt())
            dis.readFully(bytes)
            val fileDigest = sha256Hex(bytes)
            if (!fileDigest.equals(declaredSha, ignoreCase = true)) {
                return CodecResult.Malformed("File hash mismatch for $name")
            }
            files += CourierFilePayload(
                name = name,
                mediaType = mediaType,
                sha256 = fileDigest,
                byteSize = size,
                bytes = bytes,
            )
        }

        require(WatchCourierProtocol.FILE_NAME_AUDIO in fileNames) { "Audio file missing" }
        require(dis.read() == -1) { "Trailing bundle bytes" }
        val bundleSha = digest.digest().joinToString("") { "%02x".format(it) }
        CodecResult.Success(DecodedBundle(header, bundleSha, files))
    }.getOrElse { e ->
        CodecResult.Malformed("Failed to parse bundle: ${e.message}")
    }

    fun encodeReceipt(receipt: WatchBundleReceipt): ByteArray {
        val baos = ByteArrayOutputStream()
        val dos = DataOutputStream(baos)
        dos.writeInt(WatchCourierProtocol.KIND_RECEIPT)
        dos.writeInt(receipt.protocolVersion)
        dos.writeUTF(receipt.bundleId)
        dos.writeUTF(receipt.sha256)
        dos.writeLong(receipt.sealGeneration)
        dos.writeUTF(receipt.segmentId)
        dos.writeLong(receipt.committedAt)
        dos.flush()
        return baos.toByteArray()
    }

    fun decodeReceipt(bytes: ByteArray): CodecResult<WatchBundleReceipt> = runCatching {
        val dis = DataInputStream(ByteArrayInputStream(bytes))
        val kind = dis.readInt()
        if (kind != WatchCourierProtocol.KIND_RECEIPT) {
            return CodecResult.Malformed("Unexpected message kind: $kind")
        }
        val version = dis.readInt()
        if (version != WatchCourierProtocol.PROTOCOL_VERSION) {
            return CodecResult.VersionMismatch(version)
        }
        val bundleId = readBoundedUtf(dis, 128)
        val sha256 = readBoundedUtf(dis, 64)
        val sealGeneration = dis.readLong()
        val segmentId = readBoundedUtf(dis, 512)
        val committedAt = dis.readLong()
        require(bundleId.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}"))) { "Invalid receipt bundle ID" }
        require(sha256.matches(Regex("[0-9a-fA-F]{64}"))) { "Invalid receipt digest" }
        require(sealGeneration > 0 && segmentId.isNotBlank() && committedAt >= 0) { "Invalid custody receipt" }
        require(dis.read() == -1) { "Trailing receipt bytes" }
        CodecResult.Success(
            WatchBundleReceipt(
                protocolVersion = version,
                bundleId = bundleId,
                sha256 = sha256,
                sealGeneration = sealGeneration,
                segmentId = segmentId,
                committedAt = committedAt,
            ),
        )
    }.getOrElse { e ->
        CodecResult.Malformed("Receipt decode failed: ${e.message}")
    }

    /** Reads ordering metadata only; this does not verify payload custody. */
    fun inspectHeader(input: InputStream): CodecResult<CourierBundleHeader> = runCatching {
        readHeader(DataInputStream(input))
    }.getOrElse { CodecResult.Malformed("Header decode failed: ${it.message}") }

    private fun readHeader(dis: DataInputStream): CodecResult<CourierBundleHeader> {
        val magic = dis.readInt()
        if (magic != BUNDLE_MAGIC) {
            return CodecResult.Malformed("Invalid bundle magic: $magic")
        }
        val version = dis.readInt()
        if (version != WatchCourierProtocol.PROTOCOL_VERSION) {
            return CodecResult.VersionMismatch(version)
        }
        val bundleId = readBoundedUtf(dis, 128)
        if (bundleId.isBlank()) return CodecResult.Malformed("Blank bundle ID")
        val day = readBoundedUtf(dis, 8)
        val segment = readBoundedUtf(dis, 16)
        val startEpochMs = dis.readLong()
        val endEpochMs = dis.readLong()
        val zoneId = readBoundedUtf(dis, 128)
        val utcOffsetSeconds = dis.readInt()
        val sealGeneration = dis.readLong()

        val header = CourierBundleHeader(
            protocolVersion = version,
            bundleId = bundleId,
            day = day,
            segment = segment,
            startEpochMs = startEpochMs,
            endEpochMs = endEpochMs,
            zoneId = zoneId,
            utcOffsetSeconds = utcOffsetSeconds,
            sealGeneration = sealGeneration,
        )

        validateHeader(header)
        return CodecResult.Success(header)
    }

    private fun readBoundedUtf(input: DataInputStream, maximumBytes: Int): String {
        val length = input.readUnsignedShort()
        require(length <= maximumBytes) { "Text field exceeds its limit" }
        val encoded = ByteArray(length + 2)
        encoded[0] = (length ushr 8).toByte()
        encoded[1] = length.toByte()
        input.readFully(encoded, 2, length)
        return DataInputStream(ByteArrayInputStream(encoded)).readUTF()
    }

    private fun validateHeader(header: CourierBundleHeader) {
        require(header.bundleId.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}"))) { "Invalid bundle ID" }
        require(header.day.matches(Regex("[0-9]{8}"))) { "Invalid recording day" }
        LocalDate.parse(header.day, DateTimeFormatter.BASIC_ISO_DATE)
        require(header.segment.matches(Regex("[0-9]{6}_[1-9][0-9]{0,2}"))) { "Invalid recording key" }
        val durationMs = header.endEpochMs - header.startEpochMs
        require(header.endEpochMs > header.startEpochMs && durationMs > 0) { "Invalid recording span" }
        require(header.sealGeneration > 0) { "Invalid seal generation" }
        ZoneId.of(header.zoneId)
        // The watch's stamped offset is authoritative, including when the two
        // devices have different time-zone database versions.
        val start = Instant.ofEpochMilli(header.startEpochMs).atOffset(ZoneOffset.ofTotalSeconds(header.utcOffsetSeconds))
        val expectedDay = DateTimeFormatter.BASIC_ISO_DATE.format(start.toLocalDate())
        val length = (durationMs / 1000L).coerceIn(1L, 300L)
        val expectedKey = "%02d%02d%02d_%d".format(java.util.Locale.ROOT, start.hour, start.minute, start.second, length)
        require(header.day == expectedDay && header.segment == expectedKey) { "Recording clock fields disagree" }
    }

    fun sha256Hex(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
