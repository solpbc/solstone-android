// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Build
import android.text.StaticLayout
import android.text.TextPaint
import android.util.TypedValue
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.Switch
import androidx.glance.appwidget.SwitchDefaults
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartService
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.color.ColorProvider as dayNightColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import app.solstone.core.model.ReasonCode
import app.solstone.observer.formfactor.phone.MINIMUM_TOUCH_TARGET_DP
import app.solstone.observer.formfactor.phone.MarkConfirmationRoute
import app.solstone.observer.formfactor.phone.PhoneObserverWidgetColorRole
import app.solstone.observer.formfactor.phone.PhoneObserverWidgetModel
import app.solstone.observer.formfactor.phone.PhoneWidgetLineKind
import app.solstone.observer.formfactor.phone.PhoneWidgetTextMeasure
import app.solstone.observer.formfactor.phone.PhoneWidgetStartOutcome
import app.solstone.observer.formfactor.phone.SolstoneColors
import app.solstone.observer.formfactor.phone.fitPhoneWidgetLines
import app.solstone.observer.formfactor.phone.manualMarkConfirmationRoute
import app.solstone.observer.formfactor.phone.phoneStatusSnapshotOf
import app.solstone.observer.formfactor.phone.renderPhoneObserverWidget
import app.solstone.observer.formfactor.phone.sourceLabel
import app.solstone.observer.harness.HarnessBacklogStatus
import app.solstone.observer.harness.HarnessPlStatus
import app.solstone.observer.scaffold.ObserverActivity
import app.solstone.platform.fgs.ObserverForegroundService

internal const val PHONE_WIDGET_AUDIO_SOURCE_ID = "audio"

class PhoneObserverWidget : GlanceAppWidget() {
    // Exact, not the default Single: which lines fit depends on the size the owner gave it.
    override val sizeMode: SizeMode = SizeMode.Exact

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

class PhoneWidgetConfirmMarkAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val route = manualMarkConfirmationRoute(
            awaitingMarkConfirmation = phoneAwaitingMarkConfirmation(context),
            promptedConfirmationThisProcess = PhoneShellActivity.promptedConfirmationThisProcess,
        )
        val intent = if (route == MarkConfirmationRoute.CONFIRM) {
            Intent(context, ObserverActivity::class.java).apply {
                putExtra(ObserverActivity.EXTRA_CONFIRM_JOURNAL, true)
            }
        } else {
            // The widget drops what it has no room for, so a tap must reach where all of it is.
            Intent(context, PhoneShellActivity::class.java)
        }
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        context.startActivity(intent)
    }
}

/** The widget's text size. Body and header share it; the header differs by weight alone. */
internal const val PHONE_WIDGET_TEXT_SP = 14f

/**
 * The content inset. ⚠ Not the "no custom padding" that the widget build rules forbid: that rule is
 * about the BACKGROUND, which still fills the cell edge to edge ([widgetRootModifier]). Text needs
 * its own inset or the rounded corner cuts the first and last lines.
 */
internal val PHONE_WIDGET_INSET_HORIZONTAL = 16.dp
internal val PHONE_WIDGET_INSET_VERTICAL = 12.dp
private val PHONE_WIDGET_SWITCH_GAP = 8.dp

@Composable
internal fun PhoneObserverWidgetContent(model: PhoneObserverWidgetModel) {
    val context = LocalContext.current
    val size = LocalSize.current
    val surface = colorFor(PhoneObserverWidgetColorRole.SURFACE)
    val content = colorFor(PhoneObserverWidgetColorRole.CONTENT)
    val attention = colorFor(PhoneObserverWidgetColorRole.ATTENTION)
    val label = sourceLabel(PHONE_WIDGET_AUDIO_SOURCE_ID)
    val action = if (model.audioWishOn) {
        actionRunCallback<PhoneWidgetOffAction>()
    } else {
        actionStartService(
            ObserverForegroundService.widgetStartIntent(context, PHONE_WIDGET_AUDIO_SOURCE_ID),
            isForegroundService = true,
        )
    }
    val measure = WidgetTextMeasure(context, size)
    val lines = fitPhoneWidgetLines(model, label, measure.maxLines, measure)
    Box(widgetRootModifier(surface)) {
        Row(
            modifier = GlanceModifier
                .fillMaxSize()
                .padding(horizontal = PHONE_WIDGET_INSET_HORIZONTAL, vertical = PHONE_WIDGET_INSET_VERTICAL),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = GlanceModifier
                    .defaultWeight()
                    .fillMaxHeight()
                    .clickable(actionRunCallback<PhoneWidgetConfirmMarkAction>()),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                lines.forEach { line ->
                    val header = line.kind == PhoneWidgetLineKind.HEADER
                    // The header carries a fault in words and in the attention ink; the words are
                    // the indicator, the ink the second one. `audio, on` is what TalkBack reads.
                    val modifier = if (header) {
                        GlanceModifier.semantics { contentDescription = "$label, ${model.stateWord}" }
                    } else {
                        GlanceModifier
                    }
                    Text(
                        measure.displayText(line.text, line.kind),
                        modifier = modifier,
                        maxLines = line.lines,
                        style = TextStyle(
                            color = if (header && model.needsAttention) attention else content,
                            fontSize = PHONE_WIDGET_TEXT_SP.sp,
                            fontWeight = if (header) FontWeight.Medium else FontWeight.Normal,
                        ),
                    )
                }
            }
            Spacer(GlanceModifier.width(PHONE_WIDGET_SWITCH_GAP))
            // Declares the MINIMUM_TOUCH_TARGET_DP floor; One UI widget-host scaling can reduce realised bounds.
            // No visible text: the header beside it names it, and a label inside the switch was
            // squeezed to nothing at 3x1. The label stays the switch's accessible name.
            Switch(
                checked = model.audioChecked,
                onCheckedChange = action,
                modifier = GlanceModifier
                    .size(MINIMUM_TOUCH_TARGET_DP.dp)
                    .semantics { contentDescription = label },
                text = "",
                // The shell's switch accent, not a status colour: the header says the state in words.
                // Thumb and track share a colour because the host draws the track translucent.
                colors = SwitchDefaults.colors(
                    checkedThumbColor = colorFor(PhoneObserverWidgetColorRole.ACTIVE),
                    checkedTrackColor = colorFor(PhoneObserverWidgetColorRole.ACTIVE),
                    uncheckedThumbColor = colorFor(PhoneObserverWidgetColorRole.INACTIVE),
                    uncheckedTrackColor = colorFor(PhoneObserverWidgetColorRole.INACTIVE),
                ),
            )
        }
    }
}

