/**
 * Status: AKTIF
 * Flow: JavascriptInterface SmileNative. Web memanggil push, sesi, lokasi, Fake GPS (selalu false), geofence, dan fitur native dari sini.
 * Takeout: jangan. isFakeGps/isMockLocation sengaja false supaya popup HTML tidak muncul.
 */
package co.id.multifab.smile.bridge

import android.webkit.WebView
import co.id.multifab.smile.SmileApplication
import co.id.multifab.smile.fcm.FcmBackendSync
import co.id.multifab.smile.ui.WebViewController
import co.id.multifab.smile.platform.DeviceInfo
import co.id.multifab.smile.platform.PushDiagnostics
import co.id.multifab.smile.platform.PushNotificationHandler
import co.id.multifab.smile.platform.SecureStorage
import co.id.multifab.smile.capacitor.CapacitorBridge
import co.id.multifab.smile.permission.NativePermissionCoordinator
import co.id.multifab.smile.location.GeofenceMonitor
import co.id.multifab.smile.webview.WebViewNavigationState
import co.id.multifab.smile.location.LocationShiftSync
import co.id.multifab.smile.location.NativeLocationController
import co.id.multifab.smile.location.NativeLocationStore
import co.id.multifab.smile.nativeconfig.NativeConfigRefresh
import co.id.multifab.smile.nativeconfig.NativeConfigStore
import co.id.multifab.smile.nativefeature.NativeFeatureRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.json.JSONObject

/**
 * JavaScript bridge — dipanggil dari web via `window.SmileNative.*`.
 * Juga menyuntikkan shim Capacitor minimal agar kode web lama tetap jalan.
 */
class SmileJsBridge {

    private val json = Json { ignoreUnknownKeys = true }

    @android.webkit.JavascriptInterface
    fun isNative(): Boolean = true

    @android.webkit.JavascriptInterface
    fun getPlatform(): String = DeviceInfo.getPlatform()

    @android.webkit.JavascriptInterface
    fun getAppVersion(): String = DeviceInfo.getAppVersion()

    @android.webkit.JavascriptInterface
    fun getDeviceName(): String = DeviceInfo.getDeviceName()

    // ── Push / FCM ───────────────────────────────────────────────────────────

    @android.webkit.JavascriptInterface
    fun getPushPermission(): String {
        PushNotificationHandler.refreshPermissionCache()
        return PushNotificationHandler.getPermission()
    }

    /**
     * Sync API (Capacitor lama). Block di Default dispatcher agar tidak deadlock Main.
     */
    @android.webkit.JavascriptInterface
    fun requestPushPermission(): String = runBlocking(Dispatchers.Default) {
        PushNotificationHandler.requestPermission()
    }

    /**
     * Async API — tidak memblokir JS thread; hasil lewat event smile-push-permission-result.
     */
    @android.webkit.JavascriptInterface
    fun requestPushPermissionAsync() {
        CoroutineScope(Dispatchers.Default).launch {
            val result = runCatching { PushNotificationHandler.requestPermission() }
                .getOrElse { PushNotificationHandler.getPermission() }
            kotlinx.coroutines.withContext(Dispatchers.Main) {
                runCatching { dispatchPermissionResultToWeb(result) }
            }
        }
    }

    @android.webkit.JavascriptInterface
    fun openNotificationSettings(): Boolean {
        PushNotificationHandler.openNotificationSettings()
        return true
    }

    @android.webkit.JavascriptInterface
    fun getNativePermissionsStatus(): String = NativePermissionCoordinator.getStatusJson()

    @android.webkit.JavascriptInterface
    fun requestNativeLocationPermission(chainNotification: Boolean) {
        NativePermissionCoordinator.requestLocationFromUser(chainNotificationAfter = chainNotification)
    }

    @android.webkit.JavascriptInterface
    fun requestNativeNotificationPermission() {
        NativePermissionCoordinator.requestNotificationFromUser()
    }

    @android.webkit.JavascriptInterface
    fun requestNativeMediaPermission() {
        NativePermissionCoordinator.requestMediaFromUser(fromFileChooser = false)
    }

    @android.webkit.JavascriptInterface
    fun openNativeAppSettings(): Boolean {
        NativePermissionCoordinator.openAppSettings(WebViewNavigationState.activityRef?.get())
        return true
    }

    @android.webkit.JavascriptInterface
    fun setFormInputActive(active: Boolean) {
        WebViewNavigationState.setFormInputActive(active)
    }

    /** Sync — hanya baca cache. Jangan await Firebase dari JS bridge (crash/ANR Honor). */
    @android.webkit.JavascriptInterface
    fun getFcmToken(): String = PushNotificationHandler.getCachedToken()

