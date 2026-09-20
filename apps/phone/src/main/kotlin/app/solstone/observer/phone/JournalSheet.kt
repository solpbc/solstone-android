// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.os.Message
import android.os.Build
import android.view.ViewGroup
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.solstone.core.identity.JournalMarkPresentation
import app.solstone.core.pl.browser.BrowserTerminalClass
import app.solstone.core.pl.browser.JournalBrowserLifecycle
import app.solstone.core.pl.browser.JournalBrowserLifecycleListener
import app.solstone.core.pl.browser.JournalBrowserSession
import app.solstone.observer.formfactor.phone.JournalMarkCard
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

private const val INITIAL_FAILURE =
    "couldn't reach your journal. keep the solstone app open and try again."
private const val LOST_CONNECTION =
    "lost the connection to your journal. keep the solstone app open and try again."
private const val SAVE_FAILURE = "couldn't save that. try again."
private const val FILE_SELECTION_UNAVAILABLE = "file selection isn't available in the app."
private const val LINK_UNAVAILABLE = "that link isn't available in the app."

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun JournalSheet(
    presentation: JournalMarkPresentation,
    sessionFactory: () -> JournalBrowserSession,
    onClose: () -> Unit,
    onPairingRepair: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var retryGeneration by remember { mutableIntStateOf(0) }
    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = sheetState,
        modifier = Modifier
            .padding(top = 72.dp)
            .widthIn(max = 640.dp),
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = null,
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
        sheetGesturesEnabled = false,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            JournalMarkCard(
                presentation = presentation,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onClose) { Text("close") }
        }
        // The sheet asks for no window insets so its surface can paint under the
        // navigation bar, which means the journal itself has to stop above it. Without
        // this the journal's own bottom tab bar lands under Android's navigation bar and
        // cannot be tapped at all — a tap on `search` reaches the system bar instead.
        Box(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.navigationBars)) {
            key(retryGeneration) {
                JournalBrowserPane(
                    sessionFactory = sessionFactory,
                    onRetry = { retryGeneration += 1 },
                    onPairingRepair = onPairingRepair,
                )
            }
        }
    }
}

private sealed interface BrowserPaneState {
    data object Loading : BrowserPaneState
    data class Showing(val origin: String) : BrowserPaneState
    data class Failed(val message: String) : BrowserPaneState
}

