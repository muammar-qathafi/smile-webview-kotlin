/**
 * Status: PENDUKUNG
 * Flow: Pull-to-refresh native. Hanya aktif saat konten mentok ke atas.
 * Takeout: jangan selama pull-to-refresh dipakai.
 */
package co.id.multifab.smile.webview

import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.webkit.WebView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout

/**
 * Pull-to-refresh native di atas WebView.
 * Aktif hanya saat konten sudah mentok ke atas (WebView + scroll SPA).
 */
class WebViewSwipeRefreshLayout(context: Context) : SwipeRefreshLayout(context) {

    var targetWebView: WebView? = null

    override fun canChildScrollUp(): Boolean {
        val webView = targetWebView
        if (webView != null) {
            if (webView.canScrollVertically(-1)) return true
            if (webView.scrollY > 0) return true
        }
        // SPA: jika konten belum di atas, SwipeRefresh jangan ambil gesture.
        return !WebViewScrollState.contentAtTop
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        // Hanya intercept pull saat benar-benar di top.
        if (!WebViewScrollState.contentAtTop && targetWebView?.canScrollVertically(-1) != true) {
            // content not at top handled by canChildScrollUp; keep default
        }
        return try {
            super.onInterceptTouchEvent(ev)
        } catch (_: Exception) {
            false
        }
    }
}
