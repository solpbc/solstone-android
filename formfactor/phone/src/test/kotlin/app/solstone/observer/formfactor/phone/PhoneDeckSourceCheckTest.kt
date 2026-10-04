// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import app.solstone.core.identity.JournalMark
import app.solstone.core.identity.JournalMarkIcon
import app.solstone.core.identity.JournalMarkPresentation
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
        assertEquals("ready to set up", sourceStateCopy(SourceState.READY_TO_SET_UP))
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

        assertEquals(JournalMarkTokens.GENERIC_ACCESSIBLE_NAME, spokenPaneTitle(PhonePane.JOURNAL))
        assertEquals(journalMarkSpokenForm(JournalMarkPresentation.Generic), spokenPaneTitle(PhonePane.JOURNAL))

        val unavailableSpoken = spokenPaneTitle(PhonePane.JOURNAL, JournalMarkPresentation.Unavailable)
        assertEquals(JournalMarkTokens.UNAVAILABLE_ACCESSIBLE_NAME, unavailableSpoken)
        assertEquals(journalMarkSpokenForm(JournalMarkPresentation.Unavailable), unavailableSpoken)
        assertNotEquals(JournalMarkTokens.GENERIC_ACCESSIBLE_NAME, unavailableSpoken)

        val loadingSpoken = spokenPaneTitle(PhonePane.JOURNAL, JournalMarkPresentation.Loading)
        assertEquals(JournalMarkTokens.LOADING_ACCESSIBLE_NAME, loadingSpoken)
        assertEquals(journalMarkSpokenForm(JournalMarkPresentation.Loading), loadingSpoken)
        assertNotEquals(JournalMarkTokens.GENERIC_ACCESSIBLE_NAME, loadingSpoken)

        val mark = JournalMark(
            icon1 = JournalMarkIcon(name = "circle", svg = "<path/>", colorName = "blue", colorHex = "#0000FF", rot = 0),
            icon2 = JournalMarkIcon(name = "sun", svg = "<path/>", colorName = "gold", colorHex = "#FFD700", rot = 0),
            words = listOf("river", "stone"),
        )
        val expected = listOf(mark.icon1.colorName, mark.icon2.colorName, mark.words[0], mark.words[1]).joinToString(", ")
        val identifiedPresentation = JournalMarkPresentation.Identified(mark)
        val identifiedSpoken = spokenPaneTitle(PhonePane.JOURNAL, identifiedPresentation)
        assertEquals(expected, journalMarkSpokenForm(identifiedPresentation))
        assertEquals(expected, identifiedSpoken)
        assertFalse(identifiedSpoken.contains(mark.icon1.name))
        assertFalse(identifiedSpoken.contains(mark.icon2.name))
        assertEquals(4, expected.split(", ").size)

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
            } else if (file.name == "PhoneAboutFacts.kt") {
                assertEquals(1, text.split("currentTimeMillis").size - 1, file.name)
                tokens.filterNot { it == "currentTimeMillis" }.forEach { token ->
                    assertFalse(text.contains(token), "${file.name} contains $token")
                }
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
