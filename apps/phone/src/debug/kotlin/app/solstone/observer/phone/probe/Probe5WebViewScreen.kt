// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (c) 2026 sol pbc

package app.solstone.observer.phone.probe

import android.os.Build
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import java.io.ByteArrayInputStream

private enum class FontMode { Follow, Pin }
private enum class InsetMode { Default, Unmodified }

/**
 * WebView font scale and env(safe-area-inset-*). Loads only the local asset.
 * Drive: adb shell settings put system font_scale 1.3
 * Reset: adb shell settings put system font_scale 1.0
 * While running: issue the setting with this screen open (activity recreates; same process).
 * Before relaunch: adb shell am force-stop app.solstone.observer.phone then set font_scale then reopen.
 */
@Composable
fun Probe5WebViewScreen() {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    var fontMode by remember { mutableStateOf(FontMode.Follow) }
    var insetMode by remember { mutableStateOf(InsetMode.Default) }
    var cover by remember { mutableStateOf(false) }
    var textZoom by remember { mutableIntStateOf(-1) }
    var settingFontScale by remember { mutableStateOf(readFontScaleSetting(context)) }
    val processStart = remember { android.os.Process.getStartUptimeMillis() }
    val webViewPackage = remember {
        if (Build.VERSION.SDK_INT >= 26) {
            val info = WebView.getCurrentWebViewPackage()
            if (info == null) {
                "webview=null"
            } else {
                "package=${info.packageName} versionName=${info.versionName}"
            }
        } else {
            "unavailable below API 26"
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            settingFontScale = readFontScaleSetting(context)
            delay(1_000)
        }
    }
    ProbeScaffold(
        measures = "WebView.getTextZoom read-back; configuration.fontScale; Settings.System FONT_SCALE; env(safe-area-inset-*); WebView package",
        drive = "adb shell settings put system font_scale 1.3\nadb shell settings put system font_scale 1.0\nWhile running: issue with this screen open.\nBefore relaunch: adb shell am force-stop app.solstone.observer.phone then set font_scale then reopen.",
        prediction = PROBE5_PREDICTION,
        gaps = PROBE5_GAPS,
    ) {
        Text(webViewPackage)
        Text("processStartUptimeMs=$processStart")
        Text("activity.fontScale=${configuration.fontScale}")
        Text("settings.fontScale=$settingFontScale")
        Text("getTextZoom=$textZoom")
        Text("fontMode=$fontMode insetMode=$insetMode viewportFitCover=$cover (expected inert)")
        Button(onClick = { fontMode = FontMode.Follow }) { Text("font mode 1 follow system") }
        Button(onClick = { fontMode = FontMode.Pin }) { Text("font mode 2 pin") }
        Button(onClick = { insetMode = InsetMode.Default }) { Text("inset default") }
        Button(onClick = { insetMode = InsetMode.Unmodified }) { Text("inset unmodified") }
        Button(onClick = { cover = !cover }) { Text("viewport-fit=cover toggle") }
        Text("16sp", fontSize = 16.sp)
        key(fontMode) {
            AndroidView(
                modifier = Modifier.height(280.dp),
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        webViewClient = AssetOnlyClient()
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.MATCH_PARENT,
                        )
                        loadUrl(ASSET_URL)
                    }
                },
                update = { webView ->
                    if (fontMode == FontMode.Pin) {
                        pinTextZoom(webView)
                    }
                    applyInsetMode(webView, insetMode)
                    val flag = if (cover) "true" else "false"
                    webView.evaluateJavascript("setCover($flag)", null)
                    textZoom = webView.settings.textZoom
                },
                onRelease = { webView -> webView.destroy() },
            )
        }
    }
}

private fun pinTextZoom(webView: WebView) {
    webView.settings.setTextZoom(100)
}

private fun applyInsetMode(webView: WebView, mode: InsetMode) {
    if (mode == InsetMode.Unmodified) {
        webView.setOnApplyWindowInsetsListener { _, insets -> insets }
    } else {
        webView.setOnApplyWindowInsetsListener(null)
    }
}

private fun readFontScaleSetting(context: android.content.Context): Float =
    android.provider.Settings.System.getFloat(
        context.contentResolver,
        android.provider.Settings.System.FONT_SCALE,
        1f,
    )

private class AssetOnlyClient : WebViewClient() {
    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val url = request.url.toString()
        return !url.startsWith(ASSET_PREFIX)
    }

    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        val url = request.url.toString()
        if (url.startsWith(ASSET_PREFIX)) return null
        return WebResourceResponse(
            "text/plain",
            "utf-8",
            ByteArrayInputStream(ByteArray(0)),
        )
    }
}

private const val ASSET_PREFIX = "file:///android_asset/"
private const val ASSET_URL = "file:///android_asset/probe5.html"

private const val PROBE5_PREDICTION =
    "Mode 1 makes no setTextZoom call; getTextZoom is predicted to be the system scale (e.g. 130 at 130%), not the documented default of 100.\n" +
        "Mode 2 calls setTextZoom and is predicted to pin and stop following later setting changes."

private const val PROBE5_GAPS =
    "INTERNET is inherited from src/main and is present on the merged debug APK. This screen does not add INTERNET. Containment is load of file:///android_asset/probe5.html only, plus a WebViewClient that refuses non-asset navigation and returns an empty response for non-asset resource requests. JavaScript is enabled only to read env(safe-area-inset-*) via getComputedStyle against that local asset."
