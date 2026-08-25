// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.ForcedSize
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.solstone.core.model.ReasonCode
import app.solstone.core.model.SourceState
import app.solstone.observer.harness.LoadState
import app.solstone.observer.harness.ObserverStatus
import app.solstone.observer.harness.SourceStatus
import app.solstone.observer.harness.SourceWish
import app.solstone.observer.harness.SourcesReadModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhoneShortWindowGeometryTest {
    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var requestedSize: MutableState<DpSize>
    private lateinit var screenKey: MutableState<Int>
    private var density = 0f

    @Before
    fun setUp() {
        composeRule.setContent {
            requestedSize = remember { mutableStateOf(TALL_SIZE) }
            screenKey = remember { mutableStateOf(0) }
            DeviceConfigurationOverride(
                DeviceConfigurationOverride.ForcedSize(requestedSize.value),
            ) {
                density = LocalDensity.current.density
                key(screenKey.value) {
                    PhoneObserverScreen(
                        loadState = loaded(audioOn()),
                        status = connected(),
                        onToggle = { _, _ -> },
                        onStartObserving = {},
                    )
                }
            }
        }
        composeRule.waitForIdle()
    }

    @Test
    fun compactShortWindowKeepsDeckAndPillsUnclipped() {
        val tall = render(TALL_SIZE, openStatus = false)
        val short = render(SHORT_SIZE, openStatus = false)

        assertDeckFillsRoot(tall.deck!!, "tall deck")
        assertDeckFillsRoot(short.deck!!, "short deck")
        assertSameHorizontalGeometry(tall.deck!!, short.deck!!, "deck")
        assertSameControlGeometry(tall.journalPill, short.journalPill, "journalPill", VerticalAnchor.BOTTOM)
        assertSameControlGeometry(tall.statusPill, short.statusPill, "statusPill", VerticalAnchor.TOP)
    }

    @Test
    fun compactShortWindowKeepsOpenStatusPaneUnclipped() {
        val tall = render(TALL_SIZE, openStatus = true)
        val short = render(SHORT_SIZE, openStatus = true)

        assertSameControlGeometry(tall.journalPill, short.journalPill, "journalPill", VerticalAnchor.BOTTOM)
        assertSameControlGeometry(tall.statusPill, short.statusPill, "statusPill", VerticalAnchor.TOP)
        assertSameControlGeometry(tall.statusPane!!, short.statusPane!!, "statusPane", VerticalAnchor.BOTTOM)
    }

    private fun render(size: DpSize, openStatus: Boolean): ScreenGeometry {
        composeRule.runOnIdle {
            requestedSize.value = size
            screenKey.value += 1
        }
        composeRule.waitForIdle()
        if (openStatus) {
            composeRule.onNodeWithTag("statusPill").performClick()
            composeRule.waitForIdle()
        }
        return ScreenGeometry(
            deck = if (openStatus) null else boundsFor("deck", density),
            journalPill = boundsFor("journalPill", density),
            statusPill = boundsFor("statusPill", density),
            statusPane = if (openStatus) boundsFor("statusPane", density) else null,
        )
    }

    private fun boundsFor(testTag: String, density: Float): Bounds {
        val node = composeRule
        .onNodeWithTag(testTag, useUnmergedTree = true)
        .fetchSemanticsNode()
        return node.boundsInRoot.toBounds(density, node.root().boundsInRoot.toFrame(density))
    }

    private fun SemanticsNode.root(): SemanticsNode {
        var current = this
        while (current.parent != null) current = current.parent!!
        return current
    }

    private fun assertDeckFillsRoot(bounds: Bounds, label: String) {
        assertUnclipped(bounds, label)
        assertEquals(bounds.root.left, bounds.left, TOLERANCE_DP)
        assertEquals(bounds.root.top, bounds.top, TOLERANCE_DP)
        assertEquals(bounds.root.right, bounds.right, TOLERANCE_DP)
        assertEquals(bounds.root.bottom, bounds.bottom, TOLERANCE_DP)
    }

    private fun assertSameHorizontalGeometry(tall: Bounds, short: Bounds, label: String) {
        assertEquals("$label left", tall.left - tall.root.left, short.left - short.root.left, TOLERANCE_DP)
        assertEquals("$label right", tall.root.right - tall.right, short.root.right - short.right, TOLERANCE_DP)
        assertEquals("$label width", tall.width, short.width, TOLERANCE_DP)
    }

    private fun assertSameControlGeometry(
        tall: Bounds,
        short: Bounds,
        label: String,
        verticalAnchor: VerticalAnchor,
    ) {
        assertUnclipped(tall, "tall $label")
        assertUnclipped(short, "short $label")
        assertEquals("$label width", tall.width, short.width, TOLERANCE_DP)
        assertEquals("$label height", tall.height, short.height, TOLERANCE_DP)
        assertEquals("$label center x", tall.centerX - tall.root.centerX, short.centerX - short.root.centerX, TOLERANCE_DP)
        when (verticalAnchor) {
            VerticalAnchor.TOP -> assertEquals(
                "$label top anchor",
                tall.top - tall.root.top,
                short.top - short.root.top,
                TOLERANCE_DP,
            )
            VerticalAnchor.BOTTOM -> assertEquals(
                "$label bottom anchor",
                tall.root.bottom - tall.bottom,
                short.root.bottom - short.bottom,
                TOLERANCE_DP,
            )
        }
    }

    private fun assertUnclipped(bounds: Bounds, label: String) {
        assertTrue("$label width=${bounds.width}", bounds.width > 0f)
        assertTrue("$label height=${bounds.height}", bounds.height > 0f)
        assertTrue("$label left=$bounds", bounds.left >= bounds.root.left - TOLERANCE_DP)
        assertTrue("$label top=$bounds", bounds.top >= bounds.root.top - TOLERANCE_DP)
        assertTrue("$label right=$bounds", bounds.right <= bounds.root.right + TOLERANCE_DP)
        assertTrue("$label bottom=$bounds", bounds.bottom <= bounds.root.bottom + TOLERANCE_DP)
    }

    private fun Rect.toFrame(density: Float) = Frame(
        left = left / density,
        top = top / density,
        right = right / density,
        bottom = bottom / density,
    )

    private fun Rect.toBounds(density: Float, root: Frame) = Bounds(
        left = left / density,
        top = top / density,
        right = right / density,
        bottom = bottom / density,
        root = root,
    )

    private data class ScreenGeometry(
        val deck: Bounds?,
        val journalPill: Bounds,
        val statusPill: Bounds,
        val statusPane: Bounds?,
    )

    private data class Bounds(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
        val root: Frame,
    ) {
        val width: Float get() = right - left
        val height: Float get() = bottom - top
        val centerX: Float get() = (left + right) / 2f
    }

    private data class Frame(
        val left: Float,
        val top: Float,
        val right: Float,
        val bottom: Float,
    ) {
        val centerX: Float get() = (left + right) / 2f
    }

    private enum class VerticalAnchor {
        TOP,
        BOTTOM,
    }

    private companion object {
        val TALL_SIZE = DpSize(360.dp, 800.dp)
        val SHORT_SIZE = DpSize(360.dp, 479.dp)
        const val TOLERANCE_DP = 0.5f
    }

    private fun audioOn() = SourceStatus("audio", SourceWish.On, SourceState.ON, ReasonCode.NONE)

    private fun loaded(vararg sources: SourceStatus) = LoadState.Loaded(
        SourcesReadModel(
            observer = ObserverStatus(SourceState.OFF, ReasonCode.NONE),
            sources = sources.toList(),
        ),
    )

    private fun connected() = PhoneStatusModel(
        paired = true,
        online = true,
        pendingCount = 0,
        hasContentPending = false,
    )
}