private const val MIDDOT = " · "

/**
 * Measures the widget's text the way the host will draw it: the system font, at the owner's text
 * scale and bold-text setting, at the width the text column actually gets.
 */
private class WidgetTextMeasure(context: Context, size: DpSize) : PhoneWidgetTextMeasure {
    private val metrics = context.resources.displayMetrics
    private val weightAdjustment = if (Build.VERSION.SDK_INT >= 31) {
        context.resources.configuration.fontWeightAdjustment
            .takeUnless { it == android.content.res.Configuration.FONT_WEIGHT_ADJUSTMENT_UNDEFINED } ?: 0
    } else {
        0
    }
    private val textPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, PHONE_WIDGET_TEXT_SP, metrics)
    private val widthPx = dpToPx(
        size.width.value - 2 * PHONE_WIDGET_INSET_HORIZONTAL.value -
            PHONE_WIDGET_SWITCH_GAP.value - MINIMUM_TOUCH_TARGET_DP,
    ).coerceAtLeast(1)
    private val heightPx = dpToPx(size.height.value - 2 * PHONE_WIDGET_INSET_VERTICAL.value).coerceAtLeast(1)

    val maxLines: Int = (heightPx / layout("Ag", PhoneWidgetLineKind.SYNC).height.coerceAtLeast(1))

    override fun lineCount(text: String, kind: PhoneWidgetLineKind): Int =
        layout(displayText(text, kind), kind).lineCount

    /**
     * When a line has to wrap anyway, it wraps at its middots if that costs no extra line, and the
     * line break takes the dot's place as the separator: `confirm the mark · 1 waiting` reads
     * `confirm the mark` over `1 waiting`, never splitting the phrase or starting a line with a
     * dot. The words are unchanged; only a separator becomes a break.
     */
    fun displayText(text: String, kind: PhoneWidgetLineKind): String {
        if (MIDDOT !in text) return text
        val natural = layout(text, kind).lineCount
        if (natural == 1) return text
        val atDots = text.replace(MIDDOT, "\n")
        return if (layout(atDots, kind).lineCount <= natural) atDots else text
    }

    private fun layout(text: String, kind: PhoneWidgetLineKind): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint(kind), widthPx).build()

    private fun paint(kind: PhoneWidgetLineKind) = TextPaint(TextPaint.ANTI_ALIAS_FLAG).apply {
        textSize = textPx
        val weight = (if (kind == PhoneWidgetLineKind.HEADER) 500 else 400) + weightAdjustment
        typeface = if (Build.VERSION.SDK_INT >= 28) {
            Typeface.create(Typeface.DEFAULT, weight.coerceIn(1, 1000), false)
        } else {
            Typeface.DEFAULT
        }
    }

    private fun dpToPx(dp: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, metrics).toInt()
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
    awaitingMarkConfirmation = false,
    recoveryCompleted = false,
    audioAwaitingCustody = false,
    unresolvedAudioInterruption = false,
).status

private fun colorFor(role: PhoneObserverWidgetColorRole): ColorProvider =
    when (role) {
        PhoneObserverWidgetColorRole.SURFACE -> dayNightColorProvider(
            day = SolstoneColors.surfaceCream,
            night = SolstoneColors.darkSurface,
        )
        PhoneObserverWidgetColorRole.CONTENT -> dayNightColorProvider(
            day = SolstoneColors.surfaceDark,
            night = SolstoneColors.inkOnDark,
        )
        PhoneObserverWidgetColorRole.ACTIVE -> dayNightColorProvider(
            day = SolstoneColors.textOrangeAa,
            night = SolstoneColors.inkOnDark,
        )
        PhoneObserverWidgetColorRole.INACTIVE -> dayNightColorProvider(
            day = SolstoneColors.surfaceDark,
            night = SolstoneColors.inkFaintOnDark,
        )
        PhoneObserverWidgetColorRole.ATTENTION -> dayNightColorProvider(
            day = SolstoneColors.errorRed,
            night = SolstoneColors.errorPink,
        )
    }

internal fun emptyWidgetModel(notice: ReasonCode = ReasonCode.NONE): PhoneObserverWidgetModel =
    renderPhoneObserverWidget(
        readModel = null,
        statusModel = emptyPhoneStatus(),
        startOutcome = PhoneWidgetStartOutcome.None,
        notice = notice,
    )
