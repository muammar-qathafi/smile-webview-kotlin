/**
 * Status: AKTIF
 * Flow: URL halaman saat ini. Halaman login menunda izin dan Fake GPS. Keluar dari login memulai monitor Fake GPS.
 * Takeout: jangan.
 */
package co.id.multifab.smile.webview

import android.app.Activity
import co.id.multifab.smile.bridge.WebViewBridgeRegistry
import java.lang.ref.WeakReference
import java.util.Locale

/** URL & fokus form — untuk back handler, izin, dan hindari ganggu login. */
object WebViewNavigationState {

    @Volatile
    var currentUrl: String = ""
        private set

    @Volatile
    var formInputActive: Boolean = false
        private set

    @Volatile
    var activityRef: WeakReference<Activity>? = null
        private set

    private var lastBackPressAtMs: Long = 0

    fun bindActivity(activity: Activity) {
        activityRef = WeakReference(activity)
    }

    fun unbindActivity(activity: Activity) {
        if (activityRef?.get() === activity) {
            activityRef = null
        }
    }

    fun updateUrl(url: String?) {
        val next = url.orEmpty()
        val wasSignIn = isSignInPage()
        currentUrl = next
        if (wasSignIn && !isSignInPage()) {
            co.id.multifab.smile.permission.NativePermissionCoordinator.onUserLeftSignInPage()
            co.id.multifab.smile.fakegps.FakeGpsGuard.syncFromLock()
            co.id.multifab.smile.fakegps.FakeGpsMonitor.start()
        } else if (!isSignInPage()) {
            co.id.multifab.smile.fakegps.FakeGpsGuard.syncFromLock()
        }
    }

    fun setFormInputActive(active: Boolean) {
        formInputActive = active
    }

    fun isFormInputActive(): Boolean = formInputActive

    fun isSignInPage(): Boolean {
        val u = currentUrl.lowercase(Locale.US)
        if (u.isBlank()) return false
        return u.contains("signin") ||
            u.contains("/login") ||
            u.contains("sign-in") ||
            u.contains("auth/login")
    }

    fun isSettingsPage(): Boolean {
        val u = currentUrl.lowercase(Locale.US)
        return u.contains("setting")
    }

    /** True jika back boleh menutup activity (login: double-back). */
    fun shouldFinishOnBack(canGoBack: Boolean): Boolean {
        if (canGoBack) return false
        if (!isSignInPage()) return true
        val now = System.currentTimeMillis()
        val doubleTap = now - lastBackPressAtMs < 2_000L
        lastBackPressAtMs = now
        return doubleTap
    }

    fun evaluateOnWeb(script: String) {
        WebViewBridgeRegistry.get()?.evaluateJavascript(script)
    }
}
