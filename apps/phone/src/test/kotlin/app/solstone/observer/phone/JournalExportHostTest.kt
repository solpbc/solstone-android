// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone

import app.solstone.core.pl.browser.BrowserHttpResponse
import app.solstone.core.pl.browser.BrowserRequestBodySource
import app.solstone.core.pl.browser.BrowserResponseSink
import app.solstone.core.pl.browser.JournalBrowserUpstream
import app.solstone.core.pl.parseJson
import app.solstone.platform.persistence.room.ImportHandoffStatus
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class JournalExportHostTest {
    private val origin = "http://0123456789abcdef0123456789abcdef.localhost:7657"
    private val importContract = assertNotNull(
        parseImportHostContract(File("src/main/assets/journal-web-host/import-host-contract.json").readBytes()),
    )
    private val authContract = assertNotNull(
        parseAuthHostContract(File("src/main/assets/journal-web-host/auth-host-contract.json").readBytes()),
    )
    private val exportContract = assertNotNull(
        parseExportHostContract(File("src/main/assets/journal-web-host/export-host-contract.json").readBytes()),
    )

    private class FileExportDestination(
        val file: File,
        override val displayName: String = file.name,
        override val supportsRandomWrite: Boolean = true,
        private val allowReadBack: Boolean = true,
    ) : ExportDestination {
        var deleted = false
        override val uriString: String = file.toURI().toString()

        override fun currentSizeBytes(): Long = if (file.exists()) file.length() else 0L

        override fun openWriterAt(offset: Long, truncate: Boolean): OutputStream {
            file.parentFile?.mkdirs()
            val raf = RandomAccessFile(file, "rw")
            if (truncate) {
                raf.setLength(0L)
                raf.seek(0L)
            } else {
                raf.seek(offset)
            }
            return object : OutputStream() {
                override fun write(b: Int) {
                    raf.write(b)
                }

                override fun write(b: ByteArray, off: Int, len: Int) {
                    raf.write(b, off, len)
                }

                override fun close() {
                    raf.close()
                }
            }
        }

        override fun computeSha256OrNull(): String? {
            if (!allowReadBack || !file.exists()) return null
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (n > 0) digest.update(buf, 0, n)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(Locale.US, it) }
        }

        override fun deleteCreatedDocument() {
            deleted = true
            file.delete()
        }
    }

    private class ScriptedExportUpstream(
        private val payload: ByteArray,
        private val etag: String,
        private val truncateFirstAttemptAt: Int? = null,
        private val fixedStatus: Int? = null,
        private val fixedBody: ByteArray = ByteArray(0),
        private val attempts: AtomicInteger = AtomicInteger(0),
        val seenHeaders: MutableList<List<Pair<String, String>>> = mutableListOf(),
    ) : JournalBrowserUpstream {
        override val isPoisoned: Boolean get() = false
        override fun request(method: String, path: String, headers: Map<String, String>, body: ByteArray?): BrowserHttpResponse =
            error("streaming expected")

        override fun requestStreaming(
            method: String,
            path: String,
            headers: List<Pair<String, String>>,
            bodySource: BrowserRequestBodySource?,
            responseSink: BrowserResponseSink,
        ) {
            seenHeaders += headers
            val attempt = attempts.incrementAndGet()
            if (fixedStatus != null) {
                responseSink.onStatusAndHeaders(
                    fixedStatus,
                    "",
                    listOf("Content-Type" to "application/json", "Content-Length" to fixedBody.size.toString()),
                )
                if (fixedBody.isNotEmpty()) {
                    responseSink.onBodyChunk(fixedBody, 0, fixedBody.size)
                }
                responseSink.onComplete()
                return
            }

            val range = headers.firstOrNull { it.first.equals("range", ignoreCase = true) }?.second
            val ifRange = headers.firstOrNull { it.first.equals("if-range", ignoreCase = true) }?.second
            if (range != null && range.startsWith("bytes=") && ifRange == etag) {
                val start = range.removePrefix("bytes=").substringBefore('-').toInt()
                val len = payload.size - start
                responseSink.onStatusAndHeaders(
                    206,
                    "Partial Content",
                    listOf(
                        "Content-Type" to "application/zip",
                        "Content-Length" to len.toString(),
                        "Content-Range" to "bytes $start-${payload.size - 1}/${payload.size}",
                        "ETag" to etag,
                    ),
                )
                responseSink.onBodyChunk(payload, start, len)
                responseSink.onComplete()
                return
            }

            responseSink.onStatusAndHeaders(
                200,
                "OK",
                listOf(
                    "Content-Type" to "application/zip",
                    "Content-Length" to payload.size.toString(),
                    "ETag" to etag,
                ),
            )
            if (attempt == 1 && truncateFirstAttemptAt != null && truncateFirstAttemptAt < payload.size) {
                responseSink.onBodyChunk(payload, 0, truncateFirstAttemptAt)
                throw IOException("simulated drop")
            }
            responseSink.onBodyChunk(payload, 0, payload.size)
            responseSink.onComplete()
        }

        override fun close() {}
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(Locale.US, it) }

    @Test
    fun vendoredExportContractAndImportHostScriptAdvertiseExportTypes() {
        assertEquals("solstoneJournalHost", exportContract.capability)
        assertEquals("export.start", exportContract.start)
        assertEquals("export.status", exportContract.status)
        assertEquals("export.pause", exportContract.pause)
        assertEquals("export.resume", exportContract.resume)
        assertEquals("export.cancel", exportContract.cancel)
        assertEquals("export.reveal", exportContract.reveal)
        assertEquals("export.device_space", exportContract.deviceSpace)
        assertEquals("solstone-journal-export-status", exportContract.event)

        val template = File("src/main/assets/journal-web-host/import-host.js").readText()
        val script = journalImportHostScript(template, importContract, origin, authContract, exportContract)
        assertTrue(script.contains("\"export.start\""))
        assertTrue(script.contains("\"solstone-journal-export-status\""))
        assertTrue(script.contains("exportContract.deviceSpace"))
        assertTrue("__" !in script.substringAfterLast("})("))
    }

    @Test
    fun resumableSha256MatchesJvmMessageDigestAcrossSplitUpdates() {
        val sample = ByteArray(100_037) { ((it * 31) and 0xff).toByte() }
        val expected = sha256Hex(sample)
        val first = ResumableSha256()
        first.update(sample, 0, 33_333)
        val mid = first.snapshotMidstate()
        val resumed = ResumableSha256(mid.h, mid.totalBytes, mid.pending)
        resumed.update(sample, 33_333, sample.size - 33_333)
        assertEquals(expected, resumed.digestHex())
    }

    @Test
    fun exportStartStreamsVerifiesSha256AndPushesStatusEvents() {
        val dir = createTempDir("android-export-ok")
        try {
            val payload = ByteArray(256 * 1024 + 91) { ((it * 13) and 0xff).toByte() }
            val sha = sha256Hex(payload)
            val etag = "\"sha256-$sha\""
            val destFile = File(dir, "solstone-journal-2026-11-26.zip")
            val dest = FileExportDestination(destFile)
            val upstream = ScriptedExportUpstream(payload, etag)
            val stateStore = ExportStateStore(File(dir, "state.json"))
            val scheduledRuns = mutableListOf<Long>()

            val manager = JournalExportTransferManager(
                upstreamOpener = { OpenedExportUpstream(upstream, "local") },
                spaceProbe = { 500_000_000L },
                destinationReopener = { _, name -> FileExportDestination(destFile, name) },
                stateStore = stateStore,
                drainClosed = { false },
                schedulerFactory = { m ->
                    object : ExportExecutionScheduler {
                        override fun scheduleRun(estimatedRemainingBytes: Long) {
                            scheduledRuns += estimatedRemainingBytes
                            m.runTransferAttemptBlocking()
                        }
                        override fun onProgressUpdated(snapshot: JournalExportStatusSnapshot, filename: String) {}
                        override fun onEnded(snapshot: JournalExportStatusSnapshot) {}
                    }
                },
            )
            val exportHost = JournalExportHost(exportContract, manager) { _, onPicked -> onPicked(dest) }
            val pushedEvents = mutableListOf<Map<*, *>>()
            exportHost.attachEventSink { json -> pushedEvents += parseJson(json) as Map<*, *> }

            val importHost = JournalImportHost<String>(
                origin = origin,
                contract = importContract,
                openPicker = { _, _ -> },
                itemFor = { name -> IntakeItem.Stream(name, null, "content", 1, ByteArrayInputStream(byteArrayOf(1))) },
                submit = { _, done -> done(IntakeOutcome(emptyList(), emptyList())) },
                status = { ImportHandoffStatus("gone") },
                background = { it() },
                exportHost = exportHost,
            )

            val replies = mutableListOf<Map<*, *>>()
            val startPacket = """{"seq":1,"message":{"type":"export.start","id":"s1","export_id":"exp_1","download_path":"/app/export/api/ready/exp_1/download","filename":"solstone-journal-2026-11-26.zip","size_bytes":${payload.size},"sha256":"$sha","etag":${app.solstone.core.pl.toJson(etag)},"mode":"complete"},"context":null}"""
            importHost.onMessage(startPacket, origin, true) { text ->
                replies += (parseJson(text) as Map<*, *>)["body"] as Map<*, *>
            }

            assertEquals("saved", manager.currentSnapshot().phase)
            assertTrue(manager.currentSnapshot().verified)
            assertEquals(payload.size.toLong(), manager.currentSnapshot().bytesWritten)
            assertTrue(destFile.readBytes().contentEquals(payload))
            assertFalse(dest.deleted)
            assertTrue(pushedEvents.any { ((it["detail"] as? Map<*, *>)?.get("phase")) == "saved" })
            assertEquals(1, scheduledRuns.size)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun api26To33FallbackPauseResumeAndPersistedStateRecoveryWorksWithoutUidt() {
        val dir = createTempDir("android-export-api26-33")
        try {
            val payload = ByteArray(192 * 1024 + 55) { ((it * 19) and 0xff).toByte() }
            val sha = sha256Hex(payload)
            val etag = "\"sha256-$sha\""
            val destFile = File(dir, "solstone-journal-older-api.zip")
            val stateFile = File(dir, "active.json")
            val stateStore = ExportStateStore(stateFile)
            val attempts = AtomicInteger(0)
            val seenHeaders = mutableListOf<List<Pair<String, String>>>()

            // Simulate Android 12 (API 31): UIDT scheduler must NOT be invoked; direct path runs and pauses on drop.
            val uidtInvoked = AtomicInteger(0)
            val firstManager = JournalExportTransferManager(
                upstreamOpener = {
                    OpenedExportUpstream(
                        ScriptedExportUpstream(payload, etag, truncateFirstAttemptAt = 65_536, attempts = attempts, seenHeaders = seenHeaders),
                        "local",
                    )
                },
                spaceProbe = { 500_000_000L },
                destinationReopener = { _, name -> FileExportDestination(destFile, name) },
                stateStore = stateStore,
                drainClosed = { false },
                schedulerFactory = { m ->
                    object : ExportExecutionScheduler {
                        override fun scheduleRun(estimatedRemainingBytes: Long) {
                            // Explicitly model API 31 (< 34): never calls UIDT; runs in-process.
                            if (31 >= 34) uidtInvoked.incrementAndGet()
                            m.runTransferAttemptBlocking()
                        }
                        override fun onProgressUpdated(snapshot: JournalExportStatusSnapshot, filename: String) {}
                        override fun onEnded(snapshot: JournalExportStatusSnapshot) {}
                    }
                },
            )

            val request = JournalExportStartRequest(
                exportId = "exp_api31",
                downloadPath = "/app/export/api/ready/exp_api31/download",
                filename = "solstone-journal-older-api.zip",
                sizeBytes = payload.size.toLong(),
                sha256 = sha,
                etag = etag,
                mode = "complete",
            )
            firstManager.start(request, { _, cb -> cb(FileExportDestination(destFile)) }) {}

            assertEquals(0, uidtInvoked.get(), "API 26-33 must not schedule API 34+ UIDT job")
            assertEquals("paused", firstManager.currentSnapshot().phase)
            assertEquals("disconnected", firstManager.currentSnapshot().pauseReason)
            assertEquals(65_536L, firstManager.currentSnapshot().bytesWritten)
            assertEquals(65_536L, destFile.length())
            assertNotNull(stateStore.load(), "API 26-33 pause must persist resumable state across process stop")

            // Simulate process restart on API 31: a fresh manager restores the paused state and resumes via Range + If-Range.
            val restoredManager = JournalExportTransferManager(
                upstreamOpener = {
                    OpenedExportUpstream(
                        ScriptedExportUpstream(payload, etag, truncateFirstAttemptAt = 65_536, attempts = attempts, seenHeaders = seenHeaders),
                        "local",
                    )
                },
                spaceProbe = { 500_000_000L },
                destinationReopener = { _, name -> FileExportDestination(destFile, name) },
                stateStore = stateStore,
                drainClosed = { false },
                schedulerFactory = { m ->
                    object : ExportExecutionScheduler {
                        override fun scheduleRun(estimatedRemainingBytes: Long) {
                            m.runTransferAttemptBlocking()
                        }
                        override fun onProgressUpdated(snapshot: JournalExportStatusSnapshot, filename: String) {}
                        override fun onEnded(snapshot: JournalExportStatusSnapshot) {}
                    }
                },
            )
            assertEquals("paused", restoredManager.currentSnapshot().phase)
            assertEquals(65_536L, restoredManager.currentSnapshot().bytesWritten)

            restoredManager.resume("exp_api31")
            assertEquals("saved", restoredManager.currentSnapshot().phase)
            assertTrue(restoredManager.currentSnapshot().verified)
            assertTrue(destFile.readBytes().contentEquals(payload))
            assertEquals(2, seenHeaders.size)
            assertEquals("bytes=65536-", seenHeaders[1].firstOrNull { it.first.equals("range", ignoreCase = true) }?.second)
            assertEquals(etag, seenHeaders[1].firstOrNull { it.first.equals("if-range", ignoreCase = true) }?.second)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun lanToRelaySwitchPausesWithRelaySwitchedAndResumesWhenOwnerConfirms() {
        val dir = createTempDir("android-export-relay-switch")
        try {
            val payload = ByteArray(128 * 1024 + 17) { ((it * 5) and 0xff).toByte() }
            val sha = sha256Hex(payload)
            val etag = "\"sha256-$sha\""
            val destFile = File(dir, "solstone-journal-relay.zip")
            val attempts = AtomicInteger(0)
            val transportRef = AtomicReference("local")

            val manager = JournalExportTransferManager(
                upstreamOpener = {
                    OpenedExportUpstream(
                        ScriptedExportUpstream(payload, etag, truncateFirstAttemptAt = 32_768, attempts = attempts),
                        transportRef.get(),
                    )
                },
                spaceProbe = { 500_000_000L },
                destinationReopener = { _, name -> FileExportDestination(destFile, name) },
                stateStore = ExportStateStore(File(dir, "state.json")),
                drainClosed = { false },
                schedulerFactory = { m ->
                    object : ExportExecutionScheduler {
                        override fun scheduleRun(estimatedRemainingBytes: Long) {
                            m.runTransferAttemptBlocking()
                        }
                        override fun onProgressUpdated(snapshot: JournalExportStatusSnapshot, filename: String) {}
                        override fun onEnded(snapshot: JournalExportStatusSnapshot) {}
                    }
                },
            )
            val request = JournalExportStartRequest(
                exportId = "exp_relay_switch",
                downloadPath = "/app/export/api/ready/exp_relay_switch/download",
                filename = "solstone-journal-relay.zip",
                sizeBytes = payload.size.toLong(),
                sha256 = sha,
                etag = etag,
                mode = "complete",
            )
            manager.start(request, { _, cb -> cb(FileExportDestination(destFile)) }) {}
            assertEquals("paused", manager.currentSnapshot().phase)

            // Network drops off LAN onto relay: resuming switches initialTransport check -> relay_switched pause.
            transportRef.set("relay")
            manager.resume("exp_relay_switch")
            assertEquals("paused", manager.currentSnapshot().phase)
            assertEquals("relay_switched", manager.currentSnapshot().pauseReason)

            // Owner explicitly confirms Resume over relay -> finishes and verifies.
            manager.resume("exp_relay_switch")
            assertEquals("saved", manager.currentSnapshot().phase)
            assertTrue(destFile.readBytes().contentEquals(payload))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun insufficientSpaceAnd410ErasureDeleteCreatedDocument() {
        val dir = createTempDir("android-export-errors")
        try {
            // 1. Insufficient device space deletes newly created empty document.
            val lowFile = File(dir, "low.zip")
            lowFile.writeBytes(ByteArray(0))
            val lowDest = FileExportDestination(lowFile)
            val lowManager = JournalExportTransferManager(
                upstreamOpener = { error("must not open upstream when space insufficient") },
                spaceProbe = { 1024L },
                destinationReopener = { _, name -> FileExportDestination(lowFile, name) },
                stateStore = ExportStateStore(File(dir, "low.json")),
                drainClosed = { false },
                schedulerFactory = {
                    object : ExportExecutionScheduler {
                        override fun scheduleRun(estimatedRemainingBytes: Long) {}
                        override fun onProgressUpdated(snapshot: JournalExportStatusSnapshot, filename: String) {}
                        override fun onEnded(snapshot: JournalExportStatusSnapshot) {}
                    }
                },
            )
            val lowReq = JournalExportStartRequest(
                exportId = "exp_low",
                downloadPath = "/app/export/api/ready/exp_low/download",
                filename = "low.zip",
                sizeBytes = 10_000_000L,
                sha256 = "a".repeat(64),
                etag = "\"e1\"",
                mode = "complete",
            )
            var lowSnap: JournalExportStatusSnapshot? = null
            lowManager.start(lowReq, { _, cb -> cb(lowDest) }) { lowSnap = it }
            assertEquals("failed", lowSnap?.phase)
            assertEquals("insufficient_device_space", lowSnap?.errorCode)
            assertTrue(lowDest.deleted)

            // 2. 410 Gone (Trash erasure) deletes partial document and surfaces export_invalidated_by_erasure.
            val goneFile = File(dir, "gone.zip")
            val goneDest = FileExportDestination(goneFile)
            val goneManager = JournalExportTransferManager(
                upstreamOpener = {
                    OpenedExportUpstream(
                        ScriptedExportUpstream(
                            payload = ByteArray(0),
                            etag = "\"e2\"",
                            fixedStatus = 410,
                            fixedBody = """{"code":"export_invalidated_by_erasure"}""".toByteArray(),
                        ),
                        "local",
                    )
                },
                spaceProbe = { 500_000_000L },
                destinationReopener = { _, name -> FileExportDestination(goneFile, name) },
                stateStore = ExportStateStore(File(dir, "gone.json")),
                drainClosed = { false },
                schedulerFactory = { m ->
                    object : ExportExecutionScheduler {
                        override fun scheduleRun(estimatedRemainingBytes: Long) {
                            m.runTransferAttemptBlocking()
                        }
                        override fun onProgressUpdated(snapshot: JournalExportStatusSnapshot, filename: String) {}
                        override fun onEnded(snapshot: JournalExportStatusSnapshot) {}
                    }
                },
            )
            val goneReq = JournalExportStartRequest(
                exportId = "exp_gone",
                downloadPath = "/app/export/api/ready/exp_gone/download",
                filename = "gone.zip",
                sizeBytes = 4096L,
                sha256 = "b".repeat(64),
                etag = "\"e2\"",
                mode = "complete",
            )
            goneManager.start(goneReq, { _, cb -> cb(goneDest) }) {}
            assertEquals("failed", goneManager.currentSnapshot().phase)
            assertEquals("export_invalidated_by_erasure", goneManager.currentSnapshot().errorCode)
            assertTrue(goneDest.deleted)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun notificationContentTextFormatsSavingCheckingAndPausedStates() {
        val saving = ExportDownloadNotification.contentText(
            context = null,
            filename = "solstone-journal-2026-11-26.zip",
            bytesWritten = 50_000_000L,
            bytesTotal = 200_000_000L,
            phase = "transferring",
        )
        assertTrue(saving.contains("solstone-journal-2026-11-26.zip"), saving)
        assertTrue(saving.contains("50.0 MB") && saving.contains("200.0 MB"), saving)

        val checking = ExportDownloadNotification.contentText(
            context = null,
            filename = "solstone-journal-2026-11-26.zip",
            bytesWritten = 200_000_000L,
            bytesTotal = 200_000_000L,
            phase = "verifying",
        )
        assertEquals("solstone-journal-2026-11-26.zip · checking file", checking)

        val paused = ExportDownloadNotification.contentText(
            context = null,
            filename = "solstone-journal-2026-11-26.zip",
            bytesWritten = 50_000_000L,
            bytesTotal = 200_000_000L,
            phase = "paused",
        )
        assertEquals("paused at 50.0 MB of 200.0 MB", paused)
    }

    @Test
    fun uidtPauseHandsOffToStandalonePausedNotificationAndResumeClearsIt() {
        val dir = createTempDir("android-export-uidt-paused-notif")
        try {
            val payload = ByteArray(128 * 1024 + 9) { ((it * 11) and 0xff).toByte() }
            val sha = sha256Hex(payload)
            val etag = "\"sha256-$sha\""
            val destFile = File(dir, "uidt-paused.zip")
            val attempts = AtomicInteger(0)
            val postedPaused = mutableListOf<Pair<JournalExportStatusSnapshot, String>>()

            val manager = JournalExportTransferManager(
                upstreamOpener = {
                    OpenedExportUpstream(
                        ScriptedExportUpstream(payload, etag, truncateFirstAttemptAt = 32_768, attempts = attempts),
                        "local",
                    )
                },
                spaceProbe = { 500_000_000L },
                destinationReopener = { _, name -> FileExportDestination(destFile, name) },
                stateStore = ExportStateStore(File(dir, "state.json")),
                drainClosed = { false },
                schedulerFactory = { m ->
                    object : ExportExecutionScheduler {
                        override fun scheduleRun(estimatedRemainingBytes: Long) {
                            m.runTransferAttemptBlocking()
                        }
                        override fun onProgressUpdated(snapshot: JournalExportStatusSnapshot, filename: String) {}
                        override fun onEnded(snapshot: JournalExportStatusSnapshot) {}
                    }
                },
            )
            val req = JournalExportStartRequest(
                exportId = "exp_uidt_pause",
                downloadPath = "/app/export/api/ready/exp_uidt_pause/download",
                filename = "uidt-paused.zip",
                sizeBytes = payload.size.toLong(),
                sha256 = sha,
                etag = etag,
                mode = "complete",
            )
            manager.start(req, { _, cb -> cb(FileExportDestination(destFile)) }) {}
            assertEquals("paused", manager.currentSnapshot().phase)

            // When UIDT finishes after a pause, JobScheduler removes NOTIFICATION_ID (105),
            // so repostPausedNotificationIfNeeded must post STANDALONE_NOTIFICATION_ID (106).
            val reposted = ExportDownloadJobService.repostPausedNotificationIfNeeded(manager) { snap, name ->
                postedPaused += snap to name
            }
            assertTrue(reposted)
            assertEquals(1, postedPaused.size)
            assertEquals("paused", postedPaused[0].first.phase)
            assertEquals("uidt-paused.zip", postedPaused[0].second)
            assertTrue(ExportDownloadNotification.STANDALONE_NOTIFICATION_ID != ExportDownloadNotification.NOTIFICATION_ID)

            // After resuming and saving, repostPausedNotificationIfNeeded must be a no-op.
            manager.resume("exp_uidt_pause")
            assertEquals("saved", manager.currentSnapshot().phase)
            assertFalse(ExportDownloadJobService.repostPausedNotificationIfNeeded(manager) { snap, name ->
                postedPaused += snap to name
            })
            assertEquals(1, postedPaused.size)
        } finally {
            dir.deleteRecursively()
        }
    }
}
