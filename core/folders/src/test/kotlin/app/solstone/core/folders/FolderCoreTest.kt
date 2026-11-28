// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.core.folders

import app.solstone.core.pl.HttpResponse
import app.solstone.core.pl.PlHttpClient
import app.solstone.core.pl.parseJson
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class TestClock(private var nowMs: Long, private val offsetS: Int = 0) : FolderClock {
    override fun nowMs(): Long = nowMs
    override fun offsetSecondsAt(epochMs: Long): Int = offsetS
    fun advanceMs(delta: Long) {
        nowMs += delta
    }
}

private class FakeFolderFs : FolderFileSystem {
    data class Entry(
        val relPath: String,
        val documentId: String?,
        val bytes: ByteArray,
        val modifiedMs: Long,
        val isScreenshot: Boolean = false,
        val originUrl: String? = null,
    )

    val trees = LinkedHashMap<String, MutableMap<String, Entry>>()
    val walkOverrides = LinkedHashMap<String, WalkOutcome>()
    var unreadableCount: Long = 0L
    var deferredPartials: Long = 0L
    var walkComplete: Boolean = true

    fun put(
        treeUri: String,
        relPath: String,
        bytes: ByteArray,
        modifiedMs: Long,
        docId: String? = "doc:$relPath",
        isScreenshot: Boolean = false,
        originUrl: String? = null,
    ) {
        trees.getOrPut(treeUri) { LinkedHashMap() }[relPath] =
            Entry(relPath, docId, bytes, modifiedMs, isScreenshot, originUrl)
    }

    fun remove(treeUri: String, relPath: String) {
        trees[treeUri]?.remove(relPath)
    }

    fun rename(treeUri: String, oldRel: String, newRel: String, keepDocId: Boolean = true) {
        val map = trees[treeUri] ?: return
        val old = map.remove(oldRel) ?: return
        map[newRel] = old.copy(
            relPath = newRel,
            documentId = if (keepDocId) old.documentId else "doc:$newRel",
        )
    }

    override fun walk(treeUri: String): WalkOutcome {
        walkOverrides[treeUri]?.let { return it }
        val entries = trees[treeUri]?.values.orEmpty()
        val files = entries.mapNotNull { e ->
            val name = e.relPath.substringAfterLast('/')
            if (isJunkEntry(name) || isPartialDownload(name)) return@mapNotNull null
            WalkedFile(
                relPath = e.relPath,
                documentId = e.documentId,
                sizeBytes = e.bytes.size.toLong(),
                modifiedMs = e.modifiedMs,
                metadata = PlatformMetadata(
                    sizeBytes = e.bytes.size.toLong(),
                    modifiedMs = e.modifiedMs,
                    extension = extensionLower(e.relPath),
                    originUrl = e.originUrl,
                ),
                isScreenshot = e.isScreenshot,
            )
        }
        return WalkOutcome.Ok(
            files = files,
            unreadable = unreadableCount,
            deferredPartials = deferredPartials,
            complete = walkComplete,
        )
    }

    override fun readBytes(treeUri: String, documentId: String?, relPath: String, maxBytes: Long): ByteArray? {
        val entry = trees[treeUri]?.get(relPath) ?: return null
        if (entry.bytes.size.toLong() > maxBytes) return null
        return entry.bytes
    }

    override fun sha256Hex(treeUri: String, documentId: String?, relPath: String, maxBytes: Long): String? =
        readBytes(treeUri, documentId, relPath, maxBytes)?.let(::sha256Hex)

    override fun stat(treeUri: String, documentId: String?, relPath: String): PlatformMetadata? {
        val e = trees[treeUri]?.get(relPath) ?: return null
        return PlatformMetadata(
            sizeBytes = e.bytes.size.toLong(),
            modifiedMs = e.modifiedMs,
            extension = extensionLower(e.relPath),
            originUrl = e.originUrl,
        )
    }

    override fun checkExists(treeUri: String, documentId: String?, relPath: String): FileExistence {
        if (walkOverrides[treeUri] != null) return FileExistence.Unknown
        return if (trees[treeUri]?.containsKey(relPath) == true) FileExistence.Present else FileExistence.Missing
    }
}

class FolderCoreTest {
    private fun tempLedger(): File = File.createTempFile("folder-ledger-", ".json").apply {
        delete()
        deleteOnExit()
    }

