// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc
package app.solstone.observer.wear

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.*

/** The face is decorative; session truth and actions come from the session controller. */
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
    private val reason = TextView(context).apply {
        textSize = 13f
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        setPadding(dp(12), dp(4), dp(12), dp(4))
        minHeight = dp(48)
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        background = rounded(0xdd202020.toInt(), 24f)
        setOnClickListener { openDetails() }
    }
    private val details = TextView(context).apply {
        textSize = 14f
        setTextColor(0xffdddddd.toInt())
        setPadding(dp(16), dp(16), dp(16), dp(24))
        background = rounded(0xdd181818.toInt(), 18f)
    }
    private val detailAction = Button(context).apply {
        isAllCaps = false
        textSize = 14f
        minHeight = dp(48)
        minimumHeight = dp(48)
        visibility = View.GONE
    }
    private val action = Button(context).apply {
        isAllCaps = false
        textSize = 16f
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(Color.BLACK)
        background = rounded(0xffffcc33.toInt(), 28f)
        minHeight = dp(48)
        minimumHeight = dp(48)
        setPadding(dp(8), dp(4), dp(8), dp(4))
        setOnClickListener { onAction() }
    }
    private var ambient = false
    init {
        setBackgroundColor(Color.BLACK)
        addView(sky, LayoutParams(-1, -1))
        addView(scroll, LayoutParams(-1, -1))
        scroll.addView(column, FrameLayout.LayoutParams(-1, -2))
        column.addView(overview, LinearLayout.LayoutParams(-1, 0))
        overview.addView(reason, LayoutParams(-2, -2, Gravity.CENTER))
        column.addView(details, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(16), dp(8), dp(16), dp(40)) })
        column.addView(detailAction, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(24), 0, dp(24), dp(40)) })
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
        (action.layoutParams as LayoutParams).apply {
            width = (w * .60f).toInt(); height = controlHeight; bottomMargin = inset
        }
        (scroll.layoutParams as LayoutParams).apply { bottomMargin = inset + controlHeight + dp(8) }
        (overview.layoutParams as LinearLayout.LayoutParams).apply { height = h - inset - controlHeight - dp(8) }
        (reason.layoutParams as LayoutParams).apply {
            width = (w * .72f).toInt(); gravity = Gravity.CENTER; topMargin = dp(12)
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
    fun show(actionLabel: String, state: String, attention: String, detailText: String) {
        if (action.text.toString() != actionLabel) action.text = actionLabel
        action.contentDescription = "$state. $actionLabel"
        if (reason.text.toString() != attention) reason.text = attention
        reason.visibility = if (ambient || attention.isBlank()) View.GONE else View.VISIBLE
        if (details.text.toString() != detailText) details.text = detailText
        details.contentDescription = "$state. $detailText"
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
        if (label != null) detailAction.text = label
        detailAction.setOnClickListener { onClick?.invoke() }
    }
    fun updateAmbient() {
        contentDescription = if (ambient) android.text.format.DateFormat.getTimeFormat(context).format(java.util.Date()) else null
        sky.invalidate()
    }
    private fun dp(value: Int) = kotlin.math.ceil(value * resources.displayMetrics.density).toInt()
    private fun rounded(color: Int, radius: Float) = GradientDrawable().apply {
        setColor(color); cornerRadius = radius * resources.displayMetrics.density
    }
}
