// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.phone

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.DarkMode
import androidx.compose.ui.test.DeviceConfigurationOverride
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.solstone.core.model.ReasonCode
import app.solstone.core.model.SourceState
import app.solstone.observer.harness.LoadState
import app.solstone.observer.harness.ObserverStatus
import app.solstone.observer.harness.SourceStatus
import app.solstone.observer.harness.SourceWish
import app.solstone.observer.harness.SourcesReadModel
import java.io.File
import java.io.FileOutputStream
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PhoneImportOxScreenshotTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun captureImportOxLightAndDarkScreenshots() {
        val now = 1_763_838_000_000L
        val clock = PhoneStatusNow { now }
        val dir = InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)!!
        dir.mkdirs()

        val loadedSources = LoadState.Loaded(
            SourcesReadModel(
                observer = ObserverStatus(SourceState.ON, ReasonCode.NONE),
                sources = listOf(
                    SourceStatus("audio", SourceWish.On, SourceState.ON, ReasonCode.NONE),
                    SourceStatus("location", SourceWish.On, SourceState.ON, ReasonCode.NONE),
                ),
            ),
        )

        val reviewImports = PhoneImportPaneState(
            recent = ImportRecentState.WAITING,
            rows = listOf(
                PhoneImportRow(
                    id = "rev-1",
                    name = "apple-health-export.zip",
                    kind = ImportRowKind.READY_TO_REVIEW,
                    savedAtJournal = true,
                    importId = "20261122_120000",
                    reviewKind = "body_confirmation_required",
                ),
                PhoneImportRow(
                    id = "exp-1",
                    name = "prior-health-export.zip",
                    kind = ImportRowKind.REVIEW_EXPIRED,
                    importId = "20261122_093000",
                    reviewKind = "body_confirmation_required",
                ),
                PhoneImportRow(
                    id = "att-1",
                    name = "corrupt-export.zip",
                    kind = ImportRowKind.REFUSED,
                    error = "your journal couldn't read this archive.",
                    retryable = false,
                ),
                PhoneImportRow(
                    id = "snd-1",
                    name = "journal-notes.pdf",
                    kind = ImportRowKind.SENDING,
                ),
                PhoneImportRow(
                    id = "wait-1",
                    name = "receipt-scan.jpg",
                    kind = ImportRowKind.WAITING,
                ),
            ),
            recentReceipts = listOf(
                PhoneImportRecentReceipt("r-1", "field-notes.pdf", "20261122_114800", now - 12 * 60_000L),
                PhoneImportRecentReceipt("r-2", "voice-memo.m4a", "20261122_101500", now - 105 * 60_000L),
            ),
            journalOpenable = true,
        )

        val deviceAndRecentImports = PhoneImportPaneState(
            recent = ImportRecentState.WAITING,
            rows = listOf(
                PhoneImportRow(
                    id = "att-1",
                    name = "corrupt-export.zip",
                    kind = ImportRowKind.REFUSED,
                    error = "your journal couldn't read this archive.",
                    retryable = false,
                ),
                PhoneImportRow(
                    id = "snd-1",
                    name = "journal-notes.pdf",
                    kind = ImportRowKind.SENDING,
                ),
                PhoneImportRow(
                    id = "wait-1",
                    name = "receipt-scan.jpg",
                    kind = ImportRowKind.WAITING,
                ),
            ),
            recentReceipts = listOf(
                PhoneImportRecentReceipt("r-1", "field-notes.pdf", "20261122_114800", now - 12 * 60_000L),
                PhoneImportRecentReceipt("r-2", "voice-memo.m4a", "20261122_101500", now - 105 * 60_000L),
            ),
            journalOpenable = true,
            offline = true,
        )

        val homeReviewImports = PhoneImportPaneState(
            rows = listOf(
                PhoneImportRow(
                    id = "rev-1",
                    name = "apple-health-export.zip",
                    kind = ImportRowKind.READY_TO_REVIEW,
                    savedAtJournal = true,
                    importId = "20261122_120000",
                    reviewKind = "body_confirmation_required",
                ),
            ),
            journalOpenable = true,
        )

        val homeAttentionImports = PhoneImportPaneState(
            rows = listOf(
                PhoneImportRow(
                    id = "att-1",
                    name = "corrupt-export.zip",
                    kind = ImportRowKind.REFUSED,
                    error = "your journal couldn't read this archive.",
                    retryable = false,
                ),
            ),
            journalOpenable = true,
        )

        val reviewStatus = PhoneStatusModel(
            paired = true,
            online = true,
            pendingCount = 0,
            hasContentPending = false,
            awaitingMarkConfirmation = false,
            recoveryCompleted = true,
            audioAwaitingCustody = false,
            importReviewCount = 1,
        )
        val attentionStatus = reviewStatus.copy(importReviewCount = 0, importsNeedAttention = true)

        var currentDark by mutableStateOf(false)
        var currentImports by mutableStateOf(PhoneImportPaneState())
        var shot by mutableStateOf<@Composable () -> Unit>({})

        composeRule.setContent {
            DeviceConfigurationOverride(DeviceConfigurationOverride.DarkMode(currentDark)) {
                val variant = if (currentDark) ThemeVariant.DARK_STANDARD else ThemeVariant.LIGHT_STANDARD
                CompositionLocalProvider(
                    LocalPhoneStatusNow provides clock,
                    LocalPhoneImport provides PhoneImportBinding(state = currentImports),
                ) {
                    PhoneTheme(variant) {
                        Box(
                            Modifier
                                .testTag("importOxShot")
                                .background(MaterialTheme.colorScheme.background),
                        ) {
                            shot()
                        }
                    }
                }
            }
        }

        fun capture(name: String, dark: Boolean, imports: PhoneImportPaneState, content: @Composable () -> Unit) {
            composeRule.runOnIdle {
                currentDark = dark
                currentImports = imports
                shot = content
            }
            composeRule.waitForIdle()
            val image = composeRule.onNodeWithTag("importOxShot").captureToImage()
            FileOutputStream(File(dir, name)).use { out ->
                image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, out)
            }
        }

        val expiredOnlyImports = PhoneImportPaneState(
            recent = ImportRecentState.NEEDS_YOU,
            rows = listOf(
                PhoneImportRow(
                    id = "exp-1",
                    name = "prior-health-export.zip",
                    kind = ImportRowKind.REVIEW_EXPIRED,
                    importId = "20261122_093000",
                    reviewKind = "body_confirmation_required",
                ),
            ),
            journalOpenable = true,
        )

        listOf(false to "light", true to "dark").forEach { (isDark, suffix) ->
            // 1. Home deck with active `ready to review` import tile & `1 ready to review` pill.
            capture("import-ox-home-review-$suffix.png", isDark, homeReviewImports) {
                Box(Modifier.width(412.dp).height(820.dp)) {
                    PhoneObserverScreen(
                        loadState = loadedSources,
                        status = reviewStatus,
                        onToggle = { _, _ -> },
                        onStartObserving = {},
                        onConfirmMark = {},
                        journalPaired = true,
                        captureWidthDp = 412,
                    )
                }
            }

            // 2. Home deck with `needs attention` import tile & non-green `connected` pill.
            capture("import-ox-home-attention-$suffix.png", isDark, homeAttentionImports) {
                Box(Modifier.width(412.dp).height(820.dp)) {
                    PhoneObserverScreen(
                        loadState = loadedSources,
                        status = attentionStatus,
                        onToggle = { _, _ -> },
                        onStartObserving = {},
                        onConfirmMark = {},
                        journalPaired = true,
                        captureWidthDp = 412,
                    )
                }
            }

            // 3. Import screen: `waiting for your review` (`ready to review` 1-hr Apple Health card).
            capture("import-ox-pane-review-$suffix.png", isDark, reviewImports) {
                Box(Modifier.width(412.dp).height(820.dp)) {
                    PhoneObserverScreen(
                        loadState = loadedSources,
                        status = reviewStatus,
                        onToggle = { _, _ -> },
                        onStartObserving = {},
                        onConfirmMark = {},
                        journalPaired = true,
                        initial = PhoneRouteStack.Empty.showInDetail(PhoneRoute.Import),
                        captureWidthDp = 412,
                    )
                }
            }

            // 4. Import screen: `waiting for your review` expired state (`send again to review`).
            capture("import-ox-pane-expired-$suffix.png", isDark, expiredOnlyImports) {
                Box(Modifier.width(412.dp).height(820.dp)) {
                    PhoneObserverScreen(
                        loadState = loadedSources,
                        status = attentionStatus,
                        onToggle = { _, _ -> },
                        onStartObserving = {},
                        onConfirmMark = {},
                        journalPaired = true,
                        initial = PhoneRouteStack.Empty.showInDetail(PhoneRoute.Import),
                        captureWidthDp = 412,
                    )
                }
            }

            // 5. Import screen: `on this device` (`needs attention`, `sending`, `waiting` + offline hold).
            capture("import-ox-pane-device-$suffix.png", isDark, deviceAndRecentImports) {
                Box(Modifier.width(412.dp).height(820.dp)) {
                    PhoneObserverScreen(
                        loadState = loadedSources,
                        status = attentionStatus,
                        onToggle = { _, _ -> },
                        onStartObserving = {},
                        onConfirmMark = {},
                        journalPaired = true,
                        initial = PhoneRouteStack.Empty.showInDetail(PhoneRoute.Import),
                        captureWidthDp = 412,
                    )
                }
            }

            // 6. Import screen: `recent` 24h persisted receipts & `all import history in your journal` link.
            capture(
                "import-ox-pane-recent-$suffix.png",
                isDark,
                PhoneImportPaneState(
                    recent = ImportRecentState.DELIVERED,
                    recentReceipts = deviceAndRecentImports.recentReceipts,
                    journalOpenable = true,
                ),
            ) {
                Box(Modifier.width(412.dp).height(820.dp)) {
                    PhoneObserverScreen(
                        loadState = loadedSources,
                        status = reviewStatus.copy(importReviewCount = 0),
                        onToggle = { _, _ -> },
                        onStartObserving = {},
                        onConfirmMark = {},
                        journalPaired = true,
                        initial = PhoneRouteStack.Empty.showInDetail(PhoneRoute.Import),
                        captureWidthDp = 412,
                    )
                }
            }
        }
    }
}