    @Test
    fun partialsAndJunkAreFilteredDeterministically() {
        assertTrue(isPartialDownload("archive.tar.xz.part"))
        assertTrue(isPartialDownload("app.apk.crdownload"))
        assertTrue(isPartialDownload("video.mp4.download"))
        assertTrue(isPartialDownload("draft.tmp"))
        assertFalse(isPartialDownload("report.pdf"))

        assertTrue(isJunkEntry(".DS_Store"))
        assertTrue(isJunkEntry(".git"))
        assertTrue(isJunkEntry("Thumbs.db"))
        assertTrue(isJunkEntry("~\$contract.docx"))
        assertTrue(isJunkEntry("notes.md~"))
        assertFalse(isJunkEntry("todo.md"))
    }

    @Test
    fun sortingTableRoutesEveryKindAndRespectsLimitsAndSwitches() {
        val supported = SupportedList()
        val allOn = FolderKinds()

        assertEquals(
            FileKind.NOTE to DeliveryMode.CONTENT,
            classifyFile("md", 120L, false, "short note".toByteArray(), allOn, supported),
        )
        assertEquals(
            FileKind.DOCUMENT to DeliveryMode.CONTENT,
            classifyFile("pdf", 400_000L, false, null, allOn, supported),
        )
        assertEquals(
            FileKind.TRANSCRIPT to DeliveryMode.CONTENT,
            classifyFile("vtt", 8_000L, false, null, allOn, supported),
        )
        assertEquals(
            FileKind.SCREENSHOT to DeliveryMode.CONTENT,
            classifyFile("png", 50_000L, true, null, allOn, supported),
        )
        assertEquals(
            FileKind.PHOTO to DeliveryMode.CONTENT,
            classifyFile("png", 50_000L, false, null, allOn, supported),
        )
        assertEquals(
            FileKind.PHOTO to DeliveryMode.CONTENT,
            classifyFile("heic", 50_000L, false, null, allOn, supported),
        )
        // Unsupported / Office / binary -> metadata only when `other` is on.
        assertEquals(
            FileKind.OTHER to DeliveryMode.METADATA_ONLY,
            classifyFile("docx", 50_000L, false, null, allOn, supported),
        )
        assertEquals(
            FileKind.OTHER to DeliveryMode.METADATA_ONLY,
            classifyFile("apk", 58_000_000L, false, null, allOn, supported),
        )
        // Over 64 MiB -> metadata only even for supported document.
        assertEquals(
            FileKind.DOCUMENT to DeliveryMode.METADATA_ONLY,
            classifyFile("pdf", supported.maxContentBytes + 1L, false, null, allOn, supported),
        )
        // Disabled documents with `other` on falls back to metadata only.
        val noDocs = allOn.copy(documents = false)
        assertEquals(
            FileKind.DOCUMENT to DeliveryMode.METADATA_ONLY,
            classifyFile("pdf", 10_000L, false, null, noDocs, supported),
        )
        // Disabled both documents and `other` ignores PDFs completely.
        val noDocsOrOther = noDocs.copy(other = false)
        assertEquals(
            FileKind.DOCUMENT to DeliveryMode.IGNORE,
            classifyFile("pdf", 10_000L, false, null, noDocsOrOther, supported),
        )
    }

    @Test
    fun structuredAndLongMarkdownPromoteToDocument() {
        val supported = SupportedList()
        val structured = buildString {
            append("# Chapter One\n\n## Section A\n\n")
            repeat(1_500) { append("prose ") }
        }
        assertEquals(FileKind.DOCUMENT, classifyTextFile(structured.toByteArray(), supported))

        val longProse = buildString { repeat(5_000) { append("word ") } }
        assertEquals(FileKind.DOCUMENT, classifyTextFile(longProse.toByteArray(), supported))

        val shortNote = "# Todo\n\n- check dates\n- send build\n".toByteArray()
        assertEquals(FileKind.NOTE, classifyTextFile(shortNote, supported))
    }

    @Test
    fun overlapDetectsObsidianScreenshotsParentChildAndDuplicate() {
        val vault = FolderIdentity("obs", "content://tree/primary:Vault", "primary:Vault", "field notes")
        val shots = FolderIdentity("shots", "content://tree/primary:Pictures/Screenshots", "primary:Pictures/Screenshots", "Screenshots")
        val existing = listOf(FolderIdentity("f1", "content://tree/primary:Downloads", "primary:Downloads", "Downloads"))

        assertEquals(
            OverlapOutcome.Overlap(OverlapTarget.Obsidian("field notes")),
            detectOverlap("content://tree/primary:Vault/notes", "primary:Vault/notes", vault, shots, existing),
        )
        assertEquals(
            OverlapOutcome.Overlap(OverlapTarget.Screenshots("Screenshots")),
            detectOverlap("content://tree/primary:Pictures", "primary:Pictures", vault, shots, existing),
        )
        assertEquals(
            OverlapOutcome.AlreadyAdded("f1", "Downloads"),
            detectOverlap("content://tree/primary:Downloads", "primary:Downloads", vault, shots, existing),
        )
        assertEquals(
            OverlapOutcome.Overlap(OverlapTarget.Folder("f1", "Downloads")),
            detectOverlap("content://tree/primary:Downloads/work", "primary:Downloads/work", vault, shots, existing),
        )
    }