@Composable
private fun JournalBrowserPane(
    sessionFactory: () -> JournalBrowserSession,
    onRetry: () -> Unit,
    onPairingRepair: () -> Unit,
) {
    val hostView = LocalView.current
    val context = LocalContext.current
    val density = LocalDensity.current
    var state by remember { mutableStateOf<BrowserPaneState>(BrowserPaneState.Loading) }
    var committed by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    val session = remember { sessionFactory() }
    val alive = remember { AtomicBoolean(true) }

    DisposableEffect(session) {
        alive.set(true)
        val listener = JournalBrowserLifecycleListener { lifecycle ->
            if (!alive.get() || lifecycle !is JournalBrowserLifecycle.Terminal) return@JournalBrowserLifecycleListener
            if (lifecycle.reason.classification == BrowserTerminalClass.EXPLICIT_STOP) {
                return@JournalBrowserLifecycleListener
            }
            hostView.post {
                if (!alive.get()) return@post
                when (lifecycle.reason.classification) {
                    BrowserTerminalClass.UNPAIRED_FORGOTTEN,
                    BrowserTerminalClass.PAIRING_REPLACED,
                    BrowserTerminalClass.PAIRING_UNCERTAIN,
                    BrowserTerminalClass.TRUST_REFUSAL,
                    BrowserTerminalClass.IDENTITY_AUTH_REFUSAL,
                    -> onPairingRepair()
                    else -> state = BrowserPaneState.Failed(
                        if (committed) LOST_CONNECTION else INITIAL_FAILURE,
                    )
                }
            }
        }
        session.addLifecycleListener(listener)
        state = try {
            BrowserPaneState.Showing(session.start().url)
        } catch (_: Throwable) {
            BrowserPaneState.Failed(INITIAL_FAILURE)
        }
        onDispose {
            alive.set(false)
            session.removeLifecycleListener(listener)
            session.stop()
        }
    }

    when (val current = state) {
        BrowserPaneState.Loading -> Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) { CircularProgressIndicator() }
        is BrowserPaneState.Failed -> Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(current.message)
            Button(onClick = onRetry) { Text("try again") }
        }
        is BrowserPaneState.Showing -> Box(Modifier.fillMaxSize()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = {
                    createJournalWebView(
                        context = context,
                        origin = current.origin,
                        textZoom = (density.fontScale * 100).roundToInt().coerceIn(50, 300),
                        onPageCommitted = {
                            if (alive.get()) committed = true
                        },
                        onRetryableFailure = {
                            if (alive.get()) {
                                session.stop()
                                state = BrowserPaneState.Failed(
                                    if (committed) LOST_CONNECTION else INITIAL_FAILURE,
                                )
                            }
                        },
                        onUnsupportedTransfer = {
                            if (alive.get()) notice = SAVE_FAILURE
                        },
                        onUnsupportedFileSelection = {
                            if (alive.get()) notice = FILE_SELECTION_UNAVAILABLE
                        },
                        onUnsupportedNavigation = {
                            if (alive.get()) notice = LINK_UNAVAILABLE
                        },
                    )
                },
                onRelease = { webView ->
                    webView.stopLoading()
                    webView.webChromeClient = null
                    webView.webViewClient = WebViewClient()
                    webView.removeAllViews()
                    webView.destroy()
                },
            )
            if (!committed) {
                CircularProgressIndicator(Modifier.align(Alignment.Center))
            }
            notice?.let { message ->
                Text(
                    text = message,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp),
                )
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
internal fun createJournalWebView(
    context: android.content.Context,
    origin: String,
    textZoom: Int,
    onPageCommitted: () -> Unit,
    onRetryableFailure: () -> Unit,
    onUnsupportedTransfer: () -> Unit,
    onUnsupportedFileSelection: () -> Unit,
    onUnsupportedNavigation: () -> Unit,
): WebView {
    val policy = JournalWebPolicy(origin)
    val insetPolicy = journalWebInsetPolicy(WebView.getCurrentWebViewPackage()?.versionName)
    val blocked = {
        WebResourceResponse(
            "text/plain",
            "UTF-8",
            403,
            "Forbidden",
            emptyMap(),
            ByteArrayInputStream(ByteArray(0)),
        )
    }
    return WebView(context).apply {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val system = insets.getInsets(android.view.WindowInsets.Type.systemBars())
                val cutout = insets.getInsets(android.view.WindowInsets.Type.displayCutout())
                val ime = insets.getInsets(android.view.WindowInsets.Type.ime())
                view.setPadding(
                    maxOf(
                        if (insetPolicy.applyNativeSystemInsets) maxOf(system.left, cutout.left) else 0,
                        if (insetPolicy.applyNativeImeInsets) ime.left else 0,
                    ),
                    maxOf(
                        if (insetPolicy.applyNativeSystemInsets) maxOf(system.top, cutout.top) else 0,
                        if (insetPolicy.applyNativeImeInsets) ime.top else 0,
                    ),
                    maxOf(
                        if (insetPolicy.applyNativeSystemInsets) maxOf(system.right, cutout.right) else 0,
                        if (insetPolicy.applyNativeImeInsets) ime.right else 0,
                    ),
                    // No bottom system inset here on any WebView build: the sheet already
                    // holds the journal above the navigation bar, and adding it twice
                    // leaves a dead band of sheet colour under the tab bar.
                    if (insetPolicy.applyNativeImeInsets) ime.bottom else 0,
                )
            } else {
                @Suppress("DEPRECATION")
                view.setPadding(
                    if (insetPolicy.applyNativeSystemInsets) insets.systemWindowInsetLeft else 0,
                    if (insetPolicy.applyNativeSystemInsets) insets.systemWindowInsetTop else 0,
                    if (insetPolicy.applyNativeSystemInsets) insets.systemWindowInsetRight else 0,
                    0,
                )
            }
            insets
        }
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false
            mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setGeolocationEnabled(false)
            mediaPlaybackRequiresUserGesture = true
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            saveFormData = false
            safeBrowsingEnabled = false
            @Suppress("DEPRECATION")
            savePassword = false
            this.textZoom = textZoom
        }
        android.webkit.CookieManager.getInstance().setAcceptCookie(true)
        android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
        WebView.setWebContentsDebuggingEnabled(
            (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0,
        )
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                if (policy.ownerInitiatedForeign(url, request.isForMainFrame, request.hasGesture())) {
                    onUnsupportedNavigation()
                }
                return !policy.allows(url)
            }

            @Suppress("DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
                !policy.allows(url)

            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest,
            ): WebResourceResponse? = if (policy.allows(request.url.toString())) null else blocked()

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                if (!policy.allows(url)) view.stopLoading()
            }

            override fun onPageCommitVisible(view: WebView, url: String) {
                if (policy.allows(url)) onPageCommitted()
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError,
            ) {
                if (request.isForMainFrame) {
                    onRetryableFailure()
                }
            }

            override fun onReceivedHttpError(
                view: WebView,
                request: WebResourceRequest,
                errorResponse: WebResourceResponse,
            ) {
                if (request.isForMainFrame && errorResponse.statusCode >= 500) {
                    onRetryableFailure()
                }
            }

            override fun onReceivedSslError(
                view: WebView,
                handler: SslErrorHandler,
                error: android.net.http.SslError,
            ) {
                handler.cancel()
                onRetryableFailure()
            }

            override fun onRenderProcessGone(
                view: WebView,
                detail: RenderProcessGoneDetail,
            ): Boolean {
                onRetryableFailure()
                return true
            }
        }
        webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView,
                filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: FileChooserParams,
            ): Boolean {
                filePathCallback.onReceiveValue(null)
                onUnsupportedFileSelection()
                return true
            }

            override fun onPermissionRequest(request: PermissionRequest) = request.deny()

            override fun onGeolocationPermissionsShowPrompt(
                origin: String,
                callback: GeolocationPermissions.Callback,
            ) = callback.invoke(origin, false, false)

            override fun onCreateWindow(
                view: WebView,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: Message,
            ): Boolean {
                if (isUserGesture) onUnsupportedNavigation()
                return false
            }
        }
        setDownloadListener { _, _, _, _, _ -> onUnsupportedTransfer() }
        loadUrl(origin)
    }
}
