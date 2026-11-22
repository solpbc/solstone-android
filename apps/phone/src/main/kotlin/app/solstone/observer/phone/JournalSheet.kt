// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone

import app.solstone.core.i18n.phoneText

import android.annotation.SuppressLint
import android.content.Intent
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
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import app.solstone.core.diagnostics.DiagEvent
import app.solstone.core.identity.JournalMarkPresentation
import app.solstone.core.pl.browser.BrowserTerminalClass
import app.solstone.core.pl.browser.JournalBrowserLifecycle
import app.solstone.core.pl.browser.JournalBrowserLifecycleListener
import app.solstone.core.pl.browser.JournalBrowserSession
import app.solstone.core.pl.browser.JOURNAL_BROWSER_RESPONSE_HEADER
import app.solstone.observer.formfactor.phone.PhoneJournalMarkPill
import app.solstone.observer.formfactor.phone.PhonePane
import app.solstone.observer.formfactor.phone.spokenPaneTitle
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

import app.solstone.core.push.validJournalOpenPath

private val INITIAL_FAILURE get() = phoneText("android.journal.couldn.t.reach.journal.keep")
private val LOST_CONNECTION get() = phoneText("android.journal.lost.connection.journal.keep.solstone")
private val SAVE_FAILURE get() = phoneText("android.journal.couldn.t.save.that.try")
private val FILE_SELECTION_UNAVAILABLE get() = phoneText("android.journal.file.selection.isn.t.available")
private val LINK_UNAVAILABLE get() = phoneText("android.journal.that.link.isn.t.available")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun JournalSheet(
    presentation: JournalMarkPresentation,
    sessionFactory: () -> JournalSheetSession,
    onClose: () -> Unit,
    onPairingRepair: () -> Unit,
    initialPath: String? = null,
    onVisitedUrl: (String) -> Unit = {},
    openAuthBrowser: ((authorizeUrl: String, callbackScheme: String) -> Boolean)? = null,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var retryGeneration by remember { mutableIntStateOf(0) }
    val validatedPath = validJournalOpenPath(initialPath)
    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = sheetState,
        modifier = Modifier
            .padding(top = 72.dp)
            .widthIn(max = 640.dp)
            .semantics { paneTitle = spokenPaneTitle(PhonePane.JOURNAL, presentation) },
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
            // 🔴 The sheet's title is the PILL, not the mark card. `journal-mark.md` § 7 item 5:
            // *"the home pill carries the chip pair and the words, and when the journal opens that
            // pill becomes the sheet's title."* A full card here spent roughly a seventh of the
            // screen on chrome and left `close` floating beside a block far taller than it.
            //
            // ⚠ The same sentence ends *"one screen never shows the mark twice"* — satisfied here
            // because the home pill is drawn `Alignment.BottomCenter` (`PhoneShell`) and this sheet
            // insets only 72dp from the top, so the pill is behind the sheet's own surface. ⛔ If
            // the pill ever moves into the top bar, this header has to change with it.
            PhoneJournalMarkPill(
                onClick = null,
                paired = true,
                presentation = presentation,
                isHeading = true,
            )
            // ⚠ The pill wraps its own content and the spacer takes the slack. ⛔ Not a weight on
            // the pill: `weight(1f, fill = false)` caps it at its share of the row, which squeezes
            // a long two-word mark rather than letting `close` move over.
            Spacer(Modifier.weight(1f))
            TextButton(onClick = onClose) { Text(phoneText("android.journal.close")) }
        }
        // The sheet asks for no window insets so its surface can paint under the
        // navigation bar, which means the journal itself has to stop above it. Without
        // this the journal's own bottom tab bar lands under Android's navigation bar and
        // cannot be tapped at all — a tap on `search` reaches the system bar instead.
        Box(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.navigationBars)) {
            key(retryGeneration) {
                JournalBrowserPane(
                    sessionFactory = sessionFactory,
                    path = validatedPath,
                    onRetry = { retryGeneration += 1 },
                    onPairingRepair = onPairingRepair,
                    onVisitedUrl = onVisitedUrl,
                    openAuthBrowser = openAuthBrowser,
                )
            }
        }
    }
}