    @Test
    fun previewSendsNothingBeforeConfirmAndFiveMinuteWindowsGroupFiles() {
        val tBase = 1_795_689_600_000L // 2026-11-26T10:40:00Z
        val clock = TestClock(tBase + 360_000L, 0) // 10:46:00Z
        val fs = FakeFolderFs()
        val tree = "content://tree/primary:Work"
        fs.put(tree, "todo.md", "# todo\n- check dates\n".toByteArray(), tBase + 120_000L)
        fs.put(tree, "contract.pdf", "%PDF-1.4 sample".toByteArray(), tBase + 180_000L)
        fs.put(tree, "installer.apk", "binary".toByteArray(), tBase + 240_000L)
        fs.put(tree, "unfinished.crdownload", "partial".toByteArray(), tBase + 250_000L)
        fs.put(tree, ".hidden", "hidden".toByteArray(), tBase + 255_000L)

        val engine = FolderEngine(tempLedger(), fs, clock)
        val started = engine.beginSetup(tree, "Work", "Work", "primary:Work") as BeginSetupResult.Started
        val (preview, estimateItems) = engine.previewFolderWithItems(started.folderId, FolderSpan.LAST_30_DAYS)!!
        assertEquals(3L, preview.totalItems)
        assertEquals(1L, preview.notes)
        assertEquals(1L, preview.documents)
        assertEquals(1L, preview.other)
        assertEquals(2L, preview.contentItems)
        assertEquals(1L, preview.metadataOnlyItems)
        assertEquals(3, estimateItems.size)
        // Zero uploads queued before confirm!
        assertNull(engine.nextDelivery(false))

        assertEquals(ConfirmResult.Ok, engine.confirmFolder(started.folderId, FolderSpan.LAST_30_DAYS))
        val delivery = engine.nextDelivery(false)!!
        assertEquals(3, delivery.events.size)
        assertEquals(2, delivery.contentFiles.size)
        assertEquals("20261126", delivery.window.day)
        assertEquals("104000_300", delivery.window.segment)
    }

