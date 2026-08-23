// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import app.solstone.core.model.SourceState
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PhoneDeckSourceCheckTest {
    @Test
    fun tilesDoNotUseFixedHeight() {
        val root = File("src/main/kotlin/app/solstone/observer/formfactor/phone")
        listOf("PhoneSourceTile.kt", "PhoneDeck.kt").forEach { name ->
            val text = root.resolve(name).readText()
            assertFalse(text.contains("Modifier.height("), name)
        }
    }

    @Test
    fun journalPillDoesNotUseNavigationBarsPadding() {
        val text = File("src/main/kotlin/app/solstone/observer/formfactor/phone/PhoneJournalPill.kt").readText()
        val host = File("src/main/kotlin/app/solstone/observer/formfactor/phone/PhoneShell.kt").readText()
        assertFalse(text.contains("navigationBarsPadding"))
        assertFalse(host.contains("navigationBarsPadding"))
    }

    @Test
    fun pillAndPaneAvoidPercentageAndEta() {
        listOf("PhoneStatusPill.kt", "PhoneStatusPane.kt").forEach { name ->
            val text = File("src/main/kotlin/app/solstone/observer/formfactor/phone/$name").readText()
            assertFalse('%' in text, name)
            assertFalse(text.contains("ETA"), name)
            assertFalse(text.contains("ProgressIndicator"), name)
        }
    }

    @Test
    fun retiredOfflineFormAppearsNowhereInPhoneMain() {
        val root = File("src/main/kotlin/app/solstone/observer/formfactor/phone")
        root.walkTopDown().filter { it.extension == "kt" }.forEach { file ->
            assertFalse(file.readText().contains("38 offline"), file.name)
        }
    }

    @Test
    fun mainDoesNotReferenceMinimumTouchTargetConstant() {
        val root = File("src/main/kotlin/app/solstone/observer/formfactor/phone")
        root.walkTopDown().filter { it.extension == "kt" }.forEach { file ->
            if (file.name == "PhoneMetrics.kt") return@forEach
            assertFalse(file.readText().contains("MINIMUM_TOUCH_TARGET_DP"), file.name)
        }
    }

    @Test
    fun audioLabelIsApprovedIdentity() {
        assertTrue(sourceLabel("audio") == "audio")
        assertTrue(sourceLabel("location") == "location")
        assertTrue(sourceLabel("camera") == "camera")
    }

    @Test
    fun sourceStateCopyUsesApprovedStringsAndNullTbd() {
        assertEquals("off", sourceStateCopy(SourceState.OFF))
        assertEquals("taking it in", sourceStateCopy(SourceState.ON))
        assertEquals("needs attention", sourceStateCopy(SourceState.NEEDS_ATTENTION))
        assertNull(sourceStateCopy(SourceState.SETTING_UP))
        assertNull(sourceStateCopy(SourceState.PAUSED))
    }

    @Test
    fun headingTextMapsStatusAndOmitsOtherKeys() {
        assertEquals("what is waiting", headingText("heading.pane_status"))
        assertNull(headingText("heading.route_a"))
        assertNull(headingText("heading.source_detail"))
    }
}