private sealed interface BrowserPaneState {
    data object Loading : BrowserPaneState
    data class Showing(val origin: String, val loadTarget: String) : BrowserPaneState
    data class Failed(val message: String) : BrowserPaneState
}

@Composable
private fun JournalBrowserPane(
    sessionFactory: () -> JournalSheetSession,
    path: String? = null,
    onRetry: () -> Unit,
    onPairingRepair: () -> Unit,
    onVisitedUrl: (String) -> Unit,
    openAuthBrowser: ((authorizeUrl: String, callbackScheme: String) -> Boolean)? = null,
) {
    val hostView = LocalView.current
    val context = LocalContext.current
    val density = LocalDensity.current
    var state by remember { mutableStateOf<BrowserPaneState>(BrowserPaneState.Loading) }
    var committed by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var liveWebView by remember { mutableStateOf<WebView?>(null) }
    var liveImportHost by remember { mutableStateOf<JournalImportHost<Uri>?>(null) }
    var observedCanGoBack by remember { mutableStateOf(false) }
    val session = remember { sessionFactory() }
    val alive = remember { AtomicBoolean(true) }
    // The journal's import page hands file picking to the app. One pick is open at a time; the
    // system picker's answer goes to whichever pick asked for it.
    val pickAnswer = remember { arrayOfNulls<(List<Uri>?) -> Unit>(1) }
    val answerPick = { uris: List<Uri>? ->
        val answer = pickAnswer[0]
        pickAnswer[0] = null
        answer?.invoke(uris)
    }
    val pickMany = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { answerPick(it) }
    val pickOne = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { answerPick(listOfNotNull(it)) }
    val openPicker = { multiple: Boolean, onPicked: (List<Uri>?) -> Unit ->
        pickAnswer[0] = onPicked
        // Every file stays choosable: the page's filters name extensions, and the system picker
        // filters only by type, which providers often leave out (a .zip export reads as no type).
        try {
            if (multiple) pickMany.launch(arrayOf("*/*")) else pickOne.launch(arrayOf("*/*"))
        } catch (e: RuntimeException) {
            pickAnswer[0] = null
            throw e
        }
    }

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
                    else -> {
                        liveWebView = null
                        observedCanGoBack = false
                        state = BrowserPaneState.Failed(
                            if (committed) LOST_CONNECTION else INITIAL_FAILURE,
                        )
                    }
                }
            }
        }
        session.addLifecycleListener(listener)
        state = try {
            val origin = session.start().url
            val loadTarget = if (path != null) origin + path.removePrefix("/") else origin
            BrowserPaneState.Showing(origin, loadTarget)
        } catch (_: Throwable) {
            BrowserPaneState.Failed(INITIAL_FAILURE)
        }
        onDispose {
            alive.set(false)
            session.removeLifecycleListener(listener)
            session.stop()
        }
    }

    val journalCanGoBack = liveWebView != null && observedCanGoBack
    BackHandler(enabled = journalCanGoBack) {
        liveWebView?.goBack()
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
            Button(onClick = onRetry) { Text(phoneText("android.migration.try.again")) }
        }
        is BrowserPaneState.Showing -> Box(Modifier.fillMaxSize()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = {
                    createJournalWebView(
                        context = context,
                        origin = current.origin,
                        loadTarget = current.loadTarget,
                        textZoom = (density.fontScale * 100).roundToInt().coerceIn(50, 300),
                        onLiveView = { view -> liveWebView = view },
                        onVisitedHistory = { view, url, currentCanGoBack ->
                            // alive stays true on the failure screen, so a late history callback is
                            // accepted only for the current live view, which is cleared before destroy.
                            if (view === liveWebView) {
                                observedCanGoBack = currentCanGoBack
                                onVisitedUrl(url)
                                PhoneJournalTestHooks.onVisitedHistory?.invoke(url, currentCanGoBack)
                            }
                        },
                        onPageCommitted = {
                            if (alive.get()) committed = true
                        },
                        onRetryableFailure = {
                            if (alive.get()) {
                                liveWebView = null
                                observedCanGoBack = false
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
                        openPicker = openPicker,
                        openAuthBrowser = openAuthBrowser,
                        onImportHost = { host ->
                            liveImportHost?.let { prev ->
                                prev.authHost?.let(JournalAuthCallbackBus::unregister)
                                prev.tearDown()
                            }
                            liveImportHost = host
                            host?.authHost?.let(JournalAuthCallbackBus::register)
                        },
                    )
                },
                onRelease = { webView ->
                    if (liveWebView === webView) {
                        liveWebView = null
                        observedCanGoBack = false
                    }
                    liveImportHost?.let { host ->
                        host.authHost?.let(JournalAuthCallbackBus::unregister)
                        host.tearDown()
                    }
                    liveImportHost = null
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
    loadTarget: String = origin,
    textZoom: Int,
    onLiveView: (WebView) -> Unit,
    onVisitedHistory: (WebView, String, Boolean) -> Unit,
    onPageCommitted: () -> Unit,
    onRetryableFailure: () -> Unit,
    onUnsupportedTransfer: () -> Unit,
    onUnsupportedFileSelection: () -> Unit,
    onUnsupportedNavigation: () -> Unit,
    openPicker: ((Boolean, (List<Uri>?) -> Unit) -> Unit)? = null,
    openAuthBrowser: ((authorizeUrl: String, callbackScheme: String) -> Boolean)? = null,
    onImportHost: ((JournalImportHost<Uri>?) -> Unit)? = null,
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
    var importHost: JournalImportHost<Uri>? = null
    return WebView(journalWebViewContext(context)).apply {
        onLiveView(this)
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
                val decision = policy.decide(url, request.isForMainFrame)
                return applyJournalWebNavigation(
                    decision,
                    request.hasGesture(),
                    open = { destination -> deliverJournalExternalView(context, destination) },
                    notify = onUnsupportedNavigation,
                )
            }

            @Suppress("DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
                !policy.allows(url)

            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest,
            ): WebResourceResponse? = if (policy.allows(request.url.toString())) null else blocked()

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                importHost?.documentStarted()
                if (!policy.allows(url)) view.stopLoading()
            }

            override fun onPageCommitVisible(view: WebView, url: String) {
                if (policy.allows(url)) onPageCommitted()
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                onVisitedHistory(view, url, view.canGoBack())
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
                // The journal owns its response page, including honest 5xx link errors.
                // Only a bridge-generated failure belongs to the app's retry view.
                val fromJournal = errorResponse.responseHeaders?.any { (name, value) ->
                    name.equals(JOURNAL_BROWSER_RESPONSE_HEADER, ignoreCase = true) && value == "1"
                } == true
                if (request.isForMainFrame && errorResponse.statusCode >= 500 && !fromJournal) {
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
                importHost?.tearDown()
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
            ): Boolean = false
        }
        setDownloadListener { _, _, _, _, _ -> onUnsupportedTransfer() }
        applyJournalWebHostUserAgent(context, settings)
        importHost = openPicker?.let { installJournalImportHost(context, this, origin, it, openAuthBrowser) }
        onImportHost?.invoke(importHost)
        PhoneJournalTestHooks.onLoadUrl?.invoke(loadTarget)
        loadUrl(loadTarget)
    }
}

/**
 * The context the journal's WebView is built on. WebView takes `prefers-color-scheme` from its
 * context's theme, and the bottom sheet's own context carries a dark dialog theme.
 */
internal fun journalWebViewContext(context: android.content.Context): android.content.Context =
    android.view.ContextThemeWrapper(context, R.style.JournalWebViewTheme)

internal fun journalExternalViewIntent(url: String): Intent =
    Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url))

