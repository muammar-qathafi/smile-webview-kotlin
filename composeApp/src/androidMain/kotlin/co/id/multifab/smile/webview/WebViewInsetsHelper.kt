/**
 * Status: PENDUKUNG
 * Flow: Padding status bar dan keyboard supaya input web tidak tertutup, termasuk di perangkat yang insets-nya tidak konsisten.
 * Takeout: jangan.
 */
package co.id.multifab.smile.webview

import android.app.Activity
import android.graphics.Rect
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import kotlin.math.max

/**
 * Margin WebView setara Capacitor `adjustMarginsForEdgeToEdge` + Keyboard resize native.
 *
 * Deteksi IME memakai **dua sumber** (WindowInsets + visible-display-frame) karena
 * di Honor/MagicOS salah satu sering gagal → textarea tertutup keyboard.
 *
 * CSS safe-area bottom tetap dimatikan (hindari double-pad bottom-nav).
 */
object WebViewInsetsHelper {

    private var lastImeBottom = -1
    private var pendingImeHideRunnable: Runnable? = null
    private val imeHideDebounceMs = 280L
    private var lastTopPx = -1
    private var lastBottomPx = -1
    private var lastNavBottomPx = -1
    private var layoutListener: ViewTreeObserver.OnGlobalLayoutListener? = null

    fun attach(activity: Activity, webView: WebView) {
        lastImeBottom = -1
        lastTopPx = -1
        lastBottomPx = -1
        lastNavBottomPx = -1

        val statusBarFallback = SystemBarMetrics.statusBarPx(activity.resources)
        val maxNavCapPx = dp(activity, 48f)
        val imeThresholdPx = dp(activity, 80f)

        fun applyInsets(insets: WindowInsetsCompat? = ViewCompat.getRootWindowInsets(webView)) {
            val safeInsets = insets ?: return
            val statusBars = safeInsets.getInsets(WindowInsetsCompat.Type.statusBars())
            val navBars = safeInsets.getInsets(WindowInsetsCompat.Type.navigationBars())
            val imeFromInsets = safeInsets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            val imeFromFrame = detectKeyboardFromVisibleFrame(activity)
            // Honor: inset sering 0; frame sering akurat. Huawei: sebaliknya. Ambil max.
            val imeRaw = max(imeFromInsets, imeFromFrame)
            val imeBottom = if (imeRaw >= imeThresholdPx) imeRaw else 0

            val top = statusBars.top.coerceAtLeast(statusBarFallback)
            val navBottom = navBars.bottom.coerceIn(0, maxNavCapPx)

            // Jika window/Compose sudah menyusut karena adjustResize, jangan double-margin.
            val alreadyShrunk = isParentAlreadyShrunkForIme(activity, webView, imeBottom)
            val bottom = when {
                imeBottom > 0 && !alreadyShrunk -> imeBottom
                else -> navBottom
            }

            webView.updateLayoutParams<FrameLayout.LayoutParams> {
                topMargin = top
                bottomMargin = bottom
            }

            if (top != lastTopPx || bottom != lastBottomPx) {
                lastTopPx = top
                lastBottomPx = bottom
            }

            if (navBottom != lastNavBottomPx) {
                lastNavBottomPx = navBottom
                dispatchNativeSafeBottom(webView, handledByNative = true)
            }

            // Selalu beri sinyal keyboard ke JS (untuk revealField), meski margin
            // tidak ditambah karena alreadyShrunk — biar textarea di-scroll ke viewport.
            if (imeBottom != lastImeBottom) {
                val previous = lastImeBottom
                lastImeBottom = imeBottom
                pendingImeHideRunnable?.let { webView.removeCallbacks(it) }
                if (imeBottom == 0 && previous > 0) {
                    val runnable = Runnable {
                        if (lastImeBottom == 0) {
                            dispatchKeyboardHeight(webView, 0, handledByNative = true)
                        }
                    }
                    pendingImeHideRunnable = runnable
                    webView.postDelayed(runnable, imeHideDebounceMs)
                } else {
                    pendingImeHideRunnable = null
                    dispatchKeyboardHeight(webView, imeBottom, handledByNative = true)
                }
            }
        }

        webView.updateLayoutParams<ViewGroup.MarginLayoutParams> {
            topMargin = statusBarFallback
            bottomMargin = 0
        }

        ViewCompat.setOnApplyWindowInsetsListener(webView) { _, insets ->
            applyInsets(insets)
            insets
        }

        val root = activity.window.decorView
        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            applyInsets(insets)
            insets
        }

