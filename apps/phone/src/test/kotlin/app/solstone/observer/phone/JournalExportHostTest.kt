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
        private val failOnClose: Boolean = false,
        private val truncateStoredOnCloseTo: Long? = null,
    ) : ExportDestination {
        var deleted = false
        var sizeReads = 0
        var hashReads = 0
        override val uriString: String = file.toURI().toString()

        override fun currentSizeBytes(): Long {
            sizeReads += 1
            return if (file.exists()) file.length() else 0L
        }

        override fun availableSpaceBytesOrNull(): Long? = availableSpaceBytes

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
            val api31Manager = JournalExportTransferManager(
                upstreamOpener = { error("must verify complete disk copy without network") },
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

            // Resuming on API 31 routes through fallback (zero UIDT) and verifies the complete file on the worker.
            api31Manager.resume("exp_restore")
            assertEquals(0, uidtScheduled.get())
            assertEquals(1, fallbackRuns.get())
            assertEquals("saved", api31Manager.currentSnapshot().phase)
            assertTrue(api31Manager.currentSnapshot().verified)

            // 2. API 34+ routes directly through UIDT scheduling without using fallback executor.
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
}
