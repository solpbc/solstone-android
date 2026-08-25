// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import app.solstone.core.model.SourceState
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
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
    fun phoneSourceLabelsEqualApprovedTable() {
        assertEquals(
            mapOf(
                "audio" to "audio",
                "location" to "location",
                "camera" to "camera",
            ),
            phoneSourceLabels,
        )
    }

    @Test
    fun sourceStateCopyMapsEveryApprovedWord() {
        assertEquals("off", sourceStateCopy(SourceState.OFF))
        assertEquals("setting up", sourceStateCopy(SourceState.SETTING_UP))
        assertEquals("on", sourceStateCopy(SourceState.ON))
        assertEquals("paused", sourceStateCopy(SourceState.PAUSED))
        assertEquals("needs attention", sourceStateCopy(SourceState.NEEDS_ATTENTION))
        SourceState.entries.forEach { state ->
            assertTrue(sourceStateCopy(state).isNotBlank(), state.name)
        }
    }

    @Test
    fun headingTextMapsStatusAndKeepsJournalAsAMarkName() {
        assertEquals("status", headingText(PhonePane.STATUS))
        assertEquals("settings", headingText(PhonePane.SHELF))
        assertEquals("camera", headingText(PhoneRoute.SourceDetail("camera")))
        assertNull(headingText(PhonePane.JOURNAL))
        assertNotEquals("journal", headingText(PhonePane.JOURNAL))
        assertEquals("your journal, not set up yet", spokenPaneTitle(PhonePane.JOURNAL))
        assertNull(headingText(PhoneRoute.RouteA))
        assertNull(headingText(PhoneDeck))
    }

    @Test
    fun headingTextMapsAboutSolstoneAndLicenses() {
        assertEquals("about solstone", headingText(PhoneRoute.AboutSolstone))
        assertEquals("licenses", headingText(PhoneRoute.Licences))
    }

    @Test
    fun headingTextMapsShelfRoutes() {
        assertEquals("your journal", headingText(PhoneRoute.YourJournal))
        assertEquals("this device", headingText(PhoneRoute.ThisDevice))
        assertEquals("notifications", headingText(PhoneRoute.Notifications))
        assertEquals("help", headingText(PhoneRoute.Help))
    }

    @Test
    fun clockApiLivesOnlyAsOneReadOnTheObserverScreen() {
        val tokens = listOf(
            "currentTimeMillis",
            "LocalTime",
            "Calendar",
            "Instant",
            "Clock",
            "SystemClock",
            "java.time",
        )
        val root = File("src/main/kotlin/app/solstone/observer/formfactor/phone")
        root.walkTopDown().filter { it.extension == "kt" }.forEach { file ->
            val text = file.readText()
            if (file.name == "PhoneObserverScreen.kt") {
                assertEquals(1, text.split("LocalTime.now(").size - 1, file.name)
            } else {
                tokens.forEach { token ->
                    assertFalse(text.contains(token), "${file.name} contains $token")
                }
            }
        }
    }

    @Test
    fun retiredTileSublinesAppearNowhereInPhoneMain() {
        val root = File("src/main/kotlin/app/solstone/observer/formfactor/phone")
        root.walkTopDown().filter { it.extension == "kt" }.forEach { file ->
            val text = file.readText()
            assertFalse(text.contains("tap to fix"), file.name)
            assertFalse(text.contains("turn on any time"), file.name)
        }
    }

    @Test
    fun adaptiveInfoSeamIsTheOnlyPosturePath() {
        val root = File("src/main/kotlin/app/solstone/observer/formfactor/phone")
        val sources = root.walkTopDown().filter { it.extension == "kt" }.toList()
        val allSourceText = sources.joinToString("\n") { it.readText() }
        val observerScreen = root.resolve("PhoneObserverScreen.kt").readText()

        assertEquals(1, "windowAdaptiveInfo: WindowAdaptiveInfo".toRegex().findAll(allSourceText).count())
        assertEquals(1, observerScreen.split("windowAdaptiveInfo = currentWindowAdaptiveInfo(").size - 1)

        val handRolledApis = listOf(
            "WindowInfoTracker",
            "collectFoldingFeaturesAsState",
            "windowLayoutInfo",
        )
        sources.forEach { file ->
            val text = file.readText()
            handRolledApis.forEach { api ->
                assertFalse(text.contains(api), "${file.name} contains $api")
            }
        }
    }
}