    @Test
    fun noteEditsCoalesceAndRevertCancelsWhileDocumentWaitsOneHourForRevision() {
        val t0 = 1_795_689_600_000L // 10:40:00Z
        val clock = TestClock(t0 + 60_000L, 0)
        val fs = FakeFolderFs()
        val tree = "content://tree/primary:Work"
        val engine = FolderEngine(tempLedger(), fs, clock)
        val id = (engine.beginSetup(tree, "Work", "Work", "primary:Work") as BeginSetupResult.Started).folderId
        assertEquals(ConfirmResult.Ok, engine.confirmFolder(id, FolderSpan.TODAY))

        fs.put(tree, "todo.md", "line 1\nline 2\n".toByteArray(), t0 + 10_000L)
        fs.put(tree, "proposal.pdf", "%PDF-1.4 v1".toByteArray(), t0 + 20_000L)
        engine.scanAll()
        assertNull(engine.nextDelivery(false))

        // Close 10:40 window (10:45:05Z) and deliver first sight.
        clock.advanceMs(245_000L)
        val first = engine.nextDelivery(false)!!
        assertEquals(2, first.events.size)
        engine.recordOutcome(first, DeliveryOutcome.Delivered)

        // Edit note twice in 10:45-10:50 window, and save a new PDF revision.
        val tEdit1 = clock.nowMs()
        fs.put(tree, "todo.md", "line 1\nline 2\nline 3 temp\n".toByteArray(), tEdit1)
        fs.put(tree, "proposal.pdf", "%PDF-1.4 v2".toByteArray(), tEdit1)
        clock.advanceMs(10_000L)
        engine.scanAll()

        val tEdit2 = clock.nowMs()
        fs.put(tree, "todo.md", "line 1\nline 2\nline 3 final\n".toByteArray(), tEdit2)
        clock.advanceMs(10_000L)
        engine.scanAll()

        // Close 10:45 window (10:50:25Z): coalesced note edit ready; PDF 1h cool-down not elapsed.
        clock.advanceMs(300_000L)
        val noteSeg = engine.nextDelivery(false)!!
        assertEquals(1, noteSeg.events.size)
        assertEquals("todo.md", noteSeg.events[0].relPath)
        assertEquals(ActivityVerb.EDITED, noteSeg.events[0].verb)
        assertTrue(noteSeg.events[0].notePayload!!.contains("line 3 final"))
        assertFalse(noteSeg.events[0].notePayload!!.contains("line 3 temp"))
        engine.recordOutcome(noteSeg, DeliveryOutcome.Delivered)

        // Edit and revert within the next window -> cancelled cleanly!
        val tRev = clock.nowMs()
        fs.put(tree, "todo.md", "line 1\nline 2\nline 3 final\ntemp\n".toByteArray(), tRev)
        clock.advanceMs(5_000L)
        engine.scanAll()
        fs.put(tree, "todo.md", "line 1\nline 2\nline 3 final\n".toByteArray(), clock.nowMs())
        clock.advanceMs(5_000L)
        engine.scanAll()
        clock.advanceMs(300_000L)
        assertNull(engine.nextDelivery(false))

        // At +50 min after PDF save: still waiting on 1-hour cool-down.
        clock.advanceMs(40L * 60_000L)
        engine.scanAll()
        assertNull(engine.nextDelivery(false))

        // At +65 min after PDF save: 1-hour cool-down elapsed -> revision queued and delivered!
        clock.advanceMs(20L * 60_000L)
        engine.scanAll()
        val revSeg = engine.nextDelivery(false)!!
        assertEquals(1, revSeg.events.size)
        assertEquals("proposal.pdf", revSeg.events[0].relPath)
        assertEquals(ActivityVerb.REVISED, revSeg.events[0].verb)
        assertTrue(revSeg.events[0].isRevision)
        // A save back to the last acknowledged document cancels a waiting revision.
        fs.put(tree, "proposal.pdf", "%PDF-1.4 v1".toByteArray(), clock.nowMs())
        clock.advanceMs(5_000L)
        engine.scanAll()
        clock.advanceMs(61L * 60_000L)
        engine.scanAll()
        assertNull(engine.nextDelivery(false))
    }

    @Test
    fun deleteBeforeSendDropsUnsentFileAndEmitsRemovedAfterDelivery() {
        val t0 = 1_795_689_600_000L
        val clock = TestClock(t0 + 10_000L, 0)
        val fs = FakeFolderFs()
        val tree = "content://tree/primary:Drop"
        val engine = FolderEngine(tempLedger(), fs, clock)
        val id = (engine.beginSetup(tree, "Drop", "Drop", "primary:Drop") as BeginSetupResult.Started).folderId
        assertEquals(ConfirmResult.Ok, engine.confirmFolder(id, FolderSpan.TODAY))

        fs.put(tree, "kept.md", "stay".toByteArray(), t0 + 1_000L)
        fs.put(tree, "temp.md", "gone before send".toByteArray(), t0 + 2_000L)
        fs.put(tree, "temp.bin", byteArrayOf(3), t0 + 2_000L)
        engine.scanAll()

        // Delete temp.md before the 5-minute window sends!
        fs.remove(tree, "temp.md")
        fs.remove(tree, "temp.bin")
        clock.advanceMs(300_000L)
        fs.walkOverrides[tree] = WalkOutcome.Unreadable
        assertNull(engine.nextDelivery(false))
        fs.walkOverrides.remove(tree)
        clock.advanceMs(60_000L)

        val seg = engine.nextDelivery(false)!!
        assertEquals(1, seg.events.size)
        assertEquals("kept.md", seg.events[0].relPath)
        engine.recordOutcome(seg, DeliveryOutcome.Delivered)

        // Delete kept.md AFTER delivery -> emits `removed` activity line.
        fs.remove(tree, "kept.md")
        clock.advanceMs(5_000L)
        engine.scanAll()
        clock.advanceMs(310_000L)
        val rem = engine.nextDelivery(false)!!
        assertEquals(1, rem.events.size)
        assertEquals("kept.md", rem.events[0].relPath)
        assertEquals(ActivityVerb.REMOVED, rem.events[0].verb)
        assertEquals(DeliveryMode.METADATA_ONLY, rem.events[0].delivery)
    }

