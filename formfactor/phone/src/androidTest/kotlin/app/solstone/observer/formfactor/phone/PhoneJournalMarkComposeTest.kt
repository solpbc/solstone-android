// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.solstone.core.identity.JournalMark
import app.solstone.core.identity.JournalMarkIcon
import app.solstone.core.identity.JournalMarkPresentation
import app.solstone.core.identity.JournalMarkRecord
import app.solstone.core.identity.JournalMarkStore
import app.solstone.core.pl.HttpResponse
import app.solstone.core.pl.JournalIdentityRefreshCoordinator
import app.solstone.core.pl.PlHttpClient
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhoneJournalMarkComposeTest {
    @get:Rule
    val composeRule = createComposeRule()

    private class InMemoryMarkStore : JournalMarkStore {
        var record: JournalMarkRecord? = null
        override fun load(): JournalMarkRecord? = record
        override fun save(r: JournalMarkRecord) { record = r }
        override fun clear() { record = null }
    }

    private class RoutingFakeClient(
        private val handler: (method: String, path: String) -> HttpResponse,
    ) : PlHttpClient {
        override fun request(
            method: String,
            path: String,
            headers: Map<String, String>,
            body: ByteArray?,
            maxResponseBytes: Int,
        ): HttpResponse = handler(method, path)
    }

    private val fixture1Mark = JournalMark(
        icon1 = JournalMarkIcon(
            name = "piano",
            svg = "<path d=\"M18.5 3H5.5C4.12 3 3 4.12 3 5.5v13C3 19.88 4.12 21 5.5 21h13c1.38 0 2.5-1.12 2.5-2.5v-13C21 4.12 19.88 3 18.5 3z\"/><path d=\"M7 3v9\"/><path d=\"M12 3v9\"/><path d=\"M17 3v9\"/><path d=\"M3 12h18\"/>",
            colorName = "blue",
            colorHex = "#3b82f6",
            rot = 45,
        ),
        icon2 = JournalMarkIcon(
            name = "key",
            svg = "<path d=\"m15.5 7.5 2.3 2.3a1 1 0 0 0 1.4 0l2.1-2.1a1 1 0 0 0 0-1.4L19 4\"/><path d=\"m21 2-9.6 9.6\"/><circle cx=\"7.5\" cy=\"15.5\" r=\"5.5\"/>",
            colorName = "purple",
            colorHex = "#a855f7",
            rot = 0,
        ),
        words = listOf("liquefy", "smock"),
    )

    private val chartreuseMark = JournalMark(
        icon1 = JournalMarkIcon(
            name = "piano",
            svg = "<path d=\"M18.5 3H5.5\"/>",
            colorName = "chartreuse",
            colorHex = "#3b82f6",
            rot = 45,
        ),
        icon2 = JournalMarkIcon(
            name = "key",
            svg = "<circle cx=\"7.5\" cy=\"15.5\" r=\"5.5\"/>",
            colorName = "purple",
            colorHex = "#a855f7",
            rot = 0,
        ),
        words = listOf("liquefy", "smock"),
    )

    private val fixture2Mark = JournalMark(
        icon1 = JournalMarkIcon(
            name = "turtle",
            svg = "<path d=\"m12 10 2 4v3a1 1 0 0 0 1 1h2a1 1 0 0 0 1-1v-3a8 8 0 1 0-16 0v3a1 1 0 0 0 1 1h2a1 1 0 0 0 1-1v-3l2-4\"/><path d=\"M4.82 7.9 8 10\"/><path d=\"M19.18 7.9 16 10\"/><path d=\"M10.5 5.5 8 10\"/><path d=\"M13.5 5.5 16 10\"/>",
            colorName = "pink",
            colorHex = "#ec4899",
            rot = 0,
        ),
        icon2 = JournalMarkIcon(
            name = "pizza",
            svg = "<path d=\"m10 14 2 2\"/><path d=\"m15 9-6 6\"/><path d=\"M15 19v-4a3 3 0 0 0-3-3l-7.79-.31A12 12 0 0 1 20 5.48v.02A12.04 12.04 0 0 1 15 19Z\"/><path d=\"M3.14 11.23A12.03 12.03 0 0 1 5.48 4v.02A12 12 0 0 1 11.23 3.14\"/>",
            colorName = "cyan",
            colorHex = "#06b6d4",
            rot = 0,
        ),
        words = listOf("distrust", "chokehold"),
    )

    @Test
    fun placeholderGenericCardHasCorrectCopyAndSemantics() {
        composeRule.setContent {
            PhoneTheme {
                JournalMarkCard()
            }
        }
        composeRule.onNodeWithTag("journalMarkCard")
            .assertContentDescriptionEquals("your journal, not set up yet")
        composeRule.onNodeWithText("your", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText("journal", useUnmergedTree = true).assertExists()
    }

    @Test
    fun unavailableMarkCardHasUnavailableSemanticsDistinctFromGeneric() {
        composeRule.setContent {
            PhoneTheme {
                JournalMarkCard(presentation = JournalMarkPresentation.Unavailable)
            }
        }
        composeRule.onNodeWithTag("journalMarkCard")
            .assertContentDescriptionEquals("your journal's mark, unavailable right now")
        composeRule.onNodeWithText("mark", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText("unavailable", useUnmergedTree = true).assertExists()
        assertNotEquals(
            "your journal's mark, unavailable right now",
            JournalMarkTokens.GENERIC_ACCESSIBLE_NAME,
        )
    }

    @Test
    fun identifiedFixture1HasSpokenWordsBluePurpleLiquefySmock() {
        composeRule.setContent {
            PhoneTheme {
                JournalMarkCard(presentation = JournalMarkPresentation.Identified(fixture1Mark))
            }
        }
        composeRule.onNodeWithTag("journalMarkCard")
            .assertContentDescriptionEquals("blue, purple, liquefy, smock")
        composeRule.onNodeWithText("liquefy", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText("smock", useUnmergedTree = true).assertExists()
    }

    @Test
    fun chartreuseFixtureHasSpokenWordsChartreusePurpleLiquefySmock() {
        composeRule.setContent {
            PhoneTheme {
                JournalMarkCard(presentation = JournalMarkPresentation.Identified(chartreuseMark))
            }
        }
        composeRule.onNodeWithTag("journalMarkCard")
            .assertContentDescriptionEquals("chartreuse, purple, liquefy, smock")
    }

    @Test
    fun twoIdentifiedFixturesDifferInWordsAndRenderedPixels() {
        var currentPresentation by androidx.compose.runtime.mutableStateOf<JournalMarkPresentation>(
            JournalMarkPresentation.Identified(fixture1Mark),
        )

        composeRule.setContent {
            PhoneTheme {
                JournalMarkCard(
                    presentation = currentPresentation,
                    modifier = androidx.compose.ui.Modifier.size(width = 300.dp, height = 160.dp),
                )
            }
        }

        composeRule.onNodeWithText("liquefy", useUnmergedTree = true).assertExists()
        val image1 = composeRule.onNodeWithTag("journalMarkCard").captureToImage()

        currentPresentation = JournalMarkPresentation.Identified(fixture2Mark)
        composeRule.waitForIdle()

        composeRule.onNodeWithText("distrust", useUnmergedTree = true).assertExists()
        val image2 = composeRule.onNodeWithTag("journalMarkCard").captureToImage()

        assertImagesDiffer(image1, image2)
    }

    @Test
    fun pairingSuccessMarkShowsLoadingWhileGetPendingThenSwapsToIdentified() {
        val store = InMemoryMarkStore()
        val executor = Executors.newCachedThreadPool()
        val coordinator = JournalIdentityRefreshCoordinator(store, executor)

        val inRequest = CountDownLatch(1)
        val releaseRequest = CountDownLatch(1)

        val json = """
            {
              "committed": true,
              "instance_id": "inst-1",
              "mark": {
                "icon1": { "name": "piano", "svg": "<path d=\"M0 0h24v24H0z\"/>", "color": { "name": "blue", "hex": "#3b82f6" }, "rot": 45 },
                "icon2": { "name": "key", "svg": "<path d=\"M0 0h24v24H0z\"/>", "color": { "name": "purple", "hex": "#a855f7" }, "rot": 0 },
                "words": ["liquefy", "smock"]
              }
            }
        """.trimIndent()

        val client = RoutingFakeClient { _, _ ->
            inRequest.countDown()
            releaseRequest.await(5, TimeUnit.SECONDS)
            HttpResponse(200, emptyMap(), json.toByteArray())
        }

        composeRule.setContent {
            PhoneTheme {
                PairingSuccessMark(coordinator = coordinator)
            }
        }

        // A fresh pairing has no stored mark yet, so the card reads as loading
        composeRule.onNodeWithTag("journalMarkCard")
            .assertContentDescriptionEquals("your journal, mark loading")

        // Trigger usable connection
        coordinator.onUsableConnection(
            instanceId = "inst-1",
            pairingMatches = { true },
            openClient = { client },
        )

        assertTrue(inRequest.await(5, TimeUnit.SECONDS))
        // While GET in flight, card still reads as loading
        composeRule.onNodeWithTag("journalMarkCard")
            .assertContentDescriptionEquals("your journal, mark loading")

        // Release GET response
        releaseRequest.countDown()

        // Card reactively swaps to identified
        composeRule.waitUntil(5000) {
            coordinator.currentPresentation() is JournalMarkPresentation.Identified
        }
        composeRule.waitForIdle()

        composeRule.onNodeWithTag("journalMarkCard")
            .assertContentDescriptionEquals("blue, purple, liquefy, smock")
        composeRule.onNodeWithText("liquefy", useUnmergedTree = true).assertExists()

        coordinator.close()
        executor.shutdown()
    }

    @Test
    fun mockPairWithNullCoordinatorStaysGeneric() {
        composeRule.setContent {
            PhoneTheme {
                PairingSuccessMark(coordinator = null)
            }
        }
        composeRule.onNodeWithTag("journalMarkCard")
            .assertContentDescriptionEquals("your journal, not set up yet")
        composeRule.onNodeWithText("your", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText("journal", useUnmergedTree = true).assertExists()
    }

    @Test
    fun unpairedPillRendersGenericMarkAndAccessibleName() {
        composeRule.setContent {
            PhoneTheme {
                PhoneJournalMarkPill(
                    onClick = {},
                    paired = false,
                    presentation = JournalMarkPresentation.Generic,
                )
            }
        }
        composeRule.onNodeWithTag("journalMarkPill")
            .assertContentDescriptionEquals(JournalMarkTokens.GENERIC_ACCESSIBLE_NAME)
        composeRule.onNodeWithText("your", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText("journal", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithText("connect a journal", useUnmergedTree = true).assertDoesNotExist()
    }

    private fun assertImagesDiffer(image1: ImageBitmap, image2: ImageBitmap) {
        val bmp1 = image1.asAndroidBitmap()
        val bmp2 = image2.asAndroidBitmap()
        assertEquals(bmp1.width, bmp2.width)
        assertEquals(bmp1.height, bmp2.height)

        var hasPixelDifference = false
        for (x in 0 until bmp1.width) {
            for (y in 0 until bmp1.height) {
                if (bmp1.getPixel(x, y) != bmp2.getPixel(x, y)) {
                    hasPixelDifference = true
                    break
                }
            }
            if (hasPixelDifference) break
        }
        assertTrue("Rendered bitmaps must differ in pixel content", hasPixelDifference)
    }
}
