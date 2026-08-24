// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.Switch
import androidx.glance.appwidget.SwitchDefaults
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartService
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.color.ColorProvider as dayNightColorProvider
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.size
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import app.solstone.observer.formfactor.phone.MINIMUM_TOUCH_TARGET_DP
import app.solstone.observer.formfactor.phone.PhoneObserverWidgetColorRole
import app.solstone.observer.formfactor.phone.PhoneObserverWidgetModel
import app.solstone.observer.formfactor.phone.PhoneWidgetStartOutcome
import app.solstone.observer.formfactor.phone.SolstoneColors
import app.solstone.observer.formfactor.phone.phoneStatusSnapshotOf
import app.solstone.observer.formfactor.phone.renderPhoneObserverWidget
import app.solstone.observer.formfactor.phone.sourceLabel
import app.solstone.observer.harness.HarnessBacklogStatus
import app.solstone.observer.harness.HarnessPlStatus
import app.solstone.platform.fgs.ObserverForegroundService

internal const val PHONE_WIDGET_AUDIO_SOURCE_ID = "audio"

class PhoneObserverWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            val application = LocalContext.current.applicationContext as? PhoneApplication
            PhoneObserverWidgetContent(application?.widgetModel() ?: emptyWidgetModel())
        }
    }
}

class PhoneObserverWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = PhoneObserverWidget()
}

class PhoneWidgetOffAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        (context.applicationContext as? PhoneApplication)?.turnAudioOffFromWidget()
    }
}

@Composable
internal fun PhoneObserverWidgetContent(model: PhoneObserverWidgetModel) {
    val context = LocalContext.current
    val surface = colorFor(PhoneObserverWidgetColorRole.SURFACE)
    val content = colorFor(PhoneObserverWidgetColorRole.CONTENT)
    val signal = colorFor(
        if (model.needsAttention) PhoneObserverWidgetColorRole.ATTENTION else PhoneObserverWidgetColorRole.ACTIVE,
    )
    val action = if (model.audioWishOn) {
        actionRunCallback<PhoneWidgetOffAction>()
    } else {
        actionStartService(
            ObserverForegroundService.widgetStartIntent(context, PHONE_WIDGET_AUDIO_SOURCE_ID),
            isForegroundService = true,
        )
    }
    Box(widgetRootModifier(surface)) {
        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .cornerRadius(R.dimen.phone_observer_widget_inner_radius),
        ) {
            Row(GlanceModifier.fillMaxSize()) {
                Column(GlanceModifier.defaultWeight()) {
                    Text(model.stateWord, style = TextStyle(color = content))
                    Text(model.syncText, style = TextStyle(color = content))
                }
                // Declares the MINIMUM_TOUCH_TARGET_DP floor; One UI widget-host scaling can reduce realised bounds.
                Switch(
                    checked = model.audioChecked,
                    onCheckedChange = action,
                    modifier = GlanceModifier.size(MINIMUM_TOUCH_TARGET_DP.dp),
                    text = sourceLabel(PHONE_WIDGET_AUDIO_SOURCE_ID),
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = signal,
                        checkedTrackColor = signal,
                        uncheckedThumbColor = content,
                        uncheckedTrackColor = surface,
                    ),
                )
            }
        }
    }
}

internal fun widgetRootModifier(surface: ColorProvider): GlanceModifier =
    GlanceModifier
        .fillMaxSize()
        .appWidgetBackground()
        .cornerRadius(R.dimen.phone_observer_widget_background_radius)
        .background(surface)

internal fun emptyPhoneStatus() = phoneStatusSnapshotOf(
    backlog = HarnessBacklogStatus(HarnessPlStatus.NotPaired, pendingCount = 0, pendingSourceIds = emptyList()),
    registered = emptyList(),
).status

private fun colorFor(role: PhoneObserverWidgetColorRole): ColorProvider =
    when (role) {
        PhoneObserverWidgetColorRole.SURFACE -> dayNightColorProvider(
            day = SolstoneColors.surfaceCream,
            night = SolstoneColors.surfaceDark,
        )
        PhoneObserverWidgetColorRole.CONTENT -> dayNightColorProvider(
            day = SolstoneColors.surfaceDark,
            night = SolstoneColors.inkOnDark,
        )
        PhoneObserverWidgetColorRole.ACTIVE -> dayNightColorProvider(
            day = SolstoneColors.statusOnGreenLightStandard,
            night = SolstoneColors.statusOnGreenDarkStandard,
        )
        PhoneObserverWidgetColorRole.ATTENTION -> dayNightColorProvider(
            day = SolstoneColors.errorRed,
            night = SolstoneColors.errorPink,
        )
    }

internal fun emptyWidgetModel(): PhoneObserverWidgetModel =
    renderPhoneObserverWidget(
        readModel = null,
        statusModel = emptyPhoneStatus(),
        startOutcome = PhoneWidgetStartOutcome.None,
    )