    @Test
    fun incompleteListingNeverMarksMissingFilesAsDeleted() {
        val t0 = 1_795_689_600_000L
        val clock = TestClock(t0 + 10_000L, 0)
        val fs = FakeFolderFs()
        val tree = "content://tree/primary:Shared"
        val engine = FolderEngine(tempLedger(), fs, clock)
        val id = (engine.beginSetup(tree, "Shared", "Shared", "primary:Shared") as BeginSetupResult.Started).folderId
        assertEquals(ConfirmResult.Ok, engine.confirmFolder(id, FolderSpan.TODAY))

        fs.put(tree, "note.md", "top".toByteArray(), t0 + 1_000L)
        engine.scanAll()
        clock.advanceMs(310_000L)
        val first = engine.nextDelivery(false)!!
        engine.recordOutcome(first, DeliveryOutcome.Delivered)

        // Simulate incomplete SAF listing while file looks absent: must NOT emit `removed`.
        fs.walkComplete = false
        fs.unreadableCount = 1L
        fs.remove(tree, "note.md")
        clock.advanceMs(10_000L)
        engine.scanAll()
        clock.advanceMs(310_000L)
        assertNull(engine.nextDelivery(false))
        assertEquals(1L, engine.folderById(id)!!.cantRead)
    }

    @Test
    fun renameEmitsSingleRenamedLineAfterDelivery() {
        val t0 = 1_795_689_600_000L
        val clock = TestClock(t0 + 10_000L, 0)
        val fs = FakeFolderFs()
        val tree = "content://tree/primary:Docs"
        val engine = FolderEngine(tempLedger(), fs, clock)
        val id = (engine.beginSetup(tree, "Docs", "Docs", "primary:Docs") as BeginSetupResult.Started).folderId
        assertEquals(ConfirmResult.Ok, engine.confirmFolder(id, FolderSpan.TODAY))

        fs.put(tree, "draft.pdf", "%PDF-1.4 body".toByteArray(), t0 + 1_000L)
        engine.scanAll()
        clock.advanceMs(310_000L)
        val seg = engine.nextDelivery(false)!!
        engine.recordOutcome(seg, DeliveryOutcome.Delivered)

        fs.rename(tree, "draft.pdf", "proposal-final.pdf", keepDocId = false)
        clock.advanceMs(10_000L)
        engine.scanAll()
        clock.advanceMs(310_000L)

        val ren = engine.nextDelivery(false)!!
        assertEquals(1, ren.events.size)
        assertEquals(ActivityVerb.RENAMED, ren.events[0].verb)
        assertEquals("draft.pdf", ren.events[0].previousPath)
        assertEquals("proposal-final.pdf", ren.events[0].relPath)
    }

    @Test
    fun pauseHoldsWithoutSkippingAndRestartSetupPreservesGrantsWithoutJournalIdentity() {
        val ledger = tempLedger()
        val t0 = 1_795_689_600_000L
        val clock = TestClock(t0 + 10_000L, 0)
        val fs = FakeFolderFs()
        val tree = "content://tree/primary:Notes"
        val engine = FolderEngine(ledger, fs, clock)
        val id = (engine.beginSetup(tree, "Notes", "Notes", "primary:Notes") as BeginSetupResult.Started).folderId
        engine.setFacet(id, FacetOption("work", "Work", "#2f6fdd"))
        assertEquals(ConfirmResult.Ok, engine.confirmFolder(id, FolderSpan.TODAY))

        assertTrue(engine.pauseFolder(id))
        fs.put(tree, "during-pause.md", "written while paused".toByteArray(), t0 + 15_000L)
        clock.advanceMs(310_000L)
        engine.scanAll()
        // Held while paused!
        assertNull(engine.nextDelivery(false))

        assertTrue(engine.resumeFolder(id))
        val seg = engine.nextDelivery(false)!!
        assertEquals(1, seg.events.size)
        assertEquals("during-pause.md", seg.events[0].relPath)

        // Ledger persistence carries ZERO journal identity keys.
        val rawLedger = ledger.readText(Charsets.UTF_8)
        assertFalse(rawLedger.contains("instance_id"))
        assertFalse(rawLedger.contains("fingerprint"))

        // Setup restarts, but previously confirmed material survives the new range choice.
        assertTrue(engine.restartSetup())
        val rec = engine.folderById(id)!!
        assertEquals(FolderMode.SETTING_UP, rec.mode)
        assertTrue(rec.returning)
        assertEquals("work", rec.facet?.name)
        assertEquals(seg.events, engine.nextDelivery(false)!!.events)
        clock.advanceMs(2L * 86_400_000L)
        val reopened = FolderEngine(ledger, fs, clock)
        fs.put(tree, "new.md", "after pairing".toByteArray(), clock.nowMs() - 310_000L)
        reopened.scanAll()
        val waiting = reopened.nextDelivery(false)!!
        assertEquals("during-pause.md", waiting.events.single().relPath)
        reopened.recordOutcome(waiting, DeliveryOutcome.Delivered)
        assertNull(reopened.nextDelivery(false))
        assertEquals(ConfirmResult.Ok, reopened.confirmFolder(id, FolderSpan.TODAY))
        assertEquals("new.md", reopened.nextDelivery(false)!!.events.single().relPath)
    }