    /**
     * Tampilkan notifikasi sistem Android (status bar / heads-up), bukan banner HTML.
     * Dipakai setelah check-in/out sukses agar user langsung melihat tray native
     * (FCM server tetap dikirim BE ke device lain / token yang sama).
     */
    @android.webkit.JavascriptInterface
    fun showSystemNotification(title: String, body: String, type: String): String {
        return try {
            val data = mapOf(
                "type" to type,
                "title" to title,
                "body" to body,
                "source" to "smile_native_bridge",
            )
            co.id.multifab.smile.fcm.NotificationHelper.showNotification(
                context = SmileApplication.instance,
                title = title.ifBlank { "Smile Multifab" },
                body = body.ifBlank { title },
                data = data,
            )
            """{"ok":true}"""
        } catch (e: Exception) {
            """{"ok":false,"error":"${e.message?.replace("\"", "'") ?: "failed"}"}"""
        }
    }

    /**
     * Dipanggil dari shell hook saat POST attendance-check sukses (check-in).
     * Tanpa ubah kode web Smile.
     */
    @android.webkit.JavascriptInterface
    fun onAttendanceCheckInSuccess(): String {
        NativeConfigRefresh.afterAttendanceCheckIn()
        return """{"ok":true,"started":true}"""
    }

    /**
     * Jam shift dari shift_label di GET attendance-context (halaman /attendance).
     * Tanpa ubah kode web Smile.
     */
    @android.webkit.JavascriptInterface
    fun onAttendanceShift(timeIn: String, timeOut: String, label: String): String {
        NativeConfigRefresh.onAttendanceShift(timeIn, timeOut, label)
        return """{"ok":true}"""
    }

    @android.webkit.JavascriptInterface
    fun getFcmDiagnostics(@Suppress("UNUSED_PARAMETER") requestPermission: Boolean): String {
        val diag = PushNotificationHandler.getDiagnostics(requestPermission = false)
        return json.encodeToString(diag)
    }

    @android.webkit.JavascriptInterface
    fun registerPush() {
        if (!PushNotificationHandler.isFirebaseConfigured()) return
        if (!SmileApplication.isInForeground) return
        CoroutineScope(Dispatchers.IO).launch {
            val token = PushNotificationHandler.getCachedToken().ifBlank {
                runCatching { PushNotificationHandler.getToken(forceRefresh = false) }.getOrNull().orEmpty()
            }
            if (token.isNotBlank()) {
                withContext(Dispatchers.Main) {
                    runCatching { SmileJsBridge.dispatchTokenToWeb(token) }
                }
            }
            FcmBackendSync.ensureRegistered()
        }
    }

    /** Web memanggil ini setelah izin OS granted — set preferensi toggle tanpa tunggu FCM. */
    @android.webkit.JavascriptInterface
    fun onNotificationPermissionGranted() {
        dispatchEnablePushPreferenceToWeb()
    }

    // ── Secure Storage ───────────────────────────────────────────────────────

    @android.webkit.JavascriptInterface
    fun getSecure(key: String): String = SecureStorage.get(key).orEmpty()

    @android.webkit.JavascriptInterface
    fun setSecure(key: String, value: String) {
        SecureStorage.set(key, value)
    }

    @android.webkit.JavascriptInterface
    fun removeSecure(key: String) {
        SecureStorage.remove(key)
    }

    // ── TTS Reminder (SmileReminderTts Capacitor plugin) ─────────────────────

    @android.webkit.JavascriptInterface
    fun scheduleReminderTts(id: Int, soundUri: String, atMs: Double): String {
        return """{"ok":true,"requestCode":0}"""
    }

    @android.webkit.JavascriptInterface
    fun playReminderTtsNow(soundUri: String): String {
        return """{"ok":true,"skipped":true}"""
    }

    @android.webkit.JavascriptInterface
    fun cancelReminderTts(id: Int): String {
        return """{"ok":true}"""
    }

    // ── Capacitor native bridge (compat frozen — fitur baru → invokeFeature) ─

    @android.webkit.JavascriptInterface
    fun capacitorInvoke(plugin: String, method: String, optionsJson: String): String =
        CapacitorBridge.invoke(plugin, method, optionsJson)

    // ── Native Feature Plugin registry ───────────────────────────────────────

    @android.webkit.JavascriptInterface
    fun invokeFeature(feature: String, method: String, optionsJson: String): String =
        NativeFeatureRegistry.invoke(feature, method, optionsJson)

    @android.webkit.JavascriptInterface
    fun listFeatures(): String =
        NativeFeatureRegistry.list().toString()

    // ── Location / geofence (native) ────────────────────────────────────────

