/**
 * Status: AKTIF
 * Flow: Deteksi Fake GPS hanya dari flag mock pada fix lokasi (stabil di semua OEM).
 * Takeout: jangan.
 */
package co.id.multifab.smile.fakegps

import android.location.Location
import android.os.Build
import co.id.multifab.smile.webview.WebViewNavigationState

object FakeGpsDetector {

    data class Detection(
        val overlay: Boolean,
        val reason: String,
    )

    fun isSystemMock(location: Location): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            location.isMock
        } else {
            @Suppress("DEPRECATION")
            location.isFromMockProvider
        }
    }

    fun onLocation(location: Location): Detection? {
        if (WebViewNavigationState.isSignInPage()) return null
        if (!isSystemMock(location)) return null
        return Detection(overlay = true, reason = "Mock location provider aktif")
    }
}