    @Test
    fun refusedContentFallsBackToDetailsOnlyAndChunksAtSixtyThreeParts() {
        val t0 = 1_795_689_600_000L
        val clock = TestClock(t0 + 10_000L, 0)
        val fs = FakeFolderFs()
        val tree = "content://tree/primary:Drop"
        val engine = FolderEngine(tempLedger(), fs, clock)
        val id = (engine.beginSetup(tree, "Drop", "Drop", "primary:Drop") as BeginSetupResult.Started).folderId
        assertEquals(ConfirmResult.Ok, engine.confirmFolder(id, FolderSpan.TODAY))

        fs.put(tree, "corrupt.pdf", "not a valid pdf".toByteArray(), t0 + 2_000L)
        engine.scanAll()
        clock.advanceMs(310_000L)

        val seg = engine.nextDelivery(false)!!
        assertEquals(1, seg.contentFiles.size)
        engine.recordOutcome(seg, DeliveryOutcome.AllFilesRefused(listOf("corrupt.pdf")))
        assertEquals(1L, engine.folderById(id)!!.fallbackToDetails)

        val retrySeg = engine.nextDelivery(false)!!
        assertEquals(1, retrySeg.events.size)
        assertEquals(DeliveryMode.METADATA_ONLY, retrySeg.events[0].delivery)
        assertTrue(retrySeg.contentFiles.isEmpty())
        engine.recordOutcome(retrySeg, DeliveryOutcome.Delivered)

        // Chunking check: 65 content files in one 5-minute window split cleanly into 63 + 2.
        val tBatch = clock.nowMs()
        for (i in 1..65) {
            fs.put(tree, "note_$i.md", "body $i".toByteArray(), tBatch)
        }
        clock.advanceMs(5_000L)
        engine.scanAll()
        clock.advanceMs(310_000L)

        val chunk1 = engine.nextDelivery(false)!!
        assertEquals(63, chunk1.contentFiles.size)
        engine.recordOutcome(chunk1, DeliveryOutcome.Delivered)

        val chunk2 = engine.nextDelivery(false)!!
        assertEquals(2, chunk2.contentFiles.size)
        engine.recordOutcome(chunk2, DeliveryOutcome.Delivered)
        assertNull(engine.nextDelivery(false))
    }

    @Test
    fun partialDownloadPresentDoesNotBlockRenameOrDeleteBeforeSend() {
        val t0 = 1_795_689_600_000L
        val clock = TestClock(t0 + 10_000L, 0)
        val fs = FakeFolderFs()
        val tree = "content://tree/primary:Downloads/Drop"
        fs.put(tree, "partial.iso.crdownload", "partial".toByteArray(), t0 + 1_000L)
        fs.put(tree, "standup.vtt", "WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nHi\n".toByteArray(), t0 + 2_000L)

        val engine = FolderEngine(tempLedger(), fs, clock)
        val id = (engine.beginSetup(tree, "Drop", "Drop", "primary:Downloads/Drop") as BeginSetupResult.Started).folderId
        assertEquals(ConfirmResult.Ok, engine.confirmFolder(id, FolderSpan.ALL))
        clock.advanceMs(310_000L)
        val initial = engine.nextDelivery(false)!!
        engine.recordOutcome(initial, DeliveryOutcome.Delivered)

        // With partial.iso.crdownload still present: add ephemeral note, scan, delete before window closes, and rename standup.vtt.
        val t1 = clock.nowMs()
        fs.put(tree, "ephemeral.md", "gone soon".toByteArray(), t1)
        clock.advanceMs(5_000L)
        engine.scanAll()
        fs.remove(tree, "ephemeral.md")
        fs.remove(tree, "standup.vtt")
        fs.put(tree, "weekly-sync.vtt", "WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nHi\n".toByteArray(), t0 + 2_000L)
        clock.advanceMs(5_000L)
        engine.scanAll()
        clock.advanceMs(310_000L)

        val next = engine.nextDelivery(false)!!
        assertEquals(1, next.events.size)
        assertEquals(ActivityVerb.RENAMED, next.events[0].verb)
        assertEquals("standup.vtt", next.events[0].previousPath)
        assertEquals("weekly-sync.vtt", next.events[0].relPath)
        assertTrue(next.contentFiles.isEmpty())
    }

