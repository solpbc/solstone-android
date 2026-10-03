// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.spool

import app.solstone.core.model.BundleFile
import app.solstone.core.model.BundleManifest
import app.solstone.core.model.SegmentKey
import app.solstone.core.model.WireKeys
import app.solstone.core.segment.SealedSegment
import app.solstone.core.segment.SegmentPayload
import app.solstone.core.sources.PayloadRef
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileSpoolWriterTest {
    @Test
    fun sealsDstTwinsWithBareAndSuffixedDirLeaves() {
        withTempDir { baseDir ->
            val first = segment(startEpochMs = FIRST_START, payloadName = "first.bin")
            val second = segment(startEpochMs = SECOND_START, payloadName = "second.bin")
            val provider = provider(
                "first.bin" to "first-bytes".toByteArray(),
                "second.bin" to "second-bytes".toByteArray(),
            )
            val writer = FileSpoolWriter(baseDir)

            val firstResult = writer.seal(first, provider)
            val secondResult = writer.seal(second, provider)

            val firstDir = baseDir.resolve(DAY).resolve(STREAM).resolve(WIRE_SEGMENT)
            val secondLeaf = "${WIRE_SEGMENT}__ws$SECOND_START"
            val secondDir = baseDir.resolve(DAY).resolve(STREAM).resolve(secondLeaf)
            assertEquals(firstDir, firstResult.directory)
            assertEquals(secondDir, secondResult.directory)
            assertContentEquals("first-bytes".toByteArray(), Files.readAllBytes(firstDir.resolve("first.bin")))
            assertContentEquals("second-bytes".toByteArray(), Files.readAllBytes(secondDir.resolve("second.bin")))
            assertTrue(String(Files.readAllBytes(firstDir.resolve("manifest")), StandardCharsets.UTF_8).contains("segment=$WIRE_SEGMENT\n"))
            assertTrue(String(Files.readAllBytes(secondDir.resolve("manifest")), StandardCharsets.UTF_8).contains("segment=$WIRE_SEGMENT\n"))
        }
    }

    @Test
    fun sealsWithCollisionLeafWhenBareLeafIsOccupiedEvenIfDirectoryIsAbsent() {
        withTempDir { baseDir ->
            val segment = segment(startEpochMs = SECOND_START, payloadName = "second.bin")
            val provider = provider("second.bin" to "second-bytes".toByteArray())
            val writer = FileSpoolWriter(
                baseDir = baseDir,
                occupiedLeaves = { day, stream ->
                    if (day == DAY && stream == STREAM) setOf(WIRE_SEGMENT) else emptySet()
                },
            )

            val result = writer.seal(segment, provider)

            val collisionLeaf = "${WIRE_SEGMENT}__ws$SECOND_START"
            val expectedDir = baseDir.resolve(DAY).resolve(STREAM).resolve(collisionLeaf)
            assertEquals(expectedDir, result.directory)
            assertTrue(Files.exists(expectedDir.resolve("manifest")))
            assertFalse(Files.exists(baseDir.resolve(DAY).resolve(STREAM).resolve(WIRE_SEGMENT)))
        }
    }

    @Test
    fun resealSameSegmentReturnsExistingFinalWithoutDeleting() {
        withTempDir { baseDir ->
            val segment = segment(startEpochMs = FIRST_START, payloadName = "first.bin", declaredByteSize = 11L)
            val writer = FileSpoolWriter(baseDir)
            val firstResult = writer.seal(segment, provider("first.bin" to "first-bytes".toByteArray()))
            val finalDir = firstResult.directory ?: error("missing final dir")
            Files.write(finalDir.resolve("sentinel"), "keep".toByteArray())

            val secondResult = writer.seal(
                segment,
                provider("first.bin" to "first-bytes".toByteArray()),
            )

            assertEquals(finalDir, secondResult.directory)
            assertEquals("keep", String(Files.readAllBytes(finalDir.resolve("sentinel")), StandardCharsets.UTF_8))
            assertContentEquals("first-bytes".toByteArray(), Files.readAllBytes(finalDir.resolve("first.bin")))
        }
    }

    @Test
    fun directoryWithLongerPrefixIsNotTreatedAsLeafOfShorterWireKey() {
        withTempDir { baseDir ->
            val longerLeaf = "235500_300"
            val shorterWire = "235500_30"
            val streamDir = baseDir.resolve(DAY).resolve(STREAM)
            val longerDir = streamDir.resolve(longerLeaf)
            Files.createDirectories(longerDir)
            val payload = longerDir.resolve("first.bin")
            Files.write(payload, "first-bytes".toByteArray())
            val longerSealed = SealedSegment(
                stream = STREAM,
                key = SegmentKey(DAY, longerLeaf),
                wireKeys = WireKeys(DAY, longerLeaf, FIRST_START, FIRST_START + 300_000L, "Asia/Tokyo", 32400),
                payloads = emptyList(),
                gaps = emptyList(),
            )
            val manifestContent = serializeManifest(
                longerSealed,
                BundleManifest(
                    key = longerSealed.key,
                    files = listOf(BundleFile("source", "first.bin", "sha", 11L, "application/octet-stream", FIRST_START, FIRST_START + 300_000L)),
                    gaps = emptyList(),
                ),
            )
            Files.write(longerDir.resolve("manifest"), manifestContent.toByteArray(StandardCharsets.UTF_8))
            Files.write(longerDir.resolve("sentinel"), "sentinel-bytes".toByteArray())

            val shorterSegment = SealedSegment(
                stream = STREAM,
                key = SegmentKey(DAY, shorterWire),
                wireKeys = WireKeys(DAY, shorterWire, FIRST_START, FIRST_START + 300_000L, "Asia/Tokyo", 32400),
                payloads = listOf(
                    SegmentPayload("source", PayloadRef("first.bin", "application/octet-stream", 11L, null), FIRST_START, FIRST_START + 300_000L),
                ),
                gaps = emptyList(),
            )
            val writer = FileSpoolWriter(baseDir)
            val result = writer.seal(shorterSegment, provider("first.bin" to "first-bytes".toByteArray()))

            val expectedBareDir = streamDir.resolve(shorterWire)
            assertEquals(expectedBareDir, result.directory)
            assertTrue(Files.exists(expectedBareDir.resolve("manifest")))
            val shorterManifest = String(Files.readAllBytes(expectedBareDir.resolve("manifest")), StandardCharsets.UTF_8)
            assertTrue(shorterManifest.contains("segment=$shorterWire\n"))

            assertEquals("sentinel-bytes", String(Files.readAllBytes(longerDir.resolve("sentinel")), StandardCharsets.UTF_8))
            assertEquals(manifestContent, String(Files.readAllBytes(longerDir.resolve("manifest")), StandardCharsets.UTF_8))
        }
    }

    @Test
    fun unreadableBareManifestAllocatesCollisionLeafAndPreservesUnreadableDirectory() {
        withTempDir { baseDir ->
            val streamDir = baseDir.resolve(DAY).resolve(STREAM)
            val bareDir = streamDir.resolve(WIRE_SEGMENT)
            Files.createDirectories(bareDir)
            Files.write(bareDir.resolve("manifest"), "corrupt manifest header".toByteArray())
            Files.write(bareDir.resolve("sentinel"), "keep-corrupt".toByteArray())

            val segment = segment(startEpochMs = FIRST_START, payloadName = "first.bin", declaredByteSize = 11L)
            val writer = FileSpoolWriter(baseDir)
            val result = writer.seal(segment, provider("first.bin" to "first-bytes".toByteArray()))

            val collisionLeaf = "${WIRE_SEGMENT}__ws$FIRST_START"
            val expectedDir = streamDir.resolve(collisionLeaf)
            assertEquals(expectedDir, result.directory)
            assertEquals(SealState.SEALED, result.state)
            assertEquals("keep-corrupt", String(Files.readAllBytes(bareDir.resolve("sentinel")), StandardCharsets.UTF_8))
            assertEquals("corrupt manifest header", String(Files.readAllBytes(bareDir.resolve("manifest")), StandardCharsets.UTF_8))
        }
    }

    @Test
    fun unreadableCollisionManifestWithOccupiedBareAllocatesThirdFormLeaf() {
        withTempDir { baseDir ->
            val streamDir = baseDir.resolve(DAY).resolve(STREAM)
            val collisionLeaf = "${WIRE_SEGMENT}__ws$FIRST_START"
            val collisionDir = streamDir.resolve(collisionLeaf)
            Files.createDirectories(collisionDir)
            Files.write(collisionDir.resolve("manifest"), "corrupt collision manifest".toByteArray())
            Files.write(collisionDir.resolve("sentinel"), "keep-collision".toByteArray())

            val segment = segment(startEpochMs = FIRST_START, payloadName = "first.bin", declaredByteSize = 11L)
            val writer = FileSpoolWriter(
                baseDir = baseDir,
                occupiedLeaves = { day, stream ->
                    if (day == DAY && stream == STREAM) setOf(WIRE_SEGMENT) else emptySet()
                },
            )
            val result = writer.seal(segment, provider("first.bin" to "first-bytes".toByteArray()))

            val expectedDir = streamDir.resolve("${WIRE_SEGMENT}__2")
            assertEquals(expectedDir, result.directory)
            assertEquals(SealState.SEALED, result.state)
            assertEquals("keep-collision", String(Files.readAllBytes(collisionDir.resolve("sentinel")), StandardCharsets.UTF_8))
            assertEquals("corrupt collision manifest", String(Files.readAllBytes(collisionDir.resolve("manifest")), StandardCharsets.UTF_8))
        }
    }

    @Test
    fun occupiedLeavesThrowAbortsSealWithoutCreatingDirectories() {
        withTempDir { baseDir ->
            val segment = segment(startEpochMs = FIRST_START, payloadName = "first.bin", declaredByteSize = 11L)
            val writer = FileSpoolWriter(
                baseDir = baseDir,
                occupiedLeaves = { _, _ -> throw IllegalStateException("db failure during occupied lookup") },
            )

            val ex = assertFailsWith<IllegalStateException> {
                writer.seal(segment, provider("first.bin" to "first-bytes".toByteArray()))
            }
            assertEquals("db failure during occupied lookup", ex.message)
            assertFalse(Files.exists(baseDir.resolve(DAY)))
            assertFalse(Files.exists(baseDir.resolve(".draft")))
        }
    }

    @Test
    fun sealFsyncsPayloadsAndManifestBeforeMove() {
        withTempDir { baseDir ->
            val segment = segment(startEpochMs = FIRST_START, payloadName = "first.bin")
            val finalDir = baseDir.resolve(DAY).resolve(STREAM).resolve(WIRE_SEGMENT)
            val synced = mutableListOf<Path>()
            val writer = FileSpoolWriter(baseDir, fsync = { path ->
                if (!Files.isDirectory(path)) {
                    assertFalse(Files.exists(finalDir), "payload and manifest sync must precede publication")
                }
                synced.add(path)
            })

            writer.seal(segment, provider("first.bin" to "first-bytes".toByteArray()))

            assertEquals(
                listOf(
                    baseDir.resolve(".draft").resolve(DAY).resolve(STREAM).resolve(WIRE_SEGMENT).resolve("first.bin"),
                    baseDir.resolve(".draft").resolve(DAY).resolve(STREAM).resolve(WIRE_SEGMENT).resolve("manifest"),
                    finalDir,
                    finalDir.parent,
                    finalDir.parent.parent,
                    baseDir,
                    baseDir.parent,
                ),
                synced,
            )
        }
    }

    @Test
    fun sameSizeCorruptFinalCannotClaimCustodyOfOriginal() {
        withTempDir { baseDir ->
            val segment = segment(FIRST_START, "first.bin", 11L)
            val bytes = provider("first.bin" to "first-bytes".toByteArray())
            val writer = FileSpoolWriter(baseDir)
            val first = writer.seal(segment, bytes).directory!!
            Files.write(first.resolve("first.bin"), "wrong-bytes".toByteArray())
            val recovered = writer.seal(segment, bytes).directory!!
            assertTrue(first != recovered)
            assertContentEquals("first-bytes".toByteArray(), Files.readAllBytes(recovered.resolve("first.bin")))
            assertContentEquals("wrong-bytes".toByteArray(), Files.readAllBytes(first.resolve("first.bin")))
        }
    }

    @Test
    fun sameDescriptorsWithDifferentIncomingBytesKeepBothTakes() {
        withTempDir { baseDir ->
            val segment = segment(FIRST_START, "first.bin", 11L)
            val writer = FileSpoolWriter(baseDir)
            val first = writer.seal(segment, provider("first.bin" to "first-bytes".toByteArray())).directory!!
            val second = writer.seal(segment, provider("first.bin" to "other-bytes".toByteArray())).directory!!
            assertTrue(first != second)
            assertContentEquals("first-bytes".toByteArray(), Files.readAllBytes(first.resolve("first.bin")))
            assertContentEquals("other-bytes".toByteArray(), Files.readAllBytes(second.resolve("first.bin")))
        }
    }

    @Test
    fun directorySyncFailureNeverReturnsSealedCustody() {
        withTempDir { baseDir ->
            val writer = FileSpoolWriter(baseDir, fsync = {
                if (Files.isDirectory(it)) throw java.io.IOException("publication sync failed")
            })
            assertFailsWith<java.io.IOException> {
                writer.seal(segment(FIRST_START, "first.bin", 11L), provider("first.bin" to "first-bytes".toByteArray()))
            }
        }
    }

    @Test
    fun rejectsPayloadNamePathTraversal() {
        withTempDir { baseDir ->
            val segment = segment(startEpochMs = FIRST_START, payloadName = "../escape.bin")

            assertFailsWith<IllegalArgumentException> {
                FileSpoolWriter(baseDir).seal(segment, provider("../escape.bin" to "bytes".toByteArray()))
            }
        }
    }

    @Test
    fun cleansDraftDirectoryAfterSeal() {
        withTempDir { baseDir ->
            val segment = segment(startEpochMs = FIRST_START, payloadName = "first.bin")

            FileSpoolWriter(baseDir).seal(segment, provider("first.bin" to "first-bytes".toByteArray()))

            assertFalse(Files.exists(baseDir.resolve(".draft")))
        }
    }

    private fun segment(
        startEpochMs: Long,
        payloadName: String,
        declaredByteSize: Long = payloadName.length.toLong(),
    ): SealedSegment =
        SealedSegment(
            stream = STREAM,
            key = SegmentKey(DAY, WIRE_SEGMENT),
            wireKeys = WireKeys(
                day = DAY,
                segment = WIRE_SEGMENT,
                startEpochMs = startEpochMs,
                endEpochMs = startEpochMs + 300_000L,
                zoneId = "America/New_York",
                utcOffsetSeconds = if (startEpochMs == FIRST_START) -14_400 else -18_000,
            ),
            payloads = listOf(
                SegmentPayload(
                    sourceId = "source",
                    ref = PayloadRef(payloadName, "application/octet-stream", declaredByteSize, null),
                    captureStartEpochMs = startEpochMs,
                    captureEndEpochMs = startEpochMs + 300_000L,
                ),
            ),
            gaps = emptyList(),
        )

    private fun provider(vararg entries: Pair<String, ByteArray>): PayloadBytesProvider {
        val bytesByName = entries.toMap()
        return object : PayloadBytesProvider {
            override fun open(payload: SegmentPayload): ByteArrayInputStream =
                ByteArrayInputStream(bytesByName.getValue(payload.ref.name))
        }
    }

    private fun withTempDir(block: (Path) -> Unit) {
        val dir = Files.createTempDirectory("solstone-file-spool-writer")
        try {
            block(dir)
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun Path.deleteRecursively() {
        Files.walk(this).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach(Files::delete)
        }
    }

    private companion object {
        const val DAY = "20261101"
        const val STREAM = "observer"
        const val WIRE_SEGMENT = "011500_300"
        const val FIRST_START = 1_793_515_500_000L
        const val SECOND_START = FIRST_START + 3_600_000L
    }
}
