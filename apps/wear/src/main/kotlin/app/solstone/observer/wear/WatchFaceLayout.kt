// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc
package app.solstone.observer.wear

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.*

/**
 * The quiet face: the sun, an attention pill only when something needs the owner, and one
 * control. Everything else sits in one panel below the fold. Text only sits on a solid underlay.
 * The face is decorative; session truth and actions come from the session controller.
 */
class WatchFaceLayout(context: Context, private val onAction: () -> Unit) : FrameLayout(context) {
    private val sky = SunArcView(context)
    private val scroll = ScrollView(context).apply {
        isFillViewport = false
        clipToPadding = false
        isFocusable = true
        isVerticalScrollBarEnabled = true
    }
    private val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val overview = FrameLayout(context)

    private val pillDot = View(context).apply {
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(ALERT_DOT) }
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val pillText = TextView(context).apply {
        textSize = 13f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(ALERT)
        gravity = Gravity.CENTER
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }
    private val pill = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        minimumHeight = dp(48)
        setPadding(dp(14), dp(6), dp(14), dp(6))
        background = rounded(UNDERLAY, 24f)
        isClickable = true
        isFocusable = true
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        addView(pillDot, LinearLayout.LayoutParams(dp(7), dp(7)).apply { marginEnd = dp(7) })
        addView(pillText, LinearLayout.LayoutParams(-2, -2))
        setOnClickListener { openDetails() }
    }

    private val panel = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(14), dp(12), dp(14), dp(14))
        background = rounded(UNDERLAY, 22f)
    }
    private val header = text(30f, CREAM).apply { typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }
    private val headerSub = text(13f, CALM)
    private val headerBox = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        isFocusable = true
        addView(header); addView(headerSub)
    }
    private val rows = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val nextStep = text(13f, CALM)
    private val detailAction = Button(context).apply {
        isAllCaps = false
        textSize = 14f
        setTextColor(CREAM)
        background = rounded(CONTROL_QUIET, 24f).apply { setStroke(dp(1), HAIRLINE) }
        setPadding(dp(12), dp(4), dp(12), dp(4))
        minHeight = dp(48)
        minimumHeight = dp(48)
        visibility = View.GONE
    }
    private val notes = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    private val action = Button(context).apply {
        isAllCaps = false
        textSize = 16f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        minHeight = dp(48)
        minimumHeight = dp(48)
        setPadding(dp(8), dp(4), dp(8), dp(4))
        stateListAnimator = null
        setOnClickListener { onAction() }
    }
    private var ambient = false
    private var style: WatchControlStyle? = null

    init {
        setBackgroundColor(Color.BLACK)
        addView(sky, LayoutParams(-1, -1))
        addView(scroll, LayoutParams(-1, -1))
        scroll.addView(column, FrameLayout.LayoutParams(-1, -2))
        column.addView(overview, LinearLayout.LayoutParams(-1, 0))
        overview.addView(pill, LayoutParams(-2, -2, Gravity.CENTER))
        panel.addView(headerBox, LinearLayout.LayoutParams(-1, -2))
        panel.addView(rule(), ruleParams())
        panel.addView(rows, LinearLayout.LayoutParams(-1, -2))
        panel.addView(nextStep, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        panel.addView(detailAction, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        panel.addView(rule(), ruleParams())
        panel.addView(notes, LinearLayout.LayoutParams(-1, -2))
        column.addView(panel, LinearLayout.LayoutParams(-1, -2))
        addView(action, LayoutParams(-1, dp(48), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        scroll.setOnGenericMotionListener { _, event ->
            if (event.action == MotionEvent.ACTION_SCROLL && event.isFromSource(InputDevice.SOURCE_ROTARY_ENCODER)) {
                val amount = -event.getAxisValue(MotionEvent.AXIS_SCROLL) * ViewConfiguration.get(context).scaledVerticalScrollFactor
                scroll.scrollBy(0, amount.toInt())
                true
            } else false
        }
        scroll.requestFocus()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        val inset = (h * .10f).toInt()
        val controlHeight = maxOf(dp(48), (dp(24) * resources.configuration.fontScale).toInt() + dp(12))
        // A round screen is narrowest where the panel's corners pass; keep text inside the circle.
        val side = (w * .11f).toInt()
        (action.layoutParams as LayoutParams).apply {
            width = (w * .60f).toInt(); height = controlHeight; bottomMargin = inset
        }
        (scroll.layoutParams as LayoutParams).apply { bottomMargin = inset + controlHeight + dp(8) }
        (overview.layoutParams as LinearLayout.LayoutParams).apply { height = h - inset - controlHeight - dp(8) }
        (pill.layoutParams as LayoutParams).apply { width = LayoutParams.WRAP_CONTENT; gravity = Gravity.CENTER or Gravity.BOTTOM; bottomMargin = dp(10) }
        pillText.maxWidth = (w * .62f).toInt()
        (panel.layoutParams as LinearLayout.LayoutParams).apply { setMargins(side, dp(4), side, (h * .14f).toInt()) }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    internal fun show(model: WatchFaceModel) {
        if (style != model.controlStyle) {
            style = model.controlStyle
            when (model.controlStyle) {
                WatchControlStyle.START, WatchControlStyle.DETAILS -> {
                    action.background = rounded(ORANGE, 28f); action.setTextColor(ORANGE_INK)
                }
                WatchControlStyle.STOP -> {
                    action.background = rounded(CONTROL_QUIET, 28f).apply { setStroke(dp(1), HAIRLINE) }
                    action.setTextColor(CREAM)
                }
            }
        }
        setIfChanged(action, model.controlLabel)
        action.contentDescription = model.controlLabel
        action.stateDescription = model.headerSpoken
        action.tooltipText = null

        val pillVisible = !ambient && model.pill != null
        pill.visibility = if (pillVisible) View.VISIBLE else View.GONE
        if (model.pill != null) {
            setIfChanged(pillText, model.pill)
            pill.contentDescription = model.pill
        }

        setIfChanged(header, model.header)
        header.textSize = if (model.headerSub != null) 30f else 18f
        headerSub.visibility = if (model.headerSub == null) View.GONE else View.VISIBLE
        model.headerSub?.let { setIfChanged(headerSub, it) }
        headerBox.contentDescription = model.headerSpoken

        if (rows.childCount != model.rows.size) {
            rows.removeAllViews()
            model.rows.forEach { _ -> rows.addView(row(), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(3) }) }
        }
        model.rows.forEachIndexed { i, (label, value) ->
            val line = rows.getChildAt(i) as LinearLayout
            setIfChanged(line.getChildAt(0) as TextView, label)
            setIfChanged(line.getChildAt(1) as TextView, value)
            line.contentDescription = "$label: $value"
        }

        nextStep.visibility = if (model.nextStep == null) View.GONE else View.VISIBLE
        model.nextStep?.let { setIfChanged(nextStep, it) }

        if (notes.childCount != model.notes.size) {
            notes.removeAllViews()
            model.notes.forEachIndexed { i, _ ->
                notes.addView(text(12f, CALM), LinearLayout.LayoutParams(-1, -2).apply { if (i > 0) topMargin = dp(6) })
            }
        }
        model.notes.forEachIndexed { i, note -> setIfChanged(notes.getChildAt(i) as TextView, note) }
    }

    fun setAmbient(value: Boolean, lowBit: Boolean = false, burnIn: Boolean = false) {
        sky.lowBitAmbient = lowBit
        sky.burnInProtection = burnIn
        ambient = value
        sky.ambient = value
        updateAmbient()
        action.visibility = if (value) View.GONE else View.VISIBLE
        scroll.visibility = if (value) View.GONE else View.VISIBLE
    }
    fun openDetails() {
        scroll.smoothScrollTo(0, overview.height)
    }
    fun setDetailAction(label: String?, onClick: (() -> Unit)? = null) {
        detailAction.visibility = if (label == null || onClick == null) View.GONE else View.VISIBLE
        if (label != null) setIfChanged(detailAction, label)
        detailAction.setOnClickListener { onClick?.invoke() }
    }
    fun updateAmbient() {
        contentDescription = if (ambient) android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date()) else null
        sky.invalidate()
    }

    private fun row() = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        isFocusable = true
        addView(text(13f, CALM).apply { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(text(13f, CREAM).apply {
            gravity = Gravity.END
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            fontFeatureSettings = "tnum"
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
    }
    private fun rule() = View(context).apply { setBackgroundColor(HAIRLINE); importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    private fun ruleParams() = LinearLayout.LayoutParams(-1, dp(1)).apply { topMargin = dp(9); bottomMargin = dp(7) }
    private fun text(size: Float, color: Int) = TextView(context).apply { textSize = size; setTextColor(color) }
    private fun setIfChanged(view: TextView, value: String) { if (view.text.toString() != value) view.text = value }
    private fun dp(value: Int) = kotlin.math.ceil(value * resources.displayMetrics.density).toInt()
    private fun rounded(color: Int, radius: Float) = GradientDrawable().apply {
        setColor(color); cornerRadius = radius * resources.displayMetrics.density
    }

    private companion object {
        // The watch face's usage tokens (wrist up), shared with the Apple Watch app.
        const val CREAM = 0xfff4eee4.toInt()
        const val CALM = 0xffd9d9e1.toInt()
        const val ALERT = 0xfffecec7.toInt()
        const val ALERT_DOT = 0xffff6b5e.toInt()
        const val UNDERLAY = 0xf22e241c.toInt()
        const val CONTROL_QUIET = 0xff2e241c.toInt()
        const val HAIRLINE = 0x47d9d9e1
        const val ORANGE = 0xffe8913a.toInt()
        const val ORANGE_INK = 0xff1e1610.toInt()
    }
}
