/**
 * Status: AKTIF
 * Flow: Terapkan mock lokasi ke sticky lock + overlay; unlock dari fix non-mock.
 * Takeout: jangan.
 */
package co.id.multifab.smile.fakegps

import android.location.Location
import android.os.Handler
import android.os.Looper
import co.id.multifab.smile.ui.NativeOverlayState
import co.id.multifab.smile.util.SmileLog
import co.id.multifab.smile.webview.WebViewNavigationState

object FakeGpsGuard {
    private const val TAG = "FakeGpsGuard"
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var lastLoggedReason: String = ""

    @Volatile
    private var lastLoggedAtMs: Long = 0L

    fun onLocation(location: Location) {
        if (WebViewNavigationState.isSignInPage()) return
        FakeGpsLock.ensureLoaded()

        if (FakeGpsLock.isLocked()) {
            if (FakeGpsDetector.isSystemMock(location)) {
                FakeGpsLock.lock("Mock location provider aktif")
                logIfNeeded(FakeGpsLock.reason())
                syncOverlayOpen(FakeGpsLock.reason())
                return
            }
            if (FakeGpsLock.tryUnlock(location)) {
                SmileLog.i(TAG, "unlock ok")
                syncOverlayClose()
            }
            return
        }

        val detection = FakeGpsDetector.onLocation(location) ?: return
        applyLock(detection)
    }

    fun syncFromLock() {
        FakeGpsLock.ensureLoaded()
        if (WebViewNavigationState.isSignInPage()) return
        if (FakeGpsLock.isLocked()) {
            syncOverlayOpen(FakeGpsLock.reason())
        }
    }

    /** Alias: tidak ada probe AppOps — hanya sync sticky overlay. */
    fun evaluateEnvironment() = syncFromLock()

    fun evaluateLastKnown() = syncFromLock()

    private fun applyLock(detection: FakeGpsDetector.Detection) {
        if (!detection.overlay) return
        SmileLog.i(TAG, "lock reason=${detection.reason}")
        FakeGpsLock.lock(detection.reason)
        logIfNeeded(detection.reason)
        syncOverlayOpen(detection.reason)
    }

    private fun logIfNeeded(reason: String) {
        val now = System.currentTimeMillis()
        if (reason != lastLoggedReason || now - lastLoggedAtMs > 60_000L) {
            lastLoggedReason = reason
            lastLoggedAtMs = now
            FakeGpsLogger.logAsync(reason)
        }
    }

    private fun syncOverlayOpen(reason: String) {
        mainHandler.post {
            if (!WebViewNavigationState.isSignInPage()) {
                NativeOverlayState.openFakeGps(reason)
            }
        }
    }

    private fun syncOverlayClose() {
        mainHandler.post {
            NativeOverlayState.closeFakeGps()
        }
    }
}
