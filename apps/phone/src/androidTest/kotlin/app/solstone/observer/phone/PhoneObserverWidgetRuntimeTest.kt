// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone

import android.Manifest
import android.content.Context
import android.os.Build
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.glance.appwidget.testing.unit.runGlanceAppWidgetUnitTest
import androidx.glance.appwidget.testing.unit.assertIsChecked
import androidx.glance.appwidget.testing.unit.assertIsNotChecked
import androidx.glance.layout.HeightModifier
import androidx.glance.layout.PaddingModifier
import androidx.glance.layout.WidthModifier
import androidx.glance.text.Text
import androidx.glance.testing.unit.hasText
import androidx.glance.unit.ColorProvider
import androidx.glance.unit.Dimension
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.solstone.core.model.ReasonCode
import app.solstone.core.model.SourceState
import app.solstone.observer.formfactor.phone.PhoneObserverWidgetColorRole
import app.solstone.observer.formfactor.phone.PhoneObserverWidgetModel
import app.solstone.observer.formfactor.phone.SolstoneColors
import app.solstone.observer.formfactor.phone.sourceLabel
import app.solstone.observer.harness.FileSourceWishStore
import app.solstone.observer.harness.SourceToggleResult
import app.solstone.observer.harness.SourceWish
import app.solstone.observer.scaffold.ObserverHarnessRuntime
import app.solstone.platform.fgs.ObserverForegroundService
import app.solstone.platform.fgs.ObserverNotification
import app.solstone.testing.validDirectPairLink
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import androidx.test.rule.GrantPermissionRule
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class PhoneObserverWidgetRuntimeTest {
    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.CAMERA,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.POST_NOTIFICATIONS,
    )

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() {
        resetObserverRuntime()
        resetPersistence(context)
    }

    @After
    fun tearDown() {
        resetObserverRuntime()
    }

    @Test
    fun glanceUnitTestEntryPointRunsOnDeviceWithoutRobolectric() = runGlanceAppWidgetUnitTest {
        provideComposable { Text("on") }
        awaitIdle()

        onNode(hasText("on")).assertExists()
    }

    @Test
    fun uninitializedRuntimeRendersOffDespitePersistedAudioWish() {
        FileSourceWishStore(context.filesDir.resolve("source-wishes")).saveAll(
            mapOf(PHONE_WIDGET_AUDIO_SOURCE_ID to SourceWish.On),
        )
        assertTrue(context.filesDir.resolve("source-wishes").readText().contains("audio\tOn"))

        val application = context.applicationContext as PhoneApplication
        ObserverHarnessRuntime.runtime = application.runtime
        assertFalse(requireNotNull(ObserverHarnessRuntime.runtime).containerIfInitialized != null)
        val model = application.widgetModel()

        assertFalse(model.audioChecked)
        assertTrue(model.needsAttention)
    }

    @Test
    fun offRenderUsesOffCallback() =
        runGlanceAppWidgetUnitTest {
            setContext(context)
            provideComposable { PhoneObserverWidgetContent(offModel()) }
            awaitIdle()

            onNode(hasText(sourceLabel(PHONE_WIDGET_AUDIO_SOURCE_ID)))
                .assertIsNotChecked()
            onNode(hasText("off")).assertExists()
        }

    @Test
    fun offWishUsesDirectForegroundServiceStart() = runGlanceAppWidgetUnitTest {
        setContext(context)
        provideComposable { PhoneObserverWidgetContent(startModel()) }
        awaitIdle()

        onNode(hasText(sourceLabel(PHONE_WIDGET_AUDIO_SOURCE_ID)))
            .assertIsNotChecked()
    }

    @Test
    fun resizeRendersRegistryOffStateAfterItChangesUnderneath() {
        val container = pairedContainerWithAudioOff()
        ObserverForegroundService.dispatchWidgetStartAccepted(PHONE_WIDGET_AUDIO_SOURCE_ID)
        waitUntil("audio source on") { audioStatus(container).state == SourceState.ON }
        waitUntil("widget model on") { application.widgetModel().audioChecked }
        val onModel = application.widgetModel()
        assertTrue(onModel.audioChecked)

        runGlanceAppWidgetUnitTest {
            setContext(context)
            setAppWidgetSize(DpSize(180.dp, 48.dp))
            provideComposable { PhoneObserverWidgetContent(application.widgetModel()) }
            awaitIdle()

            onNode(hasText(sourceLabel(PHONE_WIDGET_AUDIO_SOURCE_ID))).assertIsChecked()
        }

        assertEquals(
            SourceToggleResult.Applied,
            container.sources.setWish(PHONE_WIDGET_AUDIO_SOURCE_ID, SourceWish.Off),
        )
        waitUntil("audio source off") { audioStatus(container).state == SourceState.OFF }
        waitUntil("widget model off") { !application.widgetModel().audioChecked }
        val offModel = application.widgetModel()
        assertFalse(offModel.audioChecked)
        assertFalse(offModel.audioWishOn)

        runGlanceAppWidgetUnitTest {
            setContext(context)
            setAppWidgetSize(DpSize(240.dp, 80.dp))
            provideComposable { PhoneObserverWidgetContent(application.widgetModel()) }
            awaitIdle()

            onNode(hasText(sourceLabel(PHONE_WIDGET_AUDIO_SOURCE_ID))).assertIsNotChecked()
            onNode(hasText("off")).assertExists()
        }
    }

    @Test
    fun directWidgetStartActuatesAudioAndInAppSetWishUpdatesWidgetWithoutExplicitWidgetUpdate() {
        val container = pairedContainerWithAudioOff()
        runGlanceAppWidgetUnitTest {
            setContext(context)
            provideComposable { PhoneObserverWidgetContent(application.widgetModel()) }
            awaitIdle()

            onNode(hasText(sourceLabel(PHONE_WIDGET_AUDIO_SOURCE_ID)))
                .assertIsNotChecked()
        }

        val updates = AtomicInteger(0)
        PhoneWidgetCoordinator.onUpdateCompleteForTest = { updates.incrementAndGet() }
        try {
            val baseline = updates.get()
            ObserverForegroundService.dispatchWidgetStartAccepted(PHONE_WIDGET_AUDIO_SOURCE_ID)
            waitUntil("widget-started audio source") { audioStatus(container).state == SourceState.ON }
            waitUntil("widget start updates") { updates.get() >= baseline + 2 }
            assertTrue(application.widgetModel().audioChecked)

            val updatesBeforeInAppWish = updates.get()
            assertEquals(
                SourceToggleResult.Applied,
                container.sources.setWish(PHONE_WIDGET_AUDIO_SOURCE_ID, SourceWish.Off),
            )
            waitUntil("in-app audio source off") { audioStatus(container).state == SourceState.OFF }
            waitUntil("in-app widget update") { updates.get() > updatesBeforeInAppWish }
            assertFalse(application.widgetModel().audioChecked)
            assertFalse(application.widgetModel().audioWishOn)
        } finally {
            PhoneWidgetCoordinator.onUpdateCompleteForTest = null
        }
    }

    @Test
    fun widgetFillsItsBoundsWithoutCustomPadding() {
        val modifier = widgetRootModifier(ColorProvider(SolstoneColors.surfaceCream))

        assertTrue(modifier.any { it is WidthModifier && it.width == Dimension.Fill })
        assertTrue(modifier.any { it is HeightModifier && it.height == Dimension.Fill })
        assertFalse(modifier.any { it is PaddingModifier })
    }

    @Test
    fun widgetRadiiResolveToNamedPlatformResourcesOnApi31Plus() {
        assumeTrue(Build.VERSION.SDK_INT >= 31)

        assertEquals(
            context.resources.getDimension(android.R.dimen.system_app_widget_background_radius),
            context.resources.getDimension(R.dimen.phone_observer_widget_background_radius),
            0f,
        )
        assertEquals(
            context.resources.getDimension(android.R.dimen.system_app_widget_inner_radius),
            context.resources.getDimension(R.dimen.phone_observer_widget_inner_radius),
            0f,
        )
    }

    @Test
    fun ongoingNotificationRequestsPromotionWhenTheSystemPermitsIt() {
        val notification = ObserverNotification.ongoing(context, requestPromotion = true)

        assertTrue(NotificationCompat.isRequestPromotedOngoing(notification))
    }

    @Test
    fun notificationDecorationOnMainThreadWithInitializedContainerDoesNotThrow() {
        pairedContainerWithAudioOff()
        val error = arrayOfNulls<Throwable>(1)

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            error[0] = runCatching {
                ObserverNotification.ongoing(context, decorate = true)
            }.exceptionOrNull()
        }

        assertNull(error[0])
    }

    private fun startModel(): PhoneObserverWidgetModel =
        PhoneObserverWidgetModel(
            audioChecked = false,
            audioWishOn = false,
            stateWord = "off",
            needsAttention = true,
            reason = ReasonCode.NONE,
            pendingCount = 0,
            syncText = "connected",
            colors = setOf(
                PhoneObserverWidgetColorRole.SURFACE,
                PhoneObserverWidgetColorRole.CONTENT,
                PhoneObserverWidgetColorRole.ACTIVE,
            ),
        )

    private fun offModel(): PhoneObserverWidgetModel =
        PhoneObserverWidgetModel(
            audioChecked = false,
            audioWishOn = true,
            stateWord = "off",
            needsAttention = true,
            reason = ReasonCode.FOREGROUND_START_NOT_ALLOWED,
            pendingCount = 0,
            syncText = "not paired",
            colors = setOf(
                PhoneObserverWidgetColorRole.SURFACE,
                PhoneObserverWidgetColorRole.CONTENT,
                PhoneObserverWidgetColorRole.ATTENTION,
            ),
        )

    private val application: PhoneApplication
        get() = context.applicationContext as PhoneApplication

    private fun pairedContainerWithAudioOff() = obtainObserverContainer().also { container ->
        assertTrue(waitForRecovery(container))
        assertTrue(container.controller.onScannedPairLink(validDirectPairLink()) != null)
        assertEquals(
            SourceToggleResult.Applied,
            container.sources.setWish(PHONE_WIDGET_AUDIO_SOURCE_ID, SourceWish.Off),
        )
    }

    private fun audioStatus(container: app.solstone.observer.scaffold.ObserverAppContainer) =
        requireNotNull(container.sources.snapshot().sources.singleOrNull {
            it.sourceId == PHONE_WIDGET_AUDIO_SOURCE_ID
        })
}
