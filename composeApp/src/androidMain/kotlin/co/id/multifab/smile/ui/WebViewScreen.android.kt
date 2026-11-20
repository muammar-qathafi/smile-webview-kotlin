/**
 * Status: AKTIF
 * Flow: WebView production: muat smile.multifab.co.id, pasang JS bridge, suntik skrip shell, pull-to-refresh.
 * Takeout: jangan.
 */
package co.id.multifab.smile.ui

import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewCompat
import co.id.multifab.smile.bridge.ShellScriptInjector
import co.id.multifab.smile.bridge.SmileJsBridge
import co.id.multifab.smile.bridge.WebViewBridgeRegistry
import co.id.multifab.smile.config.AppConfig
import co.id.multifab.smile.fcm.FcmBackendSync
import co.id.multifab.smile.fcm.NotificationHelper
import co.id.multifab.smile.webview.SmileWebChromeClient
import co.id.multifab.smile.webview.SmileWebViewClient
import co.id.multifab.smile.webview.SystemBarMetrics
import co.id.multifab.smile.webview.SystemBarMetrics.findActivity
import co.id.multifab.smile.webview.WebViewInsetsHelper
import co.id.multifab.smile.webview.WebViewScrollState
import co.id.multifab.smile.webview.WebViewSwipeRefreshLayout

actual class WebViewController internal constructor(
    private val webView: WebView?,
) {
    actual fun reload() {
        webView?.reload()
    }

    actual fun canGoBack(): Boolean = webView?.canGoBack() == true

    actual fun goBack() {
        webView?.goBack()
    }

    actual fun evaluateJavascript(script: String, onResult: ((String) -> Unit)?) {
        val view = webView ?: return
        val run = {
            if (onResult != null) {
                view.evaluateJavascript(script) { raw -> onResult(raw.orEmpty()) }
            } else {
                view.evaluateJavascript(script, null)
            }
        }
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            run()
        } else {
            view.post(run)
        }
    }

    actual fun loadUrl(url: String) {
        webView?.loadUrl(url)
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
actual fun WebViewScreen(modifier: Modifier) {
    val bridge = remember { SmileJsBridge() }

    DisposableEffect(Unit) {
        onDispose {
            WebViewBridgeRegistry.clear()
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            NotificationHelper.ensureChannel(context)

            val statusBarPx = SystemBarMetrics.statusBarPx(context.resources)
            val activity = context.findActivity()
            val mainHandler = Handler(Looper.getMainLooper())

            // SwipeRefresh → FrameLayout (insets) → WebView
            val swipeRefresh = WebViewSwipeRefreshLayout(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                setBackgroundColor(Color.WHITE)
                setColorSchemeColors(
                    Color.parseColor("#0D9488"),
                    Color.parseColor("#0891B2"),
                    Color.parseColor("#2563EB"),
                )
                setProgressBackgroundColorSchemeColor(Color.WHITE)
                setProgressViewOffset(
                    false,
                    statusBarPx / 2,
                    statusBarPx + (96 * resources.displayMetrics.density).toInt(),
                )
            }

            val container = FrameLayout(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                setBackgroundColor(Color.WHITE)
            }

            val webView = WebView(context).apply {
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ).apply {
                    topMargin = statusBarPx
                    bottomMargin = 0
                }
                setBackgroundColor(Color.WHITE)
                overScrollMode = android.view.View.OVER_SCROLL_IF_CONTENT_SCROLLS

                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    setGeolocationEnabled(true)
                    mediaPlaybackRequiresUserGesture = false
                    allowFileAccess = false
                    allowContentAccess = true
                    loadWithOverviewMode = true
                    useWideViewPort = true
                    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                    cacheMode = WebSettings.LOAD_DEFAULT
                    userAgentString = settings.userAgentString + AppConfig.USER_AGENT_SUFFIX
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    settings.safeBrowsingEnabled = true
                }

                addJavascriptInterface(bridge, AppConfig.JS_BRIDGE_NAME)

                WebViewCompat.addDocumentStartJavaScript(
                    this,
                    ShellScriptInjector.documentStartScripts(context),
                    setOf("*"),
                )

                webViewClient = SmileWebViewClient(
                    pageFinishedListener = { view, _ ->
                        ShellScriptInjector.injectShellScripts(view, bridge)
                        swipeRefresh.isRefreshing = false
                        WebViewScrollState.setContentAtTop(true)
                        view.postDelayed({
                            FcmBackendSync.ensureRegisteredAsync("pageFinished")
                        }, 800L)
                    },
                )
                webChromeClient = SmileWebChromeClient(requireNotNull(activity) { "WebView requires Activity" })

                if (activity != null) {
                    WebViewInsetsHelper.attach(activity, this)
                }

                WebViewBridgeRegistry.register(WebViewController(this))
                loadUrl(AppConfig.webUrl)
            }

            swipeRefresh.targetWebView = webView
            swipeRefresh.setOnRefreshListener {
                swipeRefresh.isRefreshing = true
                webView.reload()
                mainHandler.postDelayed({
                    if (swipeRefresh.isRefreshing) swipeRefresh.isRefreshing = false
                }, 12_000L)
            }

            container.addView(webView)
            swipeRefresh.addView(container)
            swipeRefresh
        },
    )
}