    @android.webkit.JavascriptInterface
    fun startNativeLocationTracking(@Suppress("UNUSED_PARAMETER") intervalMs: Double): String {
        LocationShiftSync.apply()
        return """{"ok":true}"""
    }

    @android.webkit.JavascriptInterface
    fun stopNativeLocationTracking(): String {
        NativeLocationController.stopTracking()
        return """{"ok":true}"""
    }

    @android.webkit.JavascriptInterface
    fun updateGeofenceConfig(json: String): String {
        GeofenceMonitor.updateConfig(json)
        return """{"ok":true}"""
    }

    @android.webkit.JavascriptInterface
    fun setLeaveExemption(hasLeave: Boolean): String {
        GeofenceMonitor.setLeaveExemption(hasLeave)
        return """{"ok":true}"""
    }

    @android.webkit.JavascriptInterface
    fun getNativeLocationLog(): String =
        NativeLocationStore.readRecent(50).toString()

    /** Web tidak boleh memakai hasil ini untuk popup HTML; deteksi overlay hanya native. */
    @android.webkit.JavascriptInterface
    fun isMockLocation(): Boolean = false

    @android.webkit.JavascriptInterface
    fun isFakeGps(): Boolean = false

    /**
     * Dipanggil dari web saat scroll: true = konten di paling atas (boleh pull-to-refresh).
     */
    @android.webkit.JavascriptInterface
    fun setContentAtTop(atTop: Boolean) {
        co.id.multifab.smile.webview.WebViewScrollState.setContentAtTop(atTop)
    }

    /** Shim Capacitor + plugin SmileReminderTts setelah halaman load. */
    fun injectCompatShim(webView: WebView) {
        webView.evaluateJavascript(CapacitorCompatShim.pageFinishedScript(), null)
    }

    companion object {
        fun dispatchPermissionResultToWeb(receive: String) {
            val controller = WebViewBridgeRegistry.get() ?: return
            val safe = receive.replace("\\", "\\\\").replace("'", "\\'")
            controller.evaluateJavascript(
                """
                (function() {
                  document.dispatchEvent(new CustomEvent('smile-push-permission-result', {
                    detail: { receive: '$safe' }
                  }));
                  if ('$safe' === 'granted') {
                    try {
                      localStorage.setItem('smile_push_notifications_enabled', '1');
                      window.dispatchEvent(new CustomEvent('smile-push-notifications-preference-changed', {
                        detail: { enabled: true }
                      }));
                    } catch (e) {}
                  }
                })();
                """.trimIndent(),
            )
        }

        fun dispatchEnablePushPreferenceToWeb() {
            val controller = WebViewBridgeRegistry.get() ?: return
            controller.evaluateJavascript(
                """
                (function() {
                  try {
                    localStorage.setItem('smile_push_notifications_enabled', '1');
                    window.dispatchEvent(new CustomEvent('smile-push-notifications-preference-changed', {
                      detail: { enabled: true }
                    }));
                  } catch (e) {}
                })();
                """.trimIndent(),
            )
        }

        fun dispatchPushToWeb(payload: JSONObject) {
            val controller = WebViewBridgeRegistry.get() ?: return
            val escaped = payload.toString()
                .replace("\\", "\\\\")
                .replace("'", "\\'")
            controller.evaluateJavascript(
                """
                (function() {
                  var detail = JSON.parse('$escaped');
                  document.dispatchEvent(new CustomEvent('smile-native-push-received', { detail: detail }));
                })();
                """.trimIndent(),
            )
        }

        fun dispatchReminderResyncToWeb(payload: JSONObject) {
            val controller = WebViewBridgeRegistry.get() ?: return
            val escaped = payload.toString()
                .replace("\\", "\\\\")
                .replace("'", "\\'")
            controller.evaluateJavascript(
                """
                (function() {
                  var detail = JSON.parse('$escaped');
                  document.dispatchEvent(new CustomEvent('smile-reminder-resync', { detail: detail }));
                  document.dispatchEvent(new CustomEvent('smile-native-push-received', { detail: detail }));
                })();
                """.trimIndent(),
            )
        }

        fun dispatchTokenToWeb(token: String) {
            val controller = WebViewBridgeRegistry.get() ?: return
            val safe = token.replace("\\", "\\\\").replace("'", "\\'")
            controller.evaluateJavascript(
                "document.dispatchEvent(new CustomEvent('smile-fcm-token', { detail: '$safe' }));",
            )
        }
    }
}

object WebViewBridgeRegistry {
    @Volatile
    private var controller: WebViewController? = null

    fun register(controller: WebViewController) {
        this.controller = controller
    }

    fun get(): WebViewController? = controller

    fun clear() {
        controller = null
    }
}