    @Test
    fun wirePrepareAndMultipartConformToProtocolV3Contract() {
        val t0 = 1_795_701_900_000L // 2026-11-27T14:05:00Z
        val ev1 = PendingEvent(
            id = "e1",
            relPath = "notes/plan.md",
            documentId = "d1",
            verb = ActivityVerb.ADDED,
            kind = FileKind.NOTE,
            delivery = DeliveryMode.CONTENT,
            eventMs = t0 + 70_000L,
            observedMs = t0 + 72_000L,
            offsetSeconds = -7 * 3600,
            window = FolderStamp(t0 + 70_000L, -7 * 3600).window(),
            windowEndMs = FolderStamp(t0 + 70_000L, -7 * 3600).windowEndMs(),
            metadata = PlatformMetadata(sizeBytes = 12L, modifiedMs = t0 + 70_000L, extension = "md"),
            contentHash = sha256Hex("hello world\n".toByteArray()),
            notePayload = "hello world\n",
        )
        val ev2 = PendingEvent(
            id = "e2",
            relPath = "archive/installer.apk",
            documentId = "d2",
            verb = ActivityVerb.ADDED,
            kind = FileKind.OTHER,
            delivery = DeliveryMode.METADATA_ONLY,
            eventMs = t0 + 80_000L,
            observedMs = t0 + 82_000L,
            offsetSeconds = -7 * 3600,
            window = ev1.window,
            windowEndMs = ev1.windowEndMs,
            metadata = PlatformMetadata(sizeBytes = 9000L, modifiedMs = t0 + 80_000L, extension = "apk"),
        )
        val delivery = SegmentDelivery(
            folderId = "desktop-b2c3d4e5",
            treeUri = "content://tree/primary:Desktop",
            folderName = "Desktop",
            facet = "work",
            kinds = FolderKinds(),
            window = ev1.window,
            events = listOf(ev1, ev2),
            contentFiles = emptyList(),
        )
        val noteBytes = "hello world\n".toByteArray()
        val part1 = StagedPartPayload(
            eventId = "e1",
            partName = "c_01.md",
            contentHash = sha256Hex(noteBytes),
            mediaType = "text/markdown",
            byteLength = noteBytes.size.toLong(),
            openStream = { ByteArrayInputStream(noteBytes) },
        )
        val prepared = prepareFolderUpload(delivery, listOf(part1))
        assertTrue(prepared.activityFilename.matches(Regex("^folder_[0-9a-f]{64}\\.jsonl$")))
        assertEquals("folder_${sha256Hex(prepared.activityBytes)}.jsonl", prepared.activityFilename)

        val env = parseJson(prepared.envelopeJson) as Map<*, *>
        assertEquals("folder", env["source"])
        val meta = env["meta"] as Map<*, *>
        assertEquals(setOf("folder"), meta.keys)
        val folderMeta = meta["folder"] as Map<*, *>
        assertEquals(1, (folderMeta["v"] as Number).toInt())
        assertEquals("desktop-b2c3d4e5", folderMeta["id"])
        assertEquals("Desktop", folderMeta["name"])
        assertEquals("work", folderMeta["facet"])

        val lines = prepared.activityBytes.toString(Charsets.UTF_8).trimEnd('\n').split('\n')
        assertEquals(2, lines.size)
        val line1 = parseJson(lines[0]) as Map<*, *>
        assertEquals("c_01.md", line1["part"])
        assertEquals("notes", line1["kind_hint"])
        assertEquals(sha256Hex(noteBytes), line1["content_hash"])
        val line2 = parseJson(lines[1]) as Map<*, *>
        assertNull(line2["part"])
        assertNull(line2["kind_hint"])

        // Stream multipart body and confirm exact byte length matches declared contentLength.
        val body = buildFolderMultipartBody(prepared, "testBoundary123")
        val streamed = ByteArrayOutputStream()
        val buf = ByteArray(1024)
        body.use {
            while (true) {
                val n = it.readChunk(buf, 0, buf.size)
                if (n < 0) break
                streamed.write(buf, 0, n)
            }
        }
        assertEquals(body.contentLength, streamed.size().toLong())
        val rawText = streamed.toString("UTF-8")
        assertTrue(rawText.contains("name=\"envelope\""))
        assertTrue(rawText.contains("filename=\"${prepared.activityFilename}\""))
        assertTrue(rawText.contains("filename=\"c_01.md\""))

        // Verify answers table parser.
        assertEquals(FolderUploadReply.AuthStop, classifyFolderIngestResponse(403, "{}"))
        assertEquals(
            FolderUploadReply.Outcome(DeliveryOutcome.Delivered),
            classifyFolderIngestResponse(200, """{"status":"duplicate"}"""),
        )
        assertEquals(
            FolderUploadReply.Outcome(DeliveryOutcome.DeliveredWithRefusedContent(listOf("bad.pdf"))),
            classifyFolderIngestResponse(200, """{"status":"ok","refused_files":[{"path":"bad.pdf","part":"c_01.pdf","reason_code":"folder_file_invalid"}]}"""),
        )
        assertEquals(
            FolderUploadReply.Outcome(DeliveryOutcome.AllFilesRefused(listOf("bad.pdf"))),
            classifyFolderIngestResponse(400, """{"reason_code":"folder_file_invalid","refused_files":[{"path":"bad.pdf"}]}"""),
        )
        assertEquals(
            FolderUploadReply.Outcome(DeliveryOutcome.Hold),
            classifyFolderIngestResponse(400, """{"reason_code":"folder_ahead"}"""),
        )
        assertEquals(
            FolderUploadReply.Outcome(DeliveryOutcome.InvalidPermanent),
            classifyFolderIngestResponse(400, """{"reason_code":"folder_invalid"}"""),
        )
        assertEquals(
            FolderUploadReply.Outcome(DeliveryOutcome.Hold),
            classifyFolderIngestResponse(503, """{"reason_code":"journal_read_failed"}"""),
        )
    }