internal fun deliverJournalExternalView(context: android.content.Context, url: String) {
    val intent = journalExternalViewIntent(url)
    val starter = PhoneJournalTestHooks.externalViewStarter
    if (starter != null) starter(intent) else context.startActivity(intent)
}

private fun applyJournalWebHostUserAgent(
    context: android.content.Context,
    settings: android.webkit.WebSettings,
) {
    val result = runCatching {
        context.assets.open("journal-web-host/host-contract.json").use { input ->
            parseJournalWebHostContract(input.readBytes())
        }
    }.getOrElse { JournalWebHostContractParse.Refused }

    when (result) {
        is JournalWebHostContractParse.Product -> {
            runCatching {
                val defaultAgent = settings.userAgentString
                settings.userAgentString = journalUserAgent(defaultAgent, result.value)
            }.onFailure { emitInvalidJournalWebHostContract() }
        }
        JournalWebHostContractParse.Refused -> emitInvalidJournalWebHostContract()
    }
}

/** Store reads for every journal window's import host, off the main thread. */
private val importHostReads by lazy {
    java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "import-host").apply { isDaemon = true } }
}

/** The journal's origin (`scheme://host:port`) for a URL the bridge serves it at. */
internal fun journalOriginOf(url: String): String? {
    val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return null
    val scheme = uri.scheme?.lowercase() ?: return null
    val host = uri.host ?: return null
    return if (uri.port >= 0) "$scheme://$host:${uri.port}" else "$scheme://$host"
}

