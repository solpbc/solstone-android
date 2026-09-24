// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.formfactor.shared

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Space
import android.widget.TextView
import app.solstone.observer.harness.AsyncLoad
import app.solstone.observer.harness.HarnessController
import app.solstone.observer.harness.HarnessEvidenceSegment
import app.solstone.observer.harness.LoadState
import app.solstone.observer.harness.PairLinkDispatchResult
import app.solstone.observer.harness.plStatusText
import app.solstone.observer.harness.syncNowMessage

class ObserverHarnessUi(
    private val context: Context,
    private val controller: HarnessController,
    private val permissionRequester: () -> Unit,
    private val asyncLoad: AsyncLoad,
    private val previewHeightPx: Int,
    private val qrBackend: QrBackend,
    private val qrThreadLabel: String,
    private val onEvidenceLoaded: () -> Unit = {},
    private val onSyncLoaded: () -> Unit = {},
    private val markAccessoryFactory: ((context: Context, onConfirmed: () -> Unit) -> View)? = null,
    /**
     * How an OWNER-facing control and an owner-facing line of text are dressed.
     *
     * ⚠ Supplied by the form factor, because brand lives in `formfactor/phone` and this module sits
     * below it. ⛔ Applied only in owner-task mode: the operator menu is instrumentation and keeps
     * the platform's own look, which is the same seam `dismiss` already draws for the screen margin
     * and the drawn back button.
     *
     * 🔴 These MUTATE the widget rather than replacing it. `connect a journal` is the app's main
     * call to action, and it was rendering as a grey square-cornered `android.widget.Button` with
     * system-font body text beside the shell's branded surfaces. ⛔ Do not swap the widget class:
     * the runtime tests match on `view is Button`.
     */
    private val ownerButtonStyle: ((Button) -> Unit)? = null,
    private val ownerTextStyle: ((TextView) -> Unit)? = null,
) {
    private val container = FrameLayout(context).apply { applySystemBarInsetPadding() }
    private var inSubmenu = false

    /**
     * Where leaving a screen goes, when this harness was opened for **one owner task**.
     *
     * 🔴 **Five of the seven screens below are operator instrumentation** — a permission probe, a
     * transport probe, raw start/stop, queue counters, and an evidence browser that prints spool
     * paths, wire segment ids and SHA-256s. ⛔ **None of them is an owner surface.** But the phone
     * shell opens this harness for two tasks that *are* (`connect a journal`, `manage local
     * storage`) and an App Link opens it for a third, and back out of any of them used to land the
     * owner on the menu that lists the other five.
     *
     * When this is set, there is no menu: the task is the whole surface and leaving it returns the
     * owner to the shell. ⛔ Nothing may route to [showMenu] while it is set.
     */
    private var dismiss: (() -> Unit)? = null
    private var permissionRows: TextView? = null
    private var plStatusRows: TextView? = null
    private var plStatusOutstanding: Boolean = false

    /** Whether the camera-off screen is what the owner is looking at right now. */
    var showsCameraOff: Boolean = false
        private set

    fun view(): View {
        // ⛔ Not unconditional: in single-task mode the caller routes to the one screen the owner
        // asked for, and building the menu first would put it on screen for a frame.
        if (dismiss == null) showMenu()
        return container
    }

    /** Open this harness for one task only; [action] is where leaving that task goes. */
    fun dismissTo(action: () -> Unit) {
        dismiss = action
    }

    /**
     * The gated way in to the scanner, installed by the host activity.
     *
     * 🔴 The menu button called [showScanPairQr] directly, which builds a camera preview — so on
     * this one entry the camera opened before anything asked for it, the same shape as the
     * owner-facing defect the shell's own entry was fixed for. ⛔ Leave this null only where there
     * is no activity to ask.
     */
    var scanEntry: (() -> Unit)? = null

    fun showMenu() {
        setScreen(isMenu = true) {
            button("Permissions") { showPermissions() }
            button("Scan pair QR") { (scanEntry ?: ::showScanPairQr)() }
            button("PL status probe") { showPlStatusProbe() }
            button("Start/stop intake") { showStartStop() }
            button("Status + queue/sync") { showStatusQueueSync() }
            button("Evidence + export") { showEvidenceExport() }
        }
    }

    fun showPermissions() {
        setScreen {
            permissionRows = text("")
            val requestNote = text("")
            button("Request permissions") {
                permissionRequester()
                requestNote.text = "Requested"
            }
            backButton()
        }
        refreshPermissions()
    }

    fun refreshPermissions() {
        val rows = permissionRows ?: return
        rows.text = permissionRowsText(controller.refreshPermissions())
    }

    /**
     * The scanner is a VIEWFINDER, so the preview is the screen and the status line sits on it.
     *
     * 🔴 **It used to be a letterboxed box.** `previewHeightPx` is a fixed pixel height (480 on the
     * phone — about a fifth of a 2340px display), inset another 16dp on every side by the
     * owner-task margin, with the status stacked above it as a separate row and two thirds of the
     * screen left empty cream. On the app's main call to action.
     *
     * ✅ iOS already ships the answer (`QRScannerView`): a full-bleed preview with the caption
     * overlaid near the bottom, and Android already ships that caption's exact words.
     *
     * ⚠ **This is the one screen that cannot use [setScreen].** That path wraps every screen in
     * `ScrollView(LinearLayout VERTICAL)`, which can neither fill nor stack — a viewfinder needs a
     * z-stack and has nothing to scroll. ⛔ Do not "fix" this by giving the preview a large fixed
     * height inside the scrolling column; that is the defect with a bigger number.
     *
     * ⚠ `previewHeightPx` is still the constructor contract for the watch and for tests; it is
     * only the phone's owner-facing scanner that stops treating it as a height.
     */
    fun showScanPairQr() {
        setFullBleedScreen {
            // ⛔ Black ground, not the shell's cream. The preview aspect-FITS a 4:3 sensor into a
            // ~19.5:9 screen, so it letterboxes — and a letterbox over cream reads as a broken
            // layout, while a letterbox over black reads as a viewfinder, which is what every
            // camera surface on the platform does. It is also what makes the white caption legible:
            // the caption sits on a live scene and cannot rely on contrast with a known ground.
            setBackgroundColor(android.graphics.Color.BLACK)
            val onStatusHolder = arrayOfNulls<TextView>(1)
            val onStatus = { message: String ->
                onStatusHolder[0]?.text = message
                if (message == "paired") showPaired()
            }
            val preview = when (qrBackend) {
                QrBackend.Camera2 -> Camera2QrPreviewView(context, controller, qrThreadLabel, onStatus)
                QrBackend.Legacy -> LegacyQrPreviewView(context, controller, qrThreadLabel, onStatus)
            }
            addView(
                preview,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
            // ⛔ A MATCH_PARENT reticle added after Back would be the hit at Back's centre and strand
            // the operator. Insertion order is a hit-test constraint.
            addView(
                ScanPairReticleView(context),
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
            // Owner-reachable via the shell's `connect a journal`, so it says what to do rather
            // than that the harness is ready. Adopted from the string iOS already ships for this
            // screen (`QRScannerView`), not authored here.
            val status = TextView(context).apply {
                text = "point your phone at the code"
                setTextColor(android.graphics.Color.WHITE)
                textSize = 16f
                // ⛔ The caption sits on a live camera, so it cannot rely on contrast with a known
                // ground. The shadow is what keeps it readable over a bright scene.
                setShadowLayer(6f, 0f, 1f, android.graphics.Color.BLACK)
                // Centred, like iOS's. A caption over a viewfinder belongs under what the owner is
                // aiming; left-aligned it reads as a label on a page.
                gravity = android.view.Gravity.CENTER_HORIZONTAL
                val pad = (24 * resources.displayMetrics.density).toInt()
                setPadding(pad, pad, pad, pad)
            }
            onStatusHolder[0] = status
            addView(
                status,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { gravity = android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL },
            )
            // ⛔ The operator path still owes a drawn `Back`, and this screen nearly shipped without
            // one. `ObserverHarnessChromeInvariantTest` walks the operator menu, enters every screen
            // and clicks `Back` to get out — so dropping it here does not merely look untidy, it
            // strands the operator on a full-screen camera with only the system gesture. The owner
            // task keeps no drawn back (the platform bible gives Android's back to the gesture),
            // which is the same `dismiss` seam [backButton] already draws.
            if (dismiss == null) {
                addView(
                    Button(context).apply {
                        text = "Back"
                        isAllCaps = false
                        setOnClickListener { leave() }
                    },
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply { gravity = android.view.Gravity.TOP or android.view.Gravity.START },
                )
            }
        }
    }

    /**
     * The scanner's stand-in when the owner has declined the camera.
     *
     * ⚠ A grant made in system Settings has no in-app callback; the activity watches for the owner
     * coming back while [showsCameraOff] is set and moves on to the scanner. [beforeSettings] runs
     * first so the caller can record that this grant is for scanning, not for a camera source.
     */
    fun showCameraOff(beforeSettings: () -> Unit = {}) {
        setScreen {
            text(CAMERA_OFF_FOR_SCAN)
            button(OPEN_ANDROID_SETTINGS) {
                beforeSettings()
                context.startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.fromParts("package", context.packageName, null),
                    ),
                )
            }
            backButton()
        }
        showsCameraOff = true
    }

    /**
     * The screen a link-driven pair (an App Link tap, or this same activity launched via an
     * explicit component with the same intent shape) lands on before a paired-only surface
     * exists to show anything richer.
     *
     * 🔴 **Used to be a bare status line with no title and no way off it but the system Back
     * gesture** — the four-beat needs-attention pattern's verdict and fix were both missing.
     * [title] is the one constant heading for every outcome this dispatch can produce, so it
     * never has to guess a verdict word that fits both "still connected elsewhere" and
     * "couldn't reach your journal." The message already names the diagnosed condition
     * ([pairLinkDispatchText]); the one thing this adds is a button — `try again`, re-driving
     * the same link, where [isRetryableSameLink] says that can honestly work, or `done`, off
     * the screen, where it can't.
     */
    fun showPairLink(uri: String?) {
        setScreen {
            val title = text("connecting to your journal").apply {
                setTypeface(Typeface.defaultFromStyle(Typeface.BOLD))
            }
            val status = text("pairing…")
            backButton()
            fun renderTerminal(message: String, retryable: Boolean) {
                status.text = message
                if (retryable) {
                    button("try again") { showPairLink(uri) }
                } else {
                    button("done") { leave() }
                }
            }
            asyncLoad.load({ controller.dispatchPairLink(uri) }) { state ->
                when (state) {
                    LoadState.Loading -> {
                        title.text = "connecting to your journal"
                        status.text = "pairing…"
                    }
                    is LoadState.Loaded -> when (val result = state.value) {
                        PairLinkDispatchResult.NoLink -> leave()
                        else -> {
                            val message = requireNotNull(pairLinkDispatchText(result))
                            if (result is PairLinkDispatchResult.Attempted && result.outcome.isSuccessfulPair()) {
                                showPaired()
                            } else {
                                renderTerminal(message, result.isRetryableSameLink())
                            }
                        }
                    }
                    // ⚠ The third copy of `Pairing failed`, and the reason this line reads from
                    // the renderer now: two independent tables of the same words is how the first
                    // one outlived a product name.
                    is LoadState.Failed -> renderTerminal(PAIR_DISPATCH_FAILED, retryable = true)
                }
            }
        }
    }

    fun showPlStatusProbe() {
        setScreen {
            plStatusRows = text("")
            button("Probe") { loadPlStatus() }
            backButton()
            loadPlStatus()
        }
    }

    private fun loadPlStatus() {
        if (plStatusOutstanding) {
            plStatusRows?.text = "checking your journal…"
            return
        }
        plStatusOutstanding = true
        asyncLoad.load({ controller.probePlStatus() }) { state ->
            when (state) {
                LoadState.Loading ->
                    plStatusRows?.text = "checking your journal…"
                is LoadState.Loaded -> {
                    plStatusOutstanding = false
                    plStatusRows?.text = plStatusText(state.value)
                }
                is LoadState.Failed -> {
                    plStatusOutstanding = false
                    plStatusRows?.text = "couldn't check your journal"
                }
            }
        }
    }

    fun showStartStop() {
        setScreen {
            val status = text(controller.diagnostics().display)
            button("Start") {
                val started = controller.start()
                status.text = if (started) controller.diagnostics().display else "Start refused"
            }
            button("Stop") {
                asyncLoad.load(
                    {
                        controller.stop()
                        controller.diagnostics().display
                    },
                ) { state ->
                    status.text = when (state) {
                        LoadState.Loading -> "Stopping..."
                        is LoadState.Loaded -> state.value
                        is LoadState.Failed -> "Stop failed"
                    }
                }
            }
            backButton()
        }
    }

    fun showStatusQueueSync() {
        setScreen {
            val content = column()
            val syncMessage = text("")
            button("Refresh") { loadStatus(content) }
            button("Sync now") {
                syncMessage.text = syncNowMessage(controller.syncNow())
                loadStatus(content)
            }
            backButton()
            loadStatus(content)
        }
    }

    fun showEvidenceExport() {
        setScreen {
            val content = column()
            backButton()
            loadEvidence(content)
        }
    }

    private fun loadEvidence(content: LinearLayout) {
        asyncLoad.load({ controller.listEvidence() }) { state ->
            content.removeAllViews()
            when (state) {
                LoadState.Loading -> content.text("Loading...")
                is LoadState.Loaded -> {
                    if (state.value.isEmpty()) {
                        content.text("No sealed segments")
                    } else {
                        state.value.forEach { content.segmentView(it) }
                    }
                    onEvidenceLoaded()
                }
                is LoadState.Failed -> {
                    content.text("Couldn't load evidence")
                    onEvidenceLoaded()
                }
            }
        }
    }

    private fun LinearLayout.segmentView(segment: HarnessEvidenceSegment) {
        val localPath = "${segment.day}/${segment.stream}/${segment.dirSegment}"
        text(
            listOf(
                localPath,
                if (segment.dirSegment != segment.segment) "wire=${segment.segment}" else null,
                "state=${segment.state}",
                "bytes=${segment.byteSize}",
            ).filterNotNull().joinToString("\n"),
        )
        segment.files.forEach { file ->
            text("${file.sourceId} ${file.name} ${file.mediaType} ${file.byteSize} ${file.sha256}")
        }
        text("Bundle: spool/$localPath")
        button("Export") {
            val result = controller.exportSegment(segment)
            text("Exported ${result.copiedFileCount}: ${result.destinationPath}")
        }
    }

    private fun loadStatus(content: LinearLayout) {
        asyncLoad.load({ controller.syncState() }) { state ->
            content.removeAllViews()
            when (state) {
                LoadState.Loading -> content.text("Loading...")
                is LoadState.Loaded -> {
                    val sync = state.value
                    content.text(
                        listOf(
                            controller.diagnostics().display,
                            "Pending: ${sync.pendingCount}",
                            "Last success: ${sync.lastSuccessAt ?: "none"}",
                            "Last failure: ${sync.lastFailureAt ?: "none"}",
                        ).joinToString("\n"),
                    )
                    onSyncLoaded()
                }
                is LoadState.Failed -> {
                    content.text("Couldn't load status")
                    onSyncLoaded()
                }
            }
        }
    }

    fun handleBack(): Boolean {
        dismiss?.let {
            it()
            return true
        }
        if (!inSubmenu) return false
        showMenu()
        return true
    }

    private fun leave() {
        dismiss?.invoke() ?: showMenu()
    }

    /**
     * Fill a pairing screen's success slot: the journal mark, then the one way on.
     *
     * 🔴 **`done` exists because single-task mode draws no `Back`.** A successful pair used to end
     * on the word `paired` and the mark with no control anywhere on screen, so the only way on was
     * a system gesture the screen never mentioned. It leaves exactly as back does: to the shell
     * when this harness was opened for one task, to the menu otherwise.
     */
    /**
     * The paired state REPLACES the screen. ⛔ It must not merely clear a column inside it.
     *
     * 🔴 **This used to `removeAllViews()` on a nested column while the camera preview sat beside
     * that column as its sibling, so the preview was never detached — and
     * `Camera2QrPreviewView` releases the camera from `onSurfaceTextureDestroyed`, which only
     * fires on detach.** The owner therefore finished pairing and sat on a confirmation screen
     * with a live camera behind it and the system's camera-in-use indicator still lit.
     *
     * ⚠ **The observable is the camera being RELEASED, not the preview being invisible.** Covering
     * or hiding the preview would look identical to an owner reading this code and identical in a
     * screenshot, and would leave the privacy indicator burning. Replacing the screen is what
     * detaches the `TextureView`, and the detach is what closes the device.
     *
     * ✅ Safe from `onStatus`: `Camera2QrPreviewView` delivers status through `post {}`, so this
     * runs on the main thread.
     */
    private fun showPaired() {
        setScreen {
            val offerExit: () -> Unit = {
                // The mark accessory ends on a Compose Text (confirmed/mismatched line); `done`
                // is a View sibling this harness adds after it. Without a deliberate gap the two
                // sit back to back, because neither side owns the other's bottom edge.
                addView(
                    Space(context),
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        (DONE_GAP_DP * resources.displayMetrics.density).toInt(),
                    ),
                )
                button("done") { leave() }
                Unit
            }
            markAccessoryFactory?.invoke(context, offerExit)?.let { addView(it) } ?: offerExit()
        }
    }

    /**
     * A screen that fills the container and stacks, for the viewfinder. Same lifecycle bookkeeping
     * as [setScreen] — ⛔ including `container.removeAllViews()`, which is what detaches a preview
     * and releases the camera when the next screen replaces this one.
     */
    private fun setFullBleedScreen(build: FrameLayout.() -> Unit) {
        inSubmenu = true
        permissionRows = null
        plStatusRows = null
        showsCameraOff = false
        container.removeAllViews()
        container.addView(
            FrameLayout(context).apply { build() },
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
    }

    private fun setScreen(isMenu: Boolean = false, build: LinearLayout.() -> Unit) {
        inSubmenu = !isMenu
        permissionRows = null
        plStatusRows = null
        showsCameraOff = false
        container.removeAllViews()
        container.addView(scroll(build))
    }

    private fun scroll(build: LinearLayout.() -> Unit): ScrollView {
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            // ⚠ Only when the owner is the reader. These screens were laid out as instrumentation,
            // with text hard against the display edge; the shell that pushes an owner task here
            // keeps a margin, and its screens should not lose it on the way in.
            if (dismiss != null) {
                val margin = (OWNER_TASK_MARGIN_DP * resources.displayMetrics.density).toInt()
                setPadding(margin, margin, margin, margin)
            }
            build()
        }
        return ScrollView(context).apply {
            addView(layout)
        }
    }

    private fun LinearLayout.text(value: String): TextView =
        TextView(context).also {
            it.text = value
            if (dismiss != null) ownerTextStyle?.invoke(it)
            addView(it)
        }


    private fun LinearLayout.column(): LinearLayout =
        LinearLayout(context).also {
            it.orientation = LinearLayout.VERTICAL
            addView(it)
        }

    private fun LinearLayout.button(label: String, onClick: () -> Unit): Button =
        Button(context).also {
            it.text = label
            if (dismiss != null) ownerButtonStyle?.invoke(it)
            // 🔴 The platform button style sets `textAllCaps`, so every label on these screens
            // rendered as `USE 4 GB (YOUR LIMIT NOW)` — shouting, in an app whose entire register is
            // lowercase. ⚠ Invisible in source and invisible to a string test: the strings were
            // already lowercase and the widget uppercased them at draw time. Only looking at the
            // screen finds this one.
            it.isAllCaps = false
            it.setOnClickListener { onClick() }
            addView(it)
        }

    private fun LinearLayout.backButton() {
        // ⛔ No in-screen back in single-task mode. The platform bible gives Android's back to the
        // system gesture, and a drawn `Back` button is this harness's own idiom leaking onto a
        // surface the shell pushed.
        if (dismiss != null) return
        button("Back") { leave() }
    }

    private companion object {
        /** The phone shell's content margin; this module cannot see that constant. */
        const val OWNER_TASK_MARGIN_DP = 16

        /** Between the mark accessory's last line and the `done` button that follows it. */
        const val DONE_GAP_DP = 24
    }
}