    @Test
    fun wireDiscoveryEndpointsSendProtocolV3Header() {
        val seenHeaders = ArrayList<Pair<String, Map<String, String>>>()
        val fakeClient = object : PlHttpClient {
            override fun request(
                method: String,
                path: String,
                headers: Map<String, String>,
                body: ByteArray?,
                maxResponseBytes: Int,
            ): HttpResponse {
                seenHeaders.add(path to headers)
                return when (path) {
                    "/app/devices/ingest/folder/supported" -> HttpResponse(
                        200,
                        emptyMap(),
                        """{"version":1,"ruleset_version":8,"window_seconds":300,"max_file_bytes":67108864,"max_note_bytes":4194304,"max_image_pixels":67108864,"document_revision_cooldown_seconds":3600,"kinds":{"notes":{"extensions":["md","txt"]},"documents":{"extensions":["pdf","docx","md","txt"],"thresholds":{"structured_prose_min_words":1500,"structured_prose_min_heading_levels":2,"long_prose_min_words":5000}},"transcripts":{"extensions":["vtt","srt"]},"screenshots":{"extensions":["png","jpg"],"convert_to_png_on_device":["heic"]},"photos":{"extensions":["png","jpg"],"convert_to_png_on_device":["heic"]}}}""".toByteArray(),
                    )
                    "/app/devices/ingest/folder/facets" -> HttpResponse(
                        200,
                        emptyMap(),
                        """{"facets":[{"name":"work","title":"Work","color":"#2f6fdd"}]}""".toByteArray(),
                    )
                    "/app/devices/ingest/folder/estimate" -> HttpResponse(
                        200,
                        emptyMap(),
                        """{"segments":4,"days":2,"thoughts":3,"already_in_journal":1,"backfill_paused":false}""".toByteArray(),
                    )
                    else -> HttpResponse(404, emptyMap(), ByteArray(0))
                }
            }
        }
        val sup = fetchFolderSupported(fakeClient) as SupportedFetchResult.Loaded
        assertTrue("docx" in sup.supported.documentExtensions)
        val facets = fetchFolderFacets(fakeClient) as FacetsFetchResult.Loaded
        assertEquals(listOf(FacetOption("work", "Work", "#2f6fdd")), facets.facets)
        val est = fetchFolderEstimate(fakeClient, "work-1234", emptyList()) as EstimateFetchResult.Loaded
        assertEquals(4L, est.estimate.segments)
        assertEquals(2L, est.estimate.days)
        assertEquals(3L, est.estimate.thoughts)
        assertEquals(1L, est.estimate.alreadyInJournal)
        assertTrue(est.estimate.fromJournal)
        assertTrue(seenHeaders.all { (_, h) -> h["X-Solstone-Protocol-Version"] == "3" })
    }
}
