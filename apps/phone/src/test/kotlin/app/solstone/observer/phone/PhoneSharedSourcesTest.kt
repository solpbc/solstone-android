// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone

import app.solstone.core.identity.PairingGeneration
import app.solstone.core.model.ReasonCode
import app.solstone.core.model.SourceState
import app.solstone.observer.harness.*
import app.solstone.platform.calendar.CalendarUiState
import app.solstone.platform.obsidian.ObsidianUi
import app.solstone.platform.screenshots.ScreenshotUi
import app.solstone.core.pl.parseJson
import app.solstone.core.pl.HttpResponse
import app.solstone.core.pl.PlHttpClient
import java.nio.file.Files
import java.util.concurrent.Executor
import kotlin.test.*

class PhoneSharedSourcesTest {
    private val file = Files.createTempDirectory("sources").resolve("snapshot").toFile()
    private var pairing: PairingGeneration? = PairingGeneration("journal", "first")
    private var now = 1_000_000L
    private var onList: () -> Unit = {}
    private var onPut: () -> Unit = {}
    private var putStatus = 204
    private var listStatus = 200
    private var capability = true
    private var folderCapability = false
    private val calls = mutableListOf<String>()
    private val bodies = mutableListOf<String>()
    private val queue = ArrayDeque<Runnable>()
    private lateinit var registry: PhoneSharedSources
    private val fake = object : PlHttpClient {
        override fun request(method: String, path: String, headers: Map<String, String>, body: ByteArray?, maxResponseBytes: Int): HttpResponse {
            calls += "$method $path"
            val (status, text) = when (path) {
                "/api/system/status" -> 200 to if (capability) {
                    val caps = if (folderCapability) """["shared_sources","folder"]""" else """["shared_sources"]"""
                    """{"capabilities":$caps,"sources_rev":"rev"}"""
                } else "{}"
                "/app/network/api/clients/self/sources" -> {
                    assertEquals("application/json", headers["Content-Type"])
                    bodies += body!!.decodeToString()
                    onPut()
                    putStatus to ""
                }
                "/app/network/api/sources" -> {
                    onList()
                    listStatus to snapshot
                }
                else -> error("unexpected request")
            }
            return HttpResponse(status, emptyMap(), text.encodeToByteArray())
        }
    }

    private fun start() {
        registry = PhoneSharedSources(file, { pairing }, { fake }, {
            pairing = null
            registry.followPairing()
        }, Executor(queue::addLast), { now }, {})
        registry.followPairing()
    }

    private fun drain() { while (queue.isNotEmpty()) queue.removeFirst().run() }
    @AfterTest fun clean() { file.parentFile.deleteRecursively() }

    @Test fun reconnectPublishesLatestAndReadsOnlyChangedOrExplicitlyRefreshedSnapshots() {
        start()
        registry.update(true, "[]")
        onPut = { registry.update(true, active); onPut = {} }
        drain()
        assertEquals(listOf("[]", active), bodies)
        val devices = registry.known.value!!.second.sources.getValue("obsidian")
        assertEquals(listOf("desk", "legacy"), devices.map { it.cid })
        assertEquals(2, devices.first().rows.size)
        assertEquals("2026-11-20T10:00:00Z", devices.first().rows.first().at.toString())
        assertEquals("2026-11-19T10:00:00Z", devices.last().rows.first().at.toString())
        val initial = calls.size
        registry.update(true, active)
        registry.refresh(false)
        drain()
        assertEquals(initial, calls.size)
        registry.update(false, "[]")
        drain()
        assertEquals(initial, calls.size)
        registry.update(true, "[]")
        drain()
        assertEquals("[]", bodies.last())
        assertEquals(1, calls.count { it == "GET /app/network/api/sources" })
        now += 300_001
        registry.refresh(false)
        drain()
        registry.refresh(true)
        drain()
        assertEquals(3, calls.count { it == "GET /app/network/api/sources" })
    }

    @Test fun offlineCacheRestoresOnlyForTheConfirmedCertificateAndRetiresOnRefusal() {
        start()
        registry.update(true, active)
        drain()
        val original = registry.known.value
        start()
        assertEquals(original, registry.known.value)
        listStatus = 503
        registry.update(true, active)
        registry.refresh(true)
        drain()
        assertEquals(original, registry.known.value)
        assertTrue(file.exists())
        listStatus = 403
        registry.refresh(true)
        drain()
        assertNull(registry.known.value)
        assertFalse(file.exists())
        val total = calls.size
        registry.update(true, active)
        drain()
        assertEquals(total, calls.size)
    }