/**
 * Give the journal's top document the import host, when this device's WebView can do it safely:
 * a message listener bound to the journal's exact origin, with the main frame checked on every
 * message, and a document-start script so the page finds the host before its own code runs.
 * Without either, the page sees no host and keeps its own file input, which the app refuses with
 * a pointer to the import tile and Share.
 */
private fun installJournalImportHost(
    context: android.content.Context,
    view: WebView,
    url: String,
    openPicker: (Boolean, (List<Uri>?) -> Unit) -> Unit,
    openAuthBrowser: ((authorizeUrl: String, callbackScheme: String) -> Boolean)? = null,
): JournalImportHost<Uri>? {
    if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) ||
        !WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
    ) {
        PhoneDiagLog.emit(DiagEvent.JournalBrowser(eventClass = "import-host", outcome = "unsupported-webview"))
        return null
    }
    val origin = journalOriginOf(url)
    val contract = runCatching {
        context.assets.open("journal-web-host/import-host-contract.json").use { parseImportHostContract(it.readBytes()) }
    }.getOrNull()
    val authContract = runCatching {
        context.assets.open("journal-web-host/auth-host-contract.json").use { parseAuthHostContract(it.readBytes()) }
    }.getOrNull()?.takeIf { it.capability == contract?.capability }
    val template = runCatching {
        context.assets.open("journal-web-host/import-host.js").use { String(it.readBytes(), Charsets.UTF_8) }
    }.getOrNull()
    if (origin == null || contract == null || template == null) {
        PhoneDiagLog.emit(DiagEvent.JournalBrowser(eventClass = "import-host", outcome = "invalid"))
        return null
    }
    val app = context.applicationContext
    val intake = ImportIntake.get(app)
    val main = android.os.Handler(android.os.Looper.getMainLooper())
    val fallbackBrowserOpener: (String, String) -> Boolean = { authorizeUrl, _ ->
        runCatching {
            deliverJournalExternalView(context, authorizeUrl)
            true
        }.getOrDefault(false)
    }
    val authHost = authContract?.let { auth ->
        JournalAuthHost(
            contract = auth,
            openBrowser = openAuthBrowser ?: fallbackBrowserOpener,
        )
    }
    val host = JournalImportHost(
        origin = origin,
        contract = contract,
        openPicker = openPicker,
        itemFor = { uri: Uri -> app.contentResolver.intakeItemFor(uri) },
        submit = { items, done -> intake.submit(items, done) },
        status = { id -> intake.store.handoffStatus(id) },
        background = { work -> importHostReads.execute(work) },
        authHost = authHost,
    )
    return try {
        val rules = setOf(origin)
        WebViewCompat.addWebMessageListener(view, JOURNAL_IMPORT_BRIDGE, rules) { _, message, sourceOrigin, isMainFrame, replyProxy ->
            host.onMessage(message.data, sourceOrigin.toString(), isMainFrame) { text ->
                main.post { runCatching { replyProxy.postMessage(text) } }
            }
        }
        WebViewCompat.addDocumentStartJavaScript(
            view,
            journalImportHostScript(template, contract, origin, authContract),
            rules,
        )
        host
    } catch (_: RuntimeException) {
        PhoneDiagLog.emit(DiagEvent.JournalBrowser(eventClass = "import-host", outcome = "refused"))
        null
    }
}

private fun emitInvalidJournalWebHostContract() {
    PhoneDiagLog.emit(DiagEvent.JournalBrowser(eventClass = "host-contract", outcome = "invalid"))
}
