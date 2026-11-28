// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import app.solstone.core.folders.FolderAttention
import app.solstone.core.folders.FolderKinds
import app.solstone.core.folders.FolderMode
import app.solstone.core.folders.FolderSpan
import app.solstone.core.folders.FolderSummary
import app.solstone.core.model.SourceState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class PhoneFoldersStateTest {
    private fun summary(
        id: String,
        mode: FolderMode,
        attention: FolderAttention? = null,
        pausedByApp: Boolean = false,
    ) = FolderSummary(
        id = id,
        treeUri = "content://tree/$id",
        name = id,
        label = id,
        mode = mode,
        returning = false,
        kinds = FolderKinds(),
        facet = null,
        overlap = null,
        attention = attention,
        selectedSpan = FolderSpan.LAST_30_DAYS,
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

    @Test
    fun tileStateFollowsWorstSignalWinsAndCarriesNoSwitch() {
        assertFalse(sourceEarnsSwitch("folders"))
        assertEquals(SourceState.READY_TO_SET_UP, foldersTileState(PhoneFoldersState()))
        assertNull(foldersSourceSubLine(PhoneFoldersState(), paired = true))

        val allOff = PhoneFoldersState(folders = listOf(summary("a", FolderMode.OFF)))
        assertEquals(SourceState.OFF, foldersTileState(allOff))

        val pausedAndOff = PhoneFoldersState(
            folders = listOf(summary("a", FolderMode.OFF), summary("b", FolderMode.PAUSED)),
        )
        assertEquals(SourceState.PAUSED, foldersTileState(pausedAndOff))

        val onAndPaused = PhoneFoldersState(
            folders = listOf(summary("a", FolderMode.PAUSED), summary("b", FolderMode.ON)),
        )
        assertEquals(SourceState.ON, foldersTileState(onAndPaused))

        val settingUpAndOn = PhoneFoldersState(
            folders = listOf(summary("a", FolderMode.ON), summary("b", FolderMode.SETTING_UP)),
        )
        assertEquals(SourceState.SETTING_UP, foldersTileState(settingUpAndOn))

        val attentionAndSettingUp = PhoneFoldersState(
            folders = listOf(
                summary("a", FolderMode.SETTING_UP),
                summary("b", FolderMode.ON, attention = FolderAttention.PERMISSION_LOST),
            ),
        )
        assertEquals(SourceState.NEEDS_ATTENTION, foldersTileState(attentionAndSettingUp))
    }

    @Test
    fun folderDetailRouteRoundTripsThroughCodec() {
        val route = PhoneRoute.FolderDetail("downloads-a1b2c3d4")
        val encoded = encodePhoneRoute(route)
        assertEquals(route, decodePhoneRoute(encoded))
    }

    @Test
    fun everyPhoneTextKeyInPhoneFoldersPaneExistsInCatalog() {
        val source = java.io.File("src/main/kotlin/app/solstone/observer/formfactor/phone/PhoneFoldersPane.kt")
            .readText()
        val keys = Regex("""phoneText\(\s*"([^"]+)"""").findAll(source).map { it.groupValues[1] }.toSet()
        kotlin.test.assertTrue(keys.isNotEmpty())
        val sampleArgs = arrayOf<Pair<String, Any?>>(
            "folder" to "Downloads",
            "other" to "Obsidian",
            "name" to "Work",
            "facet" to "Work",
            "first" to "Work",
            "second" to "Personal",
            "count" to 2,
            "on" to 1,
            "paused" to 1,
            "done" to 1,
            "total" to 3,
            "items" to "3 items",
            "start" to "Nov 1",
            "end" to "Nov 27",
            "content" to "2 files",
            "meta" to "1 other",
            "size" to "12 kB",
            "segments" to "~1 segment",
            "days" to "1 day",
            "thoughts" to "~1 thought",
        )
        for (key in keys) {
            val resolved = app.solstone.core.i18n.phoneText(key, *sampleArgs)
            kotlin.test.assertTrue(resolved.isNotBlank(), "empty resolution for $key")
        }
    }
}