    @Test fun replacementDuringReadCannotRestoreOldFactsEvenAtTheSameJournal() {
        start()
        registry.update(true, active)
        drain()
        onList = {
            pairing = PairingGeneration("journal", "second")
            registry.followPairing()
        }
        registry.refresh(true)
        drain()
        assertNull(registry.known.value)
        assertFalse(file.exists())
        onList = {}
        registry.update(true, active)
        drain()
        assertEquals("second", registry.known.value?.first)
        capability = false
        registry.refresh(true)
        drain()
        assertNull(registry.known.value)
        assertFalse(file.exists())
    }


    @Test fun configuredSourcesProduceFullReplacementAndUnconfirmedSourcesStayPrivate() {
        fun source(id: String, state: SourceState) = SourceStatus(id, SourceWish.On, state, ReasonCode.NONE)
        val sources = listOf("obsidian", "calendar", "screenshots", "audio", "location", "camera", "screen")
        val model = SourcesReadModel(ObserverStatus(SourceState.ON, ReasonCode.NONE), sources.map {
            source(it, when (it) {
                "camera" -> SourceState.PAUSED
                "location" -> SourceState.NEEDS_ATTENTION
                else -> SourceState.ON
            })
        })
        val full = parseJson(declaration(model, "on", ObsidianUi(configured = true, vault = "Notes"),
            CalendarUiState(enabled = true), ScreenshotUi(confirmed = true))) as List<*>
        assertEquals((sources + "watch").sorted(), full.map { (it as Map<*, *>)["source"] })
        val byId = full.map { it as Map<*, *> }.associateBy { it["source"] }
        assertEquals(parseJson(active), listOf(byId["obsidian"]))
        assertEquals("paused", byId["camera"]?.get("state"))
        assertEquals("needs_attention", byId["location"]?.get("state"))
        val probing = model.copy(sources = model.sources.map { row ->
            when (row.sourceId) {
                "screen", "audio" -> row.copy(wish = SourceWish.Off)
                "camera" -> row.copy(state = SourceState.SETTING_UP)
                "location" -> row.copy(state = SourceState.READY_TO_SET_UP)
                else -> row
            }
        })
        assertEquals("[]", declaration(probing, null, ObsidianUi(settingUp = true),
            CalendarUiState(countStatus = "running"), ScreenshotUi(reconfirm = true)))
    }

    @Test fun folderDeclarationsFollowSeverityCapAndCapabilityRules() {
        fun folder(
            id: String,
            mode: app.solstone.core.folders.FolderMode,
            attention: app.solstone.core.folders.FolderAttention? = null,
            pausedByApp: Boolean = false,
        ) = app.solstone.core.folders.FolderSummary(
            id = id,
            treeUri = "content://tree/$id",
            name = id,
            label = id,
            mode = mode,
            returning = true,
            kinds = app.solstone.core.folders.FolderKinds(),
            facet = null,
            overlap = null,
            attention = attention,
            selectedSpan = app.solstone.core.folders.FolderSpan.LAST_30_DAYS,
            preview = null,
            backfillDone = 0L,
            backfillTotal = 0L,
            waiting = 0,
            itemsTakenIn = 0L,
            lastTakenInMs = null,
            lastCheckedMs = null,
            cantRead = 0L,
            fallbackToDetails = 0L,
            pausedByApp = pausedByApp,
        )
        val model = SourcesReadModel(
            ObserverStatus(SourceState.ON, ReasonCode.NONE),
            listOf(SourceStatus("audio", SourceWish.On, SourceState.ON, ReasonCode.NONE)),
        )
        val foldersUi = app.solstone.platform.folders.FoldersUi(
            folders = listOf(
                folder("work-a1", app.solstone.core.folders.FolderMode.ON),
                folder("docs-b2", app.solstone.core.folders.FolderMode.PAUSED),
                folder("lost-c3", app.solstone.core.folders.FolderMode.ON, attention = app.solstone.core.folders.FolderAttention.PERMISSION_LOST),
                folder("setup-d4", app.solstone.core.folders.FolderMode.SETTING_UP),
                folder("off-e5", app.solstone.core.folders.FolderMode.OFF),
            ),
            capability = true,
        )
        val parsed = parseJson(declaration(model, null, null, null, null, foldersUi)) as List<*>
        val folderEntries = parsed.map { it as Map<*, *> }.filter { it["source"] == "folder" }
        assertEquals(3, folderEntries.size)
        val byDetail = folderEntries.associateBy { it["detail"] }
        assertEquals("on", byDetail["work-a1"]?.get("state"))
        assertEquals("paused", byDetail["docs-b2"]?.get("state"))
        assertEquals("needs_attention", byDetail["lost-c3"]?.get("state"))

        // Omit folder entries when journal omits folder capability.
        val noCap = parseJson(declaration(model, null, null, null, null, foldersUi.copy(capability = false))) as List<*>
        assertTrue(noCap.none { (it as Map<*, *>)["source"] == "folder" })

        // Cap at 64 entries total: audio stays, needs_attention & paused come before on.
        val many = (1..70).map { i ->
            folder("on-%02d".format(i), app.solstone.core.folders.FolderMode.ON)
        } + listOf(
            folder("zz-attn", app.solstone.core.folders.FolderMode.ON, attention = app.solstone.core.folders.FolderAttention.MISSING),
            folder("zz-pause", app.solstone.core.folders.FolderMode.PAUSED),
        )
        val capped = parseJson(declaration(model, null, null, null, null, app.solstone.platform.folders.FoldersUi(folders = many, capability = true))) as List<*>
        assertEquals(64, capped.size)
        val cappedMaps = capped.map { it as Map<*, *> }
        assertTrue(cappedMaps.any { it["source"] == "audio" })
        assertTrue(cappedMaps.any { it["source"] == "folder" && it["detail"] == "zz-attn" && it["state"] == "needs_attention" })
        assertTrue(cappedMaps.any { it["source"] == "folder" && it["detail"] == "zz-pause" && it["state"] == "paused" })
    }

