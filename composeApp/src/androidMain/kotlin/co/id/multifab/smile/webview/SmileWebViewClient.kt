/**
 * Status: PENDUKUNG
 * Flow: WebViewClient: selesai muat halaman, pulihkan error, picu rantai izin setelah bukan halaman login.
 * Takeout: jangan.
 */
package co.id.multifab.smile.webview

import co.id.multifab.smile.util.SmileLog

import android.graphics.Bitmap
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import co.id.multifab.smile.config.AppConfig
import co.id.multifab.smile.permission.NativePermissionCoordinator

class SmileWebViewClient(
    private val pageFinishedListener: (WebView, String?) -> Unit = { _, _ -> },
) : WebViewClient() {

    private var lastRecoveryAtMs: Long = 0

    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        WebViewNavigationState.updateUrl(url)
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val host = request.url.host.orEmpty()
        if (host.isBlank()) return false
        val allowed = AppConfig.allowedDomains.any { domain ->
            host == domain || host.endsWith(".$domain")
        }
        return !allowed
    }

    override fun onPageFinished(view: WebView, url: String?) {
        super.onPageFinished(view, url)
        WebViewNavigationState.updateUrl(url)
        pageFinishedListener(view, url)
        NativePermissionCoordinator.scheduleOnboardingIfNeeded()
    }

    override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
        super.doUpdateVisitedHistory(view, url, isReload)
        WebViewNavigationState.updateUrl(url ?: view.url)
    }

    override fun onReceivedError(
        view: WebView,
        request: WebResourceRequest,
        error: WebResourceError,
    ) {
        super.onReceivedError(view, request, error)
        if (!request.isForMainFrame) return
        SmileLog.w(TAG, "WebView main frame error: ${error.description} url=${request.url}")
        recoverToHome(view, "network_error")
    }

    private fun recoverToHome(view: WebView, reason: String) {
        val now = System.currentTimeMillis()
        if (now - lastRecoveryAtMs < 2_000L) return
        lastRecoveryAtMs = now
        view.post {
            runCatching {
                view.stopLoading()
                view.loadUrl(AppConfig.webUrl)
            }.onFailure { e ->
                SmileLog.e(TAG, "Recovery failed ($reason): ${e.message}")
            }
        }
    }

    companion object {
        private const val TAG = "SmileWebView"
    }
}
