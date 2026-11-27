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
        private val availableSpaceBytes: Long? = 500_000_000L,
        private val failOnFlushMessage: String? = null,
        private val failOnClose: Boolean = false,
        private val truncateStoredOnCloseTo: Long? = null,
    ) : ExportDestination {
        var deleted = false
        var sizeReads = 0
        var hashReads = 0
        var writerOpens = 0
        var closeCalls = 0
        override val uriString: String = file.toURI().toString()

        override fun currentSizeBytes(): Long {
            sizeReads += 1
            return if (file.exists()) file.length() else 0L
        }

        override fun availableSpaceBytesOrNull(): Long? = availableSpaceBytes

        override fun openWriterAt(offset: Long, truncate: Boolean): OutputStream {
            writerOpens += 1
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

                override fun flush() {
                    if (failOnFlushMessage != null) {
                        throw IOException(failOnFlushMessage)
                    }
                }

                override fun close() {
                    closeCalls += 1
                    try {
                        truncateStoredOnCloseTo?.let { raf.setLength(it) }
                    } finally {
                        raf.close()
                    }
                    if (failOnClose) {
                        throw IOException("simulated provider commit failure on close")
                    }
                }
            }
        }

        override fun computeSha256AndSizeOrNull(): Pair<String, Long>? {
            hashReads += 1
            if (!allowReadBack || !file.exists()) return null
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            file.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (n > 0) {
                        digest.update(buf, 0, n)
                        total += n.toLong()
                    }
                }
            }
            return digest.digest().joinToString("") { "%02x".format(Locale.US, it) } to total
        }

        override fun deleteCreatedDocument() {
            deleted = true
            file.delete()
        }
    }

    private class ScriptedExportUpstream(
        private val payload: ByteArray,
        private val etag: String,
        private val response206Etag: String = etag,
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
                        "ETag" to response206Etag,
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
            // 1. Insufficient destination volume space deletes newly created empty document.
            val lowFile = File(dir, "low.zip")
            lowFile.writeBytes(ByteArray(0))
            val lowDest = FileExportDestination(lowFile, availableSpaceBytes = 1024L)
            val lowManager = JournalExportTransferManager(
                upstreamOpener = { error("must not open upstream when space insufficient") },
                spaceProbe = { 500_000_000L },
                destinationReopener = { _, name -> FileExportDestination(lowFile, name, availableSpaceBytes = 1024L) },
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
            assertEquals("error", lowSnap?.phase)
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
            assertEquals("error", goneManager.currentSnapshot().phase)
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

    @Test
    fun failingCloseOnWriteOnlyProviderFailsWithDestWriteFailedInsteadOfClaimingSaved() {
        val dir = createTempDir("android-export-close-fail")
        try {
            val payload = ByteArray(128 * 1024 + 33) { ((it * 23) and 0xff).toByte() }
            val sha = sha256Hex(payload)
            val etag = "\"sha256-$sha\""
            val destFile = File(dir, "broken-provider.zip")
            // Simulates a write-only SAF provider whose close() fails and drops the file to 0 bytes.
            val dest = FileExportDestination(
                file = destFile,
                allowReadBack = false,
                failOnClose = true,
                truncateStoredOnCloseTo = 0L,
            )
            val manager = JournalExportTransferManager(
                upstreamOpener = { OpenedExportUpstream(ScriptedExportUpstream(payload, etag), "local") },
                spaceProbe = { 500_000_000L },
                destinationReopener = { _, _ -> dest },
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
                exportId = "exp_close_fail",
                downloadPath = "/app/export/api/ready/exp_close_fail/download",
                filename = "broken-provider.zip",
                sizeBytes = payload.size.toLong(),
                sha256 = sha,
                etag = etag,
                mode = "full",
            )
            manager.start(req, { _, cb -> cb(dest) }) {}
            assertEquals("error", manager.currentSnapshot().phase)
            assertEquals("dest_write_failed", manager.currentSnapshot().errorCode)
            assertFalse(manager.currentSnapshot().verified)
            assertEquals(1, dest.closeCalls)
            assertTrue(dest.deleted)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun failingFlushAlwaysClosesWriterAndPreservesOriginalFlushFailure() {
        val dir = createTempDir("android-export-flush-fail")
        try {
            val payload = ByteArray(64 * 1024 + 17) { ((it * 29) and 0xff).toByte() }
            val sha = sha256Hex(payload)
            val etag = "\"sha256-$sha\""
            // Flush throws ENOSPC AND close also throws a generic IOException:
            // close() must still be called once AND the original ENOSPC error must be preserved.
            val destFile = File(dir, "flush-enospc.zip")
            val dest = FileExportDestination(
                file = destFile,
                allowReadBack = false,
                failOnFlushMessage = "write failed: ENOSPC (No space left on device)",
                failOnClose = true,
            )
            val manager = JournalExportTransferManager(
                upstreamOpener = { OpenedExportUpstream(ScriptedExportUpstream(payload, etag), "local") },
                spaceProbe = { 500_000_000L },
                destinationReopener = { _, _ -> dest },
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
                exportId = "exp_flush_fail",
                downloadPath = "/app/export/api/ready/exp_flush_fail/download",
                filename = "flush-enospc.zip",
                sizeBytes = payload.size.toLong(),
                sha256 = sha,
                etag = etag,
                mode = "full",
            )
            manager.start(req, { _, cb -> cb(dest) }) {}
            assertEquals("error", manager.currentSnapshot().phase)
            assertEquals("insufficient_device_space", manager.currentSnapshot().errorCode)
            assertFalse(manager.currentSnapshot().verified)
            assertEquals(1, dest.closeCalls, "writer.close() must always run even when flush() throws")
            assertTrue(dest.deleted)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun writeOnlyProviderCleanHandoffReportsHandedOffUnverifiedAndTruncatedReadableFailsChecksum() {
        val dir = createTempDir("android-export-honest-verify")
        try {
            val payload = ByteArray(96 * 1024 + 19) { ((it * 29) and 0xff).toByte() }
            val sha = sha256Hex(payload)
            val etag = "\"sha256-$sha\""

            // 1. Write-only provider that flushes and closes cleanly reports handed_off with verified=false.
            val handoffFile = File(dir, "write-only.zip")
            val handoffDest = FileExportDestination(handoffFile, allowReadBack = false)
            val handoffManager = JournalExportTransferManager(
                upstreamOpener = { OpenedExportUpstream(ScriptedExportUpstream(payload, etag), "local") },
                spaceProbe = { 500_000_000L },
                destinationReopener = { _, _ -> handoffDest },
                stateStore = ExportStateStore(File(dir, "handoff.json")),
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
            val handoffReq = JournalExportStartRequest(
                exportId = "exp_handoff",
                downloadPath = "/app/export/api/ready/exp_handoff/download",
                filename = "write-only.zip",
                sizeBytes = payload.size.toLong(),
                sha256 = sha,
                etag = etag,
                mode = "full",
            )
            handoffManager.start(handoffReq, { _, cb -> cb(handoffDest) }) {}
            assertEquals("handed_off", handoffManager.currentSnapshot().phase)
            assertFalse(handoffManager.currentSnapshot().verified)
            assertFalse(handoffDest.deleted)

            // 2. Readable provider that silently truncated stored bytes fails checksum_mismatch and deletes doc.
            val truncFile = File(dir, "truncated.zip")
            val truncDest = FileExportDestination(truncFile, allowReadBack = true, truncateStoredOnCloseTo = 0L)
            val truncManager = JournalExportTransferManager(
                upstreamOpener = { OpenedExportUpstream(ScriptedExportUpstream(payload, etag), "local") },
                spaceProbe = { 500_000_000L },
                destinationReopener = { _, _ -> truncDest },
                stateStore = ExportStateStore(File(dir, "trunc.json")),
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
            val truncReq = JournalExportStartRequest(
                exportId = "exp_trunc",
                downloadPath = "/app/export/api/ready/exp_trunc/download",
                filename = "truncated.zip",
                sizeBytes = payload.size.toLong(),
                sha256 = sha,
                etag = etag,
                mode = "full",
            )
            truncManager.start(truncReq, { _, cb -> cb(truncDest) }) {}
            assertEquals("error", truncManager.currentSnapshot().phase)
            assertEquals("checksum_mismatch", truncManager.currentSnapshot().errorCode)
            assertFalse(truncManager.currentSnapshot().verified)
            assertTrue(truncDest.deleted)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun fullInternalStorageDoesNotBlockExternalDestinationWithCapacityOrUnknownCapacity() {
        val dir = createTempDir("android-export-external-space")
        try {
            val payload = ByteArray(64 * 1024 + 7) { ((it * 17) and 0xff).toByte() }
            val sha = sha256Hex(payload)
            val etag = "\"sha256-$sha\""
            // Internal storage is nearly full (1 KiB), but the picked external/cloud destination
            // either reports plenty of space or withholds capacity (null).
            for ((label, destSpace) in listOf("external-room" to 500_000_000L, "unknown-cap" to null)) {
                val destFile = File(dir, "$label.zip")
                val dest = FileExportDestination(destFile, availableSpaceBytes = destSpace)
                val manager = JournalExportTransferManager(
                    upstreamOpener = { OpenedExportUpstream(ScriptedExportUpstream(payload, etag), "local") },
                    spaceProbe = { 1024L },
                    destinationReopener = { _, _ -> dest },
                    stateStore = ExportStateStore(File(dir, "$label.json")),
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
                    exportId = "exp_$label",
                    downloadPath = "/app/export/api/ready/exp_$label/download",
                    filename = "$label.zip",
                    sizeBytes = payload.size.toLong(),
                    sha256 = sha,
                    etag = etag,
                    mode = "full",
                )
                manager.start(req, { _, cb -> cb(dest) }) {}
                assertEquals("saved", manager.currentSnapshot().phase, "case=$label")
                assertTrue(manager.currentSnapshot().verified, "case=$label")
                assertTrue(destFile.readBytes().contentEquals(payload), "case=$label")
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun resume206WithMismatchedEtagRefusesAppendAndFailsCleanly() {
        val dir = createTempDir("android-export-etag-mismatch")
        try {
            val payload = ByteArray(160 * 1024 + 13) { ((it * 37) and 0xff).toByte() }
            val sha = sha256Hex(payload)
            val etag = "\"sha256-$sha\""
            val destFile = File(dir, "etag-mismatch.zip")
            val dest = FileExportDestination(destFile)
            val attempts = AtomicInteger(0)
            val manager = JournalExportTransferManager(
                upstreamOpener = {
                    OpenedExportUpstream(
                        ScriptedExportUpstream(
                            payload = payload,
                            etag = etag,
                            response206Etag = "\"sha256-stale-different\"",
                            truncateFirstAttemptAt = 65_536,
                            attempts = attempts,
                        ),
                        "local",
                    )
                },
                spaceProbe = { 500_000_000L },
                destinationReopener = { _, _ -> dest },
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
                exportId = "exp_etag",
                downloadPath = "/app/export/api/ready/exp_etag/download",
                filename = "etag-mismatch.zip",
                sizeBytes = payload.size.toLong(),
                sha256 = sha,
                etag = etag,
                mode = "full",
            )
            manager.start(req, { _, cb -> cb(dest) }) {}
            assertEquals("paused", manager.currentSnapshot().phase)

            manager.resume("exp_etag")
            assertEquals("error", manager.currentSnapshot().phase)
            assertEquals("save_failed", manager.currentSnapshot().errorCode)
            assertTrue(dest.deleted)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun constructorRecoveryDoesNotReadOrHashDestinationOnCallerThreadAndSchedulerRoutesByApiLevel() {
        val dir = createTempDir("android-export-nonblocking-init")
        try {
            val payload = ByteArray(64 * 1024) { 0x55 }
            val sha = sha256Hex(payload)
            val destFile = File(dir, "already-full.zip")
            destFile.writeBytes(payload)
            val dest = FileExportDestination(destFile)
            val stateStore = ExportStateStore(File(dir, "state.json"))
            stateStore.save(
                PersistedExportState(
                    exportId = "exp_restore",
                    downloadPath = "/app/export/api/ready/exp_restore/download",
                    filename = "already-full.zip",
                    sizeBytes = payload.size.toLong(),
                    sha256 = sha,
                    etag = "\"sha256-$sha\"",
                    mode = "full",
                    uriString = dest.uriString,
                    displayName = dest.displayName,
                    bytesWritten = payload.size.toLong(),
                    initialTransport = "local",
                    pauseReason = "paused",
                ),
            )

            // 1. Construction must NOT block on currentSizeBytes() or computeSha256AndSizeOrNull().
            val uidtScheduled = AtomicInteger(0)
            val fallbackRuns = AtomicInteger(0)
            val probeUpstream = ScriptedExportUpstream(payload, "\"sha256-$sha\"")
            val api31Manager = JournalExportTransferManager(
                upstreamOpener = { OpenedExportUpstream(probeUpstream, "local") },
                spaceProbe = { 500_000_000L },
                destinationReopener = { _, _ -> dest },
                stateStore = stateStore,
                drainClosed = { false },
                schedulerFactory = { m ->
                    AndroidExportExecutionScheduler(
                        manager = m,
                        sdkIntProvider = { 31 },
                        scheduleUidt = {
                            uidtScheduled.incrementAndGet()
                            true
                        },
                        cancelUidt = {},
                        postFallbackNotification = { _, _ -> },
                        cancelFallbackNotification = {},
                        runFallback = { r ->
                            fallbackRuns.incrementAndGet()
                            r.run()
                        },
                    )
                },
            )
            assertEquals(0, dest.sizeReads, "init must not stat SAF destination synchronously")
            assertEquals(0, dest.hashReads, "init must not hash SAF destination synchronously")
            assertEquals("paused", api31Manager.currentSnapshot().phase)

            // Resuming on API 31 routes through fallback (zero UIDT), probes the live journal
            // for eligibility via 1-byte Range + If-Range, and verifies the complete file on the worker.
            api31Manager.resume("exp_restore")
            assertEquals(0, uidtScheduled.get())
            assertEquals(1, fallbackRuns.get())
            assertEquals(1, probeUpstream.seenHeaders.size)
            assertEquals(
                "bytes=${payload.size - 1}-${payload.size - 1}",
                probeUpstream.seenHeaders[0].firstOrNull { it.first.equals("range", ignoreCase = true) }?.second,
            )
            assertEquals(
                "\"sha256-$sha\"",
                probeUpstream.seenHeaders[0].firstOrNull { it.first.equals("if-range", ignoreCase = true) }?.second,
            )
            assertEquals("saved", api31Manager.currentSnapshot().phase)
            assertTrue(api31Manager.currentSnapshot().verified)

            // 2. API 34+ on a validated network routes directly through UIDT scheduling without fallback.
            val uidt34Count = AtomicInteger(0)
            val fallback34Count = AtomicInteger(0)
            val api34Manager = JournalExportTransferManager(
                upstreamOpener = { error("not needed when UIDT schedules") },
                spaceProbe = { 500_000_000L },
                destinationReopener = { _, _ -> dest },
                stateStore = ExportStateStore(File(dir, "state34.json")),
                drainClosed = { false },
                schedulerFactory = { m ->
                    AndroidExportExecutionScheduler(
                        manager = m,
                        sdkIntProvider = { 34 },
                        hasValidatedNetwork = { true },
                        scheduleUidt = {
                            uidt34Count.incrementAndGet()
                            true
                        },
                        cancelUidt = {},
                        postFallbackNotification = { _, _ -> },
                        cancelFallbackNotification = {},
                        runFallback = { _ -> fallback34Count.incrementAndGet() },
                    )
                },
            )
            val req34 = JournalExportStartRequest(
                exportId = "exp_api34",
                downloadPath = "/app/export/api/ready/exp_api34/download",
                filename = "api34.zip",
                sizeBytes = 4096L,
                sha256 = sha,
                etag = "\"sha256-$sha\"",
                mode = "full",
            )
            api34Manager.start(req34, { _, cb -> cb(FileExportDestination(File(dir, "api34.zip"))) }) {}
            assertEquals(1, uidt34Count.get())
            assertEquals(0, fallback34Count.get())
            assertEquals("transferring", api34Manager.currentSnapshot().phase)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun api35WithoutValidatedNetworkAllowsLocalSaveResumeAndPausesHonestlyWhenDisconnected() {
        val dir = createTempDir("exp-unvalidated")
        try {
            val payload = ByteArray(128 * 1024) { i -> ((i * 19 + 5) and 0xff).toByte() }
            val sha = sha256Hex(payload)
            val etag = "\"sha256-$sha\""
            val split = 48 * 1024

            // 1. Reachable direct journal on local Wi-Fi without validated internet:
            //    skips UIDT (cancels any stale job), streams via fallback, pauses on drop,
            //    resumes via Range + If-Range, and verifies SHA-256.
            val uidtScheduled = AtomicInteger(0)
            val uidtCancelled = AtomicInteger(0)
            val fallbackRuns = AtomicInteger(0)
            val upstream = ScriptedExportUpstream(
                payload = payload,
                etag = etag,
                truncateFirstAttemptAt = split,
            )
            val outFile = File(dir, "local-lan.zip")
            val dest = FileExportDestination(outFile)
            val localManager = JournalExportTransferManager(
                upstreamOpener = { OpenedExportUpstream(upstream, "local") },
                spaceProbe = { 500_000_000L },
                destinationReopener = { _, _ -> dest },
                stateStore = ExportStateStore(File(dir, "local-state.json")),
                drainClosed = { false },
                schedulerFactory = { m ->
                    AndroidExportExecutionScheduler(
                        manager = m,
                        sdkIntProvider = { 35 },
                        hasValidatedNetwork = { false },
                        scheduleUidt = {
                            uidtScheduled.incrementAndGet()
                            true
                        },
                        cancelUidt = { uidtCancelled.incrementAndGet() },
                        postFallbackNotification = { _, _ -> },
                        cancelFallbackNotification = {},
                        runFallback = { r ->
                            fallbackRuns.incrementAndGet()
                            r.run()
                        },
                    )
                },
            )
            val req = JournalExportStartRequest(
                exportId = "exp_lan_only",
                downloadPath = "/app/export/api/ready/exp_lan_only/download",
                filename = "local-lan.zip",
                sizeBytes = payload.size.toLong(),
                sha256 = sha,
                etag = etag,
                mode = "full",
            )
            localManager.start(req, { _, cb -> cb(dest) }) {}
            assertEquals(0, uidtScheduled.get())
            assertTrue(uidtCancelled.get() >= 1)
            assertEquals(1, fallbackRuns.get())
            assertEquals("paused", localManager.currentSnapshot().phase)
            assertEquals("disconnected", localManager.currentSnapshot().pauseReason)
            assertEquals(split.toLong(), localManager.currentSnapshot().bytesWritten)

            localManager.resume("exp_lan_only")
            assertEquals(0, uidtScheduled.get())
            assertEquals(2, fallbackRuns.get())
            assertEquals("saved", localManager.currentSnapshot().phase)
            assertTrue(localManager.currentSnapshot().verified)
            assertTrue(payload.contentEquals(outFile.readBytes()))
            assertEquals(2, upstream.seenHeaders.size)
            assertEquals(
                "bytes=$split-",
                upstream.seenHeaders[1].firstOrNull { it.first.equals("range", ignoreCase = true) }?.second,
            )
            assertEquals(
                etag,
                upstream.seenHeaders[1].firstOrNull { it.first.equals("if-range", ignoreCase = true) }?.second,
            )

            // 2. Truly disconnected without validated internet (unreachable direct journal):
            //    never stalls transferring at 0 B; transitions honestly to paused/disconnected
            //    and posts paused notification.
            val disconnectedNotices = mutableListOf<String>()
            val disconnectedUidt = AtomicInteger(0)
            val offlineDest = FileExportDestination(File(dir, "offline.zip"))
            val offlineManager = JournalExportTransferManager(
                upstreamOpener = { throw java.io.IOException("journal unreachable") },
                spaceProbe = { 500_000_000L },
                destinationReopener = { _, _ -> offlineDest },
                stateStore = ExportStateStore(File(dir, "offline-state.json")),
                drainClosed = { false },
                schedulerFactory = { m ->
                    AndroidExportExecutionScheduler(
                        manager = m,
                        sdkIntProvider = { 35 },
                        hasValidatedNetwork = { false },
                        scheduleUidt = {
                            disconnectedUidt.incrementAndGet()
                            true
                        },
                        cancelUidt = {},
                        postFallbackNotification = { snap, _ -> disconnectedNotices += snap.phase },
                        cancelFallbackNotification = {},
                        runFallback = { r -> r.run() },
                    )
                },
            )
            val offlineReq = JournalExportStartRequest(
                exportId = "exp_offline",
                downloadPath = "/app/export/api/ready/exp_offline/download",
                filename = "offline.zip",
                sizeBytes = payload.size.toLong(),
                sha256 = sha,
                etag = etag,
                mode = "full",
            )
            offlineManager.start(offlineReq, { _, cb -> cb(offlineDest) }) {}
            assertEquals(0, disconnectedUidt.get())
            assertEquals("paused", offlineManager.currentSnapshot().phase)
            assertEquals("disconnected", offlineManager.currentSnapshot().pauseReason)
            assertEquals(0L, offlineManager.currentSnapshot().bytesWritten)
            assertTrue("paused" in disconnectedNotices, "expected paused notification on disconnect")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun resumedFullDocumentChecksLiveEligibilityHonorsOwnershipAndKeepsWriteOnlyHandoffHonest() {
        val dir = createTempDir("exp-full-recovery")
        try {
            val payload = ByteArray(64 * 1024 + 29) { i -> ((i * 37 + 11) and 0xff).toByte() }
            val sha = sha256Hex(payload)
            val etag = "\"sha256-$sha\""
            val lastByte = payload.size - 1

            fun syncScheduler(m: JournalExportTransferManager): ExportExecutionScheduler =
                object : ExportExecutionScheduler {
                    override fun scheduleRun(estimatedRemainingBytes: Long) {
                        m.runTransferAttemptBlocking()
                    }
                    override fun onProgressUpdated(snapshot: JournalExportStatusSnapshot, filename: String) {}
                    override fun onEnded(snapshot: JournalExportStatusSnapshot) {}
                }

            fun saveFullState(
                stateFile: File,
                exportId: String,
                dest: FileExportDestination,
                ownsCreatedDocument: Boolean = false,
            ): ExportStateStore {
                val store = ExportStateStore(stateFile)
                store.save(
                    PersistedExportState(
                        exportId = exportId,
                        downloadPath = "/app/export/api/ready/$exportId/download",
                        filename = dest.displayName,
                        sizeBytes = payload.size.toLong(),
                        sha256 = sha,
                        etag = etag,
                        mode = "full",
                        uriString = dest.uriString,
                        displayName = dest.displayName,
                        bytesWritten = payload.size.toLong(),
                        initialTransport = "local",
                        pauseReason = "paused",
                        ownsCreatedDocument = ownsCreatedDocument,
                    ),
                )
                return store
            }

            fun raw200Upstream(
                responseHeaders: List<Pair<String, String>>,
                body: ByteArray = payload,
                bodyChunksEmitted: AtomicInteger,
                seenHeaders: MutableList<List<Pair<String, String>>>,
            ): JournalBrowserUpstream = object : JournalBrowserUpstream {
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
                    responseSink.onStatusAndHeaders(200, "OK", responseHeaders)
                    bodyChunksEmitted.incrementAndGet()
                    responseSink.onBodyChunk(body, 0, body.size)
                    responseSink.onComplete()
                }

                override fun close() {}
            }

            // 1. Offline resume on a full document must stay paused/disconnected (never confirm offline)
            //    and preserve the already-owned document.
            val offlineFile = File(dir, "offline-full.zip").apply { writeBytes(payload) }
            val offlineDest = FileExportDestination(offlineFile)
            val offlineManager = JournalExportTransferManager(
                upstreamOpener = { throw IOException("journal unreachable") },
                spaceProbe = { 500_000_000L },
                destinationReopener = { _, _ -> offlineDest },
                stateStore = saveFullState(File(dir, "s-offline.json"), "exp_off_full", offlineDest, ownsCreatedDocument = true),
                drainClosed = { false },
                schedulerFactory = ::syncScheduler,
            )
            offlineManager.resume("exp_off_full")
            assertEquals("paused", offlineManager.currentSnapshot().phase)
            assertEquals("disconnected", offlineManager.currentSnapshot().pauseReason)
            assertFalse(offlineDest.deleted)
            assertTrue(offlineFile.readBytes().contentEquals(payload))

            // 2. 410 erasure on a resumed full document fails export_invalidated_by_erasure and
            //    NEVER deletes the already-owned document (even if ownsCreatedDocument was true).
            val erasedFile = File(dir, "erased-full.zip").apply { writeBytes(payload) }
            val erasedDest = FileExportDestination(erasedFile)
            val erasedUpstream = ScriptedExportUpstream(
                payload = ByteArray(0),
                etag = etag,
                fixedStatus = 410,
                fixedBody = """{"code":"export_invalidated_by_erasure"}""".toByteArray(),
            )
            val erasedManager = JournalExportTransferManager(
                upstreamOpener = { OpenedExportUpstream(erasedUpstream, "local") },
                spaceProbe = { 500_000_000L },
                destinationReopener = { _, _ -> erasedDest },
                stateStore = saveFullState(File(dir, "s-erased.json"), "exp_erased_full", erasedDest, ownsCreatedDocument = true),
                drainClosed = { false },
                schedulerFactory = ::syncScheduler,
            )
            erasedManager.resume("exp_erased_full")
            assertEquals("error", erasedManager.currentSnapshot().phase)
            assertEquals("export_invalidated_by_erasure", erasedManager.currentSnapshot().errorCode)
            assertFalse(erasedDest.deleted, "must not delete already-owned complete document on 410 recovery")
            assertTrue(erasedFile.readBytes().contentEquals(payload))
            assertEquals(
                "bytes=$lastByte-$lastByte",
                erasedUpstream.seenHeaders[0].firstOrNull { it.first.equals("range", ignoreCase = true) }?.second,
            )

            // 3. Range-ignoring 200 with matching strong ETag & Content-Length: reads 0 body bytes,
            //    verifies disk copy -> saved/verified=true.
            val ok200File = File(dir, "ok200-full.zip").apply { writeBytes(payload) }
            val ok200Dest = FileExportDestination(ok200File)
            val ok200BodyChunks = AtomicInteger(0)
            val ok200Headers = mutableListOf<List<Pair<String, String>>>()
            val ok200Manager = JournalExportTransferManager(
                upstreamOpener = {
                    OpenedExportUpstream(
                        raw200Upstream(
                            listOf("Content-Length" to payload.size.toString(), "ETag" to etag),
                            bodyChunksEmitted = ok200BodyChunks,
                            seenHeaders = ok200Headers,
                        ),
                        "local",
                    )
                },
                spaceProbe = { 500_000_000L },
                destinationReopener = { _, _ -> ok200Dest },
                stateStore = saveFullState(File(dir, "s-ok200.json"), "exp_ok200", ok200Dest),
                drainClosed = { false },
                schedulerFactory = ::syncScheduler,
            )
            ok200Manager.resume("exp_ok200")
            assertEquals("saved", ok200Manager.currentSnapshot().phase)
            assertTrue(ok200Manager.currentSnapshot().verified)
            assertEquals(0, ok200BodyChunks.get(), "matching strong-ETag 200 must avoid a second full transfer")
            assertFalse(ok200Dest.deleted)

            // 4. Range-ignoring 200 matching strong ETag with 1 flipped byte on disk -> checksum_mismatch
            //    with 0 body bytes transferred and already-owned document preserved.
            val corruptPayload = payload.copyOf().also { it[0] = (it[0].toInt() xor 0x01).toByte() }
            val corruptFile = File(dir, "corrupt-full.zip").apply { writeBytes(corruptPayload) }
            val corruptDest = FileExportDestination(corruptFile)
            val corruptBodyChunks = AtomicInteger(0)
            val corruptManager = JournalExportTransferManager(
                upstreamOpener = {
                    OpenedExportUpstream(
                        raw200Upstream(
                            listOf("Content-Length" to payload.size.toString(), "ETag" to etag),
                            bodyChunksEmitted = corruptBodyChunks,
                            seenHeaders = mutableListOf(),
                        ),
                        "local",
                    )
                },
                spaceProbe = { 500_000_000L },
                destinationReopener = { _, _ -> corruptDest },
                stateStore = saveFullState(File(dir, "s-corrupt.json"), "exp_corrupt", corruptDest, ownsCreatedDocument = true),
                drainClosed = { false },
                schedulerFactory = ::syncScheduler,
            )
            corruptManager.resume("exp_corrupt")
            assertEquals("error", corruptManager.currentSnapshot().phase)
            assertEquals("checksum_mismatch", corruptManager.currentSnapshot().errorCode)
            assertEquals(0, corruptBodyChunks.get())
            assertFalse(corruptDest.deleted, "must preserve already-owned file on recovery checksum mismatch")
            assertTrue(corruptFile.readBytes().contentEquals(corruptPayload))

            // 5. Range-ignoring 200 with changed ETag, weak ETag, or mismatched Content-Length ->
            //    0 body bytes transferred, export_copy_unconfirmed, already-owned file preserved.
            val unconfirmedCases = listOf(
                "changed-etag" to listOf("Content-Length" to payload.size.toString(), "ETag" to "\"sha256-other\""),
                "weak-etag" to listOf("Content-Length" to payload.size.toString(), "ETag" to "W/$etag"),
                "bad-length" to listOf("Content-Length" to (payload.size + 7).toString(), "ETag" to etag),
            )
            for ((label, respHeaders) in unconfirmedCases) {
                val f = File(dir, "$label.zip").apply { writeBytes(payload) }
                val d = FileExportDestination(f)
                val bodyChunks = AtomicInteger(0)
                val mgr = JournalExportTransferManager(
                    upstreamOpener = {
                        OpenedExportUpstream(
                            raw200Upstream(respHeaders, bodyChunksEmitted = bodyChunks, seenHeaders = mutableListOf()),
                            "local",
                        )
                    },
                    spaceProbe = { 500_000_000L },
                    destinationReopener = { _, _ -> d },
                    stateStore = saveFullState(File(dir, "s-$label.json"), "exp_$label", d, ownsCreatedDocument = true),
                    drainClosed = { false },
                    schedulerFactory = ::syncScheduler,
                )
                mgr.resume("exp_$label")
                assertEquals("error", mgr.currentSnapshot().phase, label)
                assertEquals("export_copy_unconfirmed", mgr.currentSnapshot().errorCode, label)
                assertEquals(0, bodyChunks.get(), "$label must abort before reading full body")
                assertFalse(d.deleted, "$label must preserve already-owned document")
                assertTrue(f.readBytes().contentEquals(payload), label)
            }

            // 6. Range-ignoring 200 with absent ETag (including a short/interrupted body, on BOTH
            //    readable and write-only providers): SAF has no atomic staging rename, so recovery
            //    MUST abort at headers with export_copy_unconfirmed, NEVER open/truncate the owner's
            //    already-complete document, NEVER delete it, and preserve all original bytes intact.
            for ((label, allowReadBack) in listOf("absent-etag-readable" to true, "absent-etag-write-only" to false)) {
                val absentEtagFile = File(dir, "$label.zip").apply { writeBytes(payload) }
                val absentEtagDest = FileExportDestination(absentEtagFile, allowReadBack = allowReadBack)
                val absentEtagBodyChunks = AtomicInteger(0)
                val shortInterruptedUpstream = object : JournalBrowserUpstream {
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
                        responseSink.onStatusAndHeaders(200, "OK", listOf("Content-Length" to payload.size.toString()))
                        absentEtagBodyChunks.incrementAndGet()
                        responseSink.onBodyChunk(payload, 0, 1024)
                        throw IOException("short interrupted 200 body")
                    }

                    override fun close() {}
                }
                val absentEtagManager = JournalExportTransferManager(
                    upstreamOpener = { OpenedExportUpstream(shortInterruptedUpstream, "local") },
                    spaceProbe = { 500_000_000L },
                    destinationReopener = { _, _ -> absentEtagDest },
                    stateStore = saveFullState(File(dir, "s-$label.json"), "exp_$label", absentEtagDest, ownsCreatedDocument = true),
                    drainClosed = { false },
                    schedulerFactory = ::syncScheduler,
                )
                absentEtagManager.resume("exp_$label")
                assertEquals("error", absentEtagManager.currentSnapshot().phase, label)
                assertEquals("export_copy_unconfirmed", absentEtagManager.currentSnapshot().errorCode, label)
                assertEquals(0, absentEtagBodyChunks.get(), "$label must abort before reading short 200 body")
                assertEquals(0, absentEtagDest.writerOpens, "$label must never open or truncate an already-complete SAF document")
                assertFalse(absentEtagDest.deleted, "$label must never delete already-owned document")
                assertTrue(absentEtagFile.readBytes().contentEquals(payload), "$label must preserve original bytes intact")
            }

            // 7. 416 on full-document recovery fails export_copy_unconfirmed and preserves document.
            val r416File = File(dir, "r416.zip").apply { writeBytes(payload) }
            val r416Dest = FileExportDestination(r416File)
            val r416Manager = JournalExportTransferManager(
                upstreamOpener = {
                    OpenedExportUpstream(
                        ScriptedExportUpstream(ByteArray(0), etag, fixedStatus = 416),
                        "local",
                    )
                },
                spaceProbe = { 500_000_000L },
                destinationReopener = { _, _ -> r416Dest },
                stateStore = saveFullState(File(dir, "s-416.json"), "exp_416", r416Dest, ownsCreatedDocument = true),
                drainClosed = { false },
                schedulerFactory = ::syncScheduler,
            )
            r416Manager.resume("exp_416")
            assertEquals("error", r416Manager.currentSnapshot().phase)
            assertEquals("export_copy_unconfirmed", r416Manager.currentSnapshot().errorCode)
            assertFalse(r416Dest.deleted)
            assertTrue(r416File.readBytes().contentEquals(payload))

            // 8. Write-only provider on a resumed full document: even after a valid 1-byte 206 probe,
            //    cannot read back stored bytes and did not stream them in this pass, so MUST fail
            //    checksum_mismatch (never fake saved or handed_off) and MUST NOT delete the file.
            val woFile = File(dir, "wo-resumed.zip").apply { writeBytes(payload) }
            val woDest = FileExportDestination(woFile, allowReadBack = false)
            val woProbeUpstream = ScriptedExportUpstream(payload, etag)
            val woManager = JournalExportTransferManager(
                upstreamOpener = { OpenedExportUpstream(woProbeUpstream, "local") },
                spaceProbe = { 500_000_000L },
                destinationReopener = { _, _ -> woDest },
                stateStore = saveFullState(File(dir, "s-wo.json"), "exp_wo_resumed", woDest, ownsCreatedDocument = true),
                drainClosed = { false },
                schedulerFactory = ::syncScheduler,
            )
            woManager.resume("exp_wo_resumed")
            assertEquals("error", woManager.currentSnapshot().phase)
            assertEquals("checksum_mismatch", woManager.currentSnapshot().errorCode)
            assertFalse(woManager.currentSnapshot().verified)
            assertFalse(woDest.deleted, "must preserve write-only provider document on recovery failure")
            assertEquals(1, woProbeUpstream.seenHeaders.size)

            // 9. Pre-existing non-empty destination picked at start() is owned by the provider/owner:
            //    insufficient space or 410 must NEVER delete it.
            val existingBytes = "owner-existing-file".toByteArray()
            val existingFile = File(dir, "preexisting.zip").apply { writeBytes(existingBytes) }
            val existingDest = FileExportDestination(existingFile, availableSpaceBytes = 1024L)
            val existingManager = JournalExportTransferManager(
                upstreamOpener = { error("must fail space check before upstream") },
                spaceProbe = { 500_000_000L },
                destinationReopener = { _, _ -> existingDest },
                stateStore = ExportStateStore(File(dir, "s-existing.json")),
                drainClosed = { false },
                schedulerFactory = ::syncScheduler,
            )
            val existingReq = JournalExportStartRequest(
                exportId = "exp_existing",
                downloadPath = "/app/export/api/ready/exp_existing/download",
                filename = "preexisting.zip",
                sizeBytes = 10_000_000L,
                sha256 = sha,
                etag = etag,
                mode = "full",
            )
            existingManager.start(existingReq, { _, cb -> cb(existingDest) }) {}
            assertEquals("error", existingManager.currentSnapshot().phase)
            assertEquals("insufficient_device_space", existingManager.currentSnapshot().errorCode)
            assertFalse(existingDest.deleted, "must never delete a pre-existing non-empty document")
            assertTrue(existingFile.readBytes().contentEquals(existingBytes))

            // 10. Interrupted 1-byte eligibility probe pauses disconnected while preserving the
            //     full document and progress, then retries the same last byte and verifies.
            val dropProbeFile = File(dir, "drop-probe.zip").apply { writeBytes(payload) }
            val dropProbeDest = FileExportDestination(dropProbeFile)
            val dropProbeAttempts = AtomicInteger(0)
            val dropProbeHeaders = mutableListOf<List<Pair<String, String>>>()
            val dropProbeUpstream = object : JournalBrowserUpstream {
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
                    dropProbeHeaders += headers
                    val n = dropProbeAttempts.incrementAndGet()
                    responseSink.onStatusAndHeaders(
                        206,
                        "Partial Content",
                        listOf(
                            "Content-Type" to "application/zip",
                            "Content-Length" to "1",
                            "Content-Range" to "bytes $lastByte-$lastByte/${payload.size}",
                            "ETag" to etag,
                        ),
                    )
                    if (n == 1) {
                        throw IOException("probe stream interrupted")
                    }
                    responseSink.onBodyChunk(payload, lastByte, 1)
                    responseSink.onComplete()
                }

                override fun close() {}
            }
            val dropProbeManager = JournalExportTransferManager(
                upstreamOpener = { OpenedExportUpstream(dropProbeUpstream, "local") },
                spaceProbe = { 500_000_000L },
                destinationReopener = { _, _ -> dropProbeDest },
                stateStore = saveFullState(File(dir, "s-drop-probe.json"), "exp_drop_probe", dropProbeDest, ownsCreatedDocument = true),
                drainClosed = { false },
                schedulerFactory = ::syncScheduler,
            )
            dropProbeManager.resume("exp_drop_probe")
            assertEquals("paused", dropProbeManager.currentSnapshot().phase)
            assertEquals("disconnected", dropProbeManager.currentSnapshot().pauseReason)
            assertEquals(payload.size.toLong(), dropProbeManager.currentSnapshot().bytesWritten)
            assertFalse(dropProbeDest.deleted)
            assertTrue(dropProbeFile.readBytes().contentEquals(payload))

            dropProbeManager.resume("exp_drop_probe")
            assertEquals("saved", dropProbeManager.currentSnapshot().phase)
            assertTrue(dropProbeManager.currentSnapshot().verified)
            assertEquals(2, dropProbeHeaders.size)
            assertEquals(
                "bytes=$lastByte-$lastByte",
                dropProbeHeaders[0].firstOrNull { it.first.equals("range", ignoreCase = true) }?.second,
            )
            assertEquals(
                "bytes=$lastByte-$lastByte",
                dropProbeHeaders[1].firstOrNull { it.first.equals("range", ignoreCase = true) }?.second,
            )
        } finally {
            dir.deleteRecursively()
        }
    }
}