    @Test fun putStripsFolderEntriesWhenStatusOmitsFolderCapability() {
        start()
        val withFolder = """[{"source":"audio","state":"on"},{"source":"folder","state":"on","detail":"work-a1"}]"""
        registry.update(true, withFolder)
        drain()
        assertEquals(1, bodies.size)
        val sent = parseJson(bodies.single()) as List<*>
        assertEquals(1, sent.size)
        assertEquals("audio", (sent.single() as Map<*, *>)["source"])

        folderCapability = true
        registry.update(false, withFolder)
        drain()
        registry.update(true, withFolder)
        drain()
        assertEquals(2, bodies.size)
        val resent = parseJson(bodies.last()) as List<*>
        assertEquals(2, resent.size)
        assertTrue(resent.any { (it as Map<*, *>)["source"] == "folder" && it["detail"] == "work-a1" })
    }

    @Test fun putFallsBackToNonFolderEntriesIfJournalRejectsFolderDeclarationWith400() {
        folderCapability = true
        start()
        var first = true
        onPut = {
            if (first) {
                first = false
                putStatus = 400
            } else {
                putStatus = 204
            }
        }
        val withFolder = """[{"source":"audio","state":"on"},{"source":"folder","state":"on","detail":"work-a1"}]"""
        registry.update(true, withFolder)
        drain()
        assertEquals(2, bodies.size)
        val retried = parseJson(bodies.last()) as List<*>
        assertEquals(1, retried.size)
        assertEquals("audio", (retried.single() as Map<*, *>)["source"])
    }

    @Test fun transientFailureRetriesOnExistingStatusEventsWithoutPollingOnSuccess() {
        start()
        putStatus = 503
        registry.update(true, active)
        drain()
        registry.update(true, active)
        drain()
        assertEquals(1, bodies.size)
        now += 5_001
        putStatus = 204
        registry.update(true, active)
        drain()
        assertEquals(listOf(active, active), bodies)
        assertNotNull(registry.known.value)
        val count = calls.size
        now += 300_001
        registry.update(true, active)
        drain()
        assertEquals(count, calls.size)
    }

    @Test fun rejectionWaitsForAChangedDeclarationAndOldJournalClearsTheCache() {
        start()
        registry.update(true, active)
        drain()
        putStatus = 400
        registry.update(true, "[]")
        drain()
        val attempts = bodies.size
        registry.update(true, "[]")
        registry.refresh(true)
        drain()
        assertEquals(attempts, bodies.size)
        listStatus = 404
        registry.refresh(true)
        drain()
        assertNull(registry.known.value)
        assertFalse(file.exists())
    }

    @Test fun removalReceivedDuringDisconnectWipesOfflineFacts() {
        start()
        registry.update(true, active)
        drain()
        onList = { registry.update(false, active) }
        listStatus = 403
        registry.refresh(true)
        drain()
        assertNull(registry.known.value)
        assertFalse(file.exists())
    }

    companion object {
        const val active = """[{"source":"obsidian","state":"on","detail":"Notes"}]"""
        private val snapshot = """{"self_cid":"me","sources_rev":"rev","devices":[
            {"cid":"me","display_label":"phone","sources":[]},
            {"cid":"legacy","display_label":"Laptop","device_last_seen_at":"2026-11-21T10:00:00Z","sources":[
                {"source":"obsidian","origin":"observed","last_synced_at":"2026-11-19T10:00:00Z"}]},
            {"cid":"desk","display_label":"Desk","device_last_seen_at":"2026-11-20T10:00:00Z","sources":[
                {"source":"obsidian","origin":"declared","detail":"Notes","state":"paused","declared_at":"2026-11-19T10:00:00Z"},
                {"source":"obsidian","origin":"declared","detail":"Work","state":"on","declared_at":null}]}]}"""
    }
}