        // Fallback OEM: IME WindowInsets kadang tidak dispatch di Honor.
        val content = activity.findViewById<View>(android.R.id.content)
        layoutListener?.let { listener ->
            content.viewTreeObserver.removeOnGlobalLayoutListener(listener)
        }
        val listener = ViewTreeObserver.OnGlobalLayoutListener {
            applyInsets(ViewCompat.getRootWindowInsets(content) ?: ViewCompat.getRootWindowInsets(webView))
        }
        layoutListener = listener
        content.viewTreeObserver.addOnGlobalLayoutListener(listener)

        ViewCompat.requestApplyInsets(webView)
        ViewCompat.requestApplyInsets(root)
        webView.post {
            dispatchNativeSafeBottom(webView, handledByNative = true)
            applyInsets()
        }
    }

    private fun dp(activity: Activity, value: Float): Int =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value,
            activity.resources.displayMetrics,
        ).toInt()

    /**
     * Tinggi keyboard dari visible display frame — andal di banyak OEM saat
     * WindowInsets.ime() terlambat / 0.
     */
    private fun detectKeyboardFromVisibleFrame(activity: Activity): Int {
        val decor = activity.window.decorView
        val frame = Rect()
        decor.getWindowVisibleDisplayFrame(frame)
        val rootHeight = decor.rootView?.height ?: decor.height
        if (rootHeight <= 0) return 0
        return (rootHeight - frame.bottom).coerceAtLeast(0)
    }

    /**
     * True jika parent WebView sudah dipangkas oleh adjustResize / system,
     * sehingga menambah bottomMargin IME akan double-shift.
     */
    private fun isParentAlreadyShrunkForIme(
        activity: Activity,
        webView: WebView,
        imeBottom: Int,
    ): Boolean {
        if (imeBottom <= 0) return false
        val parent = webView.parent as? View ?: return false
        val decorH = activity.window.decorView.height
        if (decorH <= 0 || parent.height <= 0) return false
        val shrink = decorH - parent.height
        // Parent sudah menyusut mendekati tinggi keyboard → jangan margin lagi.
        return shrink >= (imeBottom * 0.6f)
    }

    private fun dispatchNativeSafeBottom(webView: WebView, handledByNative: Boolean) {
        val handledJs = if (handledByNative) "true" else "false"
        webView.post {
            webView.evaluateJavascript(
                """
                (function() {
                  var handled = $handledJs;
                  var root = document.documentElement;
                  if (handled) {
                    root.classList.add('smile-native-safe-bottom');
                    root.style.setProperty('--smile-safe-bottom', '0px');
                  } else {
                    root.classList.remove('smile-native-safe-bottom');
                    root.style.removeProperty('--smile-safe-bottom');
                  }
                  window.dispatchEvent(new CustomEvent('smile:safe-bottom', {
                    detail: { handledByNative: handled }
                  }));
                })();
                """.trimIndent(),
                null,
            )
        }
    }

    private fun dispatchKeyboardHeight(
        webView: WebView,
        height: Int,
        handledByNative: Boolean,
    ) {
        val handledJs = if (handledByNative) "true" else "false"
        webView.post {
            webView.evaluateJavascript(
                """
                (function() {
                  var h = $height;
                  var handled = $handledJs;
                  var open = h > 0;
                  var events = open
                    ? ['keyboardWillShow','keyboardDidShow']
                    : ['keyboardWillHide','keyboardDidHide'];
                  var payload = open
                    ? { keyboardHeight: h, handledByNative: handled }
                    : { keyboardHeight: 0, handledByNative: handled };
                  if (window.__smileKeyboardListeners) {
                    events.forEach(function(ev) {
                      var list = window.__smileKeyboardListeners[ev] || [];
                      list.slice().forEach(function(fn) {
                        try { fn(payload); } catch(e) {}
                      });
                    });
                  }
                  window.dispatchEvent(new CustomEvent('smile:keyboard', {
                    detail: { height: h, handledByNative: handled }
                  }));
                })();
                """.trimIndent(),
                null,
            )
        }
    }
}
