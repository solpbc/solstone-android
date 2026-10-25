// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone

import app.solstone.core.model.ReasonCode
import app.solstone.core.model.SourceState
import app.solstone.core.spool.GAP_HEADLINE
import app.solstone.core.spool.GAP_TODAY
import app.solstone.core.spool.GapNoticeStore
import app.solstone.observer.formfactor.phone.PhoneWidgetStartOutcome
import app.solstone.observer.formfactor.phone.renderPhoneObserverWidget
import app.solstone.observer.formfactor.phone.statusPillText
import java.time.Instant
import app.solstone.observer.harness.ObserverStatus
import app.solstone.observer.harness.SourcesReadModel
import app.solstone.platform.identity.file.CommitMarkerStatus
import app.solstone.platform.identity.file.PairingCommitMarker
import java.io.File
import java.nio.file.Files
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ColdGapStatusTest {
    @Test
    fun coldAssemblyShowsAnInWindowLossAndDoesNotTreatAMissingReadAsAttention() {
        val root = Files.createTempDirectory("cold-gap").toFile()
        try {
            val files = File(root, "files")
            val cache = File(root, "cache")
            commit(files)
            val now = System.currentTimeMillis()
            val zone = ZoneId.systemDefault()
            val store = GapNoticeStore(File(files, "gap").toPath(), clock = { now }, zone = { zone })
            assertTrue(store.recordLoss("audio", "undeliverable", "rec-1", now))
            val closed = coldPhoneStatus(files, cache, now, zone)
            assertFalse(closed.gateOpen)
            assertFalse(statusPillText(closed, now, zone) == GAP_HEADLINE)
            File(files, "gap/opened").writeBytes(ByteArray(0))
            val status = coldPhoneStatus(files, cache, now, zone)
            assertTrue(status.paired)
            assertFalse(status.connectivityKnown)
            assertFalse(status.awaitingMarkConfirmation)
            assertTrue(status.gateOpen)
            val rendered = render(status)
            assertEquals(GAP_HEADLINE, rendered.syncText)
            assertEquals(GAP_TODAY, rendered.syncDetail)
            assertFalse(rendered.needsAttention)
            assertFalse(rendered.syncText == "not paired")
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun noInWindowLossIsConnectedAndNotPairedOnlyWithoutACommit() {
        val root = Files.createTempDirectory("cold-clean").toFile()
        try {
            val files = File(root, "files")
            val cache = File(root, "cache")
            val now = System.currentTimeMillis()
            val zone = ZoneId.systemDefault()
            val absent = coldPhoneStatus(files, cache, now, zone)
            assertFalse(absent.paired)
            assertEquals("not paired", render(absent).syncText)

            commit(files, CommitMarkerStatus.ABSENT)
            File(files, "pl/identity.tsv").writeText("paired\n")
            assertFalse(coldPaired(files))

            File(files, "pl/pairing.commit").writeText(
                PairingCommitMarker(status = CommitMarkerStatus.COMMITTED, instanceId = "journal").toSerializedString(),
            )
            val clean = coldPhoneStatus(files, cache, now, zone)
            val rendered = render(clean)
            assertEquals("", rendered.syncText)
            assertNull(rendered.syncDetail)
            assertFalse(rendered.needsAttention)
            assertFalse(rendered.syncText.contains("syncing"))
            assertFalse(rendered.syncText == "connected")
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun theLiveGateReadsTheStoredOpeningAndAFailedConversionDoesNotHoldIt() {
        val root = Files.createTempDirectory("live-gate").toFile()
        try {
            val files = File(root, "files")
            val now = System.currentTimeMillis()
            val zone = ZoneId.systemDefault()
            val store = GapNoticeStore(File(files, "gap").toPath(), clock = { now }, zone = { zone })
            assertTrue(store.recordLoss("audio", "undeliverable", "rec-1", now))
            assertTrue(store.rewriteUnknown(listOf("draft/leaf")))
            val before = liveGapStatus(files, noticesMayShow = true, now, zone)
            assertFalse(before.gateOpen)
            assertTrue(before.losses.isEmpty())
            assertFalse(before.unknownPresent)
            assertTrue(before.technicalDetails.startsWith("not run"))

            File(files, "gap/opened").writeBytes(ByteArray(0))
            val waiting = liveGapStatus(files, noticesMayShow = false, now, zone)
            assertFalse(waiting.gateOpen)
            assertTrue(waiting.losses.isEmpty())

            File(files, "gap/conversion").writeText("failed IOException\n")
            val open = liveGapStatus(files, noticesMayShow = true, now, zone)
            assertTrue(open.gateOpen)
            assertEquals(1, open.losses.size)
            assertFalse(open.losses.single().seen)
            assertTrue(open.unknownPresent)
            assertTrue(open.unknownUnseen)
            assertEquals(listOf("draft/leaf"), open.unseenUnknownKeys)
            assertTrue(open.technicalDetails.startsWith("failed IOException"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun aColdRedrawKeepsTheLastStatusLineAndDropsTheGapWhenTheWindowEnds() {
        val root = Files.createTempDirectory("cold-remembered").toFile()
        try {
            val files = File(root, "files")
            val cache = File(root, "cache")
            commit(files)
            val zone = ZoneId.of("UTC")
            val now = Instant.parse("2026-10-25T15:00:00Z").toEpochMilli()
            val found = Instant.parse("2026-10-17T15:00:00Z").toEpochMilli()
            var clock = found
            val store = GapNoticeStore(File(files, "gap").toPath(), clock = { clock }, zone = { zone })
            assertTrue(store.recordLoss("audio", "undeliverable", "rec-old", found))
            File(files, "gap/opened").writeBytes(ByteArray(0))
            rememberWidgetSyncText(files, "3 syncing")
            rememberWidgetSyncText(files, GAP_HEADLINE)
            assertEquals("3 syncing", File(files, WIDGET_SYNC_LINE).readText())
            val later = coldPhoneStatus(files, cache, now, zone)
            assertEquals("3 syncing", statusPillText(later, now, zone))
            assertFalse(statusPillText(later, now, zone) == GAP_HEADLINE)

            clock = now
            assertTrue(store.recordLoss("audio", "undeliverable", "rec-new", now))
            val during = coldPhoneStatus(files, cache, now, zone)
            assertEquals(GAP_HEADLINE, statusPillText(during, now, zone))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun anUnreadableGapStoreSaysSoAndAMissingOneHasNotRun() {
        val root = Files.createTempDirectory("gap-unreadable").toFile()
        try {
            val files = File(root, "files").apply { mkdirs() }
            val now = System.currentTimeMillis()
            val zone = ZoneId.of("UTC")
            File(files, "gap").writeText("blocked")
            assertEquals("unreadable", liveGapStatus(files, noticesMayShow = true, now, zone).technicalDetails)
            assertEquals("unreadable", coldPhoneStatus(files, File(root, "cache"), now, zone).gapTechnicalDetails)
            val fresh = File(root, "fresh")
            assertEquals("not run", liveGapStatus(fresh, noticesMayShow = true, now, zone).technicalDetails)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun custodyUnprovenAndSealFailedFoldersStillWait() {
        val root = Files.createTempDirectory("custody-shape").toFile()
        try {
            val files = File(root, "files")
            val audio = File(files, "audio-source").apply { mkdirs() }
            val unproven = File(audio, "rec-unproven").apply { mkdirs() }
            File(unproven, "record.txt").writeText("record")
            val sealFailed = File(audio, "rec-seal").apply { mkdirs() }
            File(sealFailed, "record.txt").writeText("record")
            assertFalse(File(unproven, "gap-loss").exists())
            assertFalse(File(sealFailed, "gap-loss").exists())
            assertTrue(audioAttemptStillWaiting(unproven))
            assertTrue(audioAttemptStillWaiting(sealFailed))
            assertTrue(audioAwaitingCustody(File(root, "cache"), files, emptySet()))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun custodySkipsALossFactAnEmptyRecordingAndAScreenDirectory() {
        val root = Files.createTempDirectory("custody").toFile()
        try {
            val files = File(root, "files")
            val cache = File(root, "cache")
            val audio = File(files, "audio-source")
            File(audio, "screen").apply { mkdirs(); File(this, "clip").writeText("x") }
            File(files, "screen-source/rec-screen").apply { mkdirs(); File(this, "record.txt").writeText("screen") }
            assertFalse(audioAwaitingCustody(cache, files, emptySet()))

            val kept = File(audio, "rec-kept").apply { mkdirs() }
            File(kept, "record.txt").writeText("record")
            File(kept, "gap-loss").writeBytes(ByteArray(0))
            val empty = File(audio, "rec-empty").apply { mkdirs() }
            File(empty, "capture.adts").writeBytes(ByteArray(0))
            assertFalse(audioAwaitingCustody(cache, files, emptySet()))

            File(audio, "rec-record").apply { mkdirs(); File(this, "record.txt").writeText("record") }
            assertTrue(audioAwaitingCustody(cache, files, emptySet()))
            assertFalse(audioAwaitingCustody(cache, files, setOf("rec-record")))

            File(audio, "rec-record").deleteRecursively()
            File(audio, "rec-bytes").apply { mkdirs(); File(this, "audio.m4a").writeBytes(byteArrayOf(1)) }
            assertTrue(audioAwaitingCustody(cache, files, emptySet()))

            val legacy = File(cache, "audio-source").apply { mkdirs() }
            assertFalse(audioAwaitingCustody(cache, File(root, "empty-files"), emptySet()))
            File(legacy, "old.m4a").writeBytes(byteArrayOf(1))
            assertTrue(audioAwaitingCustody(cache, File(root, "empty-files"), emptySet()))
            legacy.deleteRecursively()
            File(cache, "audio-source").writeText("unlisted")
            assertTrue(audioAwaitingCustody(cache, File(root, "empty-files"), emptySet()))
        } finally {
            root.deleteRecursively()
        }
    }

    private fun commit(files: File, status: CommitMarkerStatus = CommitMarkerStatus.COMMITTED) {
        File(files, "pl").mkdirs()
        File(files, "pl/identity.tsv").writeText("paired\n")
        File(files, "pl/pairing.commit").writeText(
            PairingCommitMarker(status = status, instanceId = "journal").toSerializedString(),
        )
    }

    private fun render(status: app.solstone.observer.formfactor.phone.PhoneStatusModel) =
        renderPhoneObserverWidget(
            readModel = SourcesReadModel(
                observer = ObserverStatus(SourceState.OFF, ReasonCode.NONE),
                sources = emptyList(),
            ),
            statusModel = status,
            startOutcome = PhoneWidgetStartOutcome.None,
        )
}
