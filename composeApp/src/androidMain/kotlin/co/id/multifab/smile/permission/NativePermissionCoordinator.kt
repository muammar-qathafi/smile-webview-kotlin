/**
 * Status: AKTIF
 * Flow: Rantai izin lokasi → notifikasi setelah login.
 *       Upload file memakai system picker (tanpa READ_MEDIA_*).
 *       Tidak memunculkan dialog di halaman login.
 * Takeout: jangan.
 */
package co.id.multifab.smile.permission

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import co.id.multifab.smile.SmileApplication
import co.id.multifab.smile.location.LocationShiftSync
import co.id.multifab.smile.location.NativeLocationController
import co.id.multifab.smile.platform.PushNotificationHandler
import co.id.multifab.smile.webview.WebViewFileChooser
import co.id.multifab.smile.webview.WebViewNavigationState
import org.json.JSONObject

/**
 * Izin runtime native: lokasi → notifikasi; status untuk Settings.
 * Upload lampiran: system file picker (bukan READ_MEDIA_*).
 */
object NativePermissionCoordinator {

    private const val PREFS = "smile_native_permissions"
    /** v2: rantai termasuk izin media/file. */
    private const val KEY_ONBOARDING_DONE = "onboarding_chain_v2_done"

    /** Grace untuk prompt otomatis dari web/FCM — tidak memblokir rantai lokasi→notifikasi→media. */
    private const val POST_LOGIN_GRACE_MS = 12_000L
    private const val ONBOARDING_DELAY_MS = 2_500L
    private const val CHAIN_LOCATION_TO_NOTIFICATION_MS = 700L
    private const val CHAIN_NOTIFICATION_TO_MEDIA_MS = 700L

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var requestLocation: (() -> Unit)? = null

    @Volatile
    private var requestBackgroundLocation: (() -> Unit)? = null

    @Volatile
    private var requestNotification: (() -> Unit)? = null

    @Volatile
    private var requestMedia: (() -> Unit)? = null

    @Volatile
    private var afterLocationGranted: (() -> Unit)? = null

    @Volatile
    private var onboardingQueuedThisProcess = false

    @Volatile
    private var postLoginGraceUntilMs: Long = 0L

    @Volatile
    private var pendingNotificationDispatch = false

    private const val POST_NOTIFICATION_UI_SETTLE_MS = 400L

    fun registerRequesters(
        locationRequester: () -> Unit,
        notificationRequester: () -> Unit,
        backgroundLocationRequester: (() -> Unit)? = null,
        mediaRequester: (() -> Unit)? = null,
    ) {
        requestLocation = locationRequester
        requestNotification = notificationRequester
        requestBackgroundLocation = backgroundLocationRequester
        requestMedia = mediaRequester
    }

    fun onUserLeftSignInPage() {
        postLoginGraceUntilMs = System.currentTimeMillis() + POST_LOGIN_GRACE_MS
        injectPermissionGraceToWeb(postLoginGraceUntilMs)
    }

    /** Hasil izin lokasi dari rantai onboarding native (lanjut ke notifikasi). */
    fun onLocationResult(granted: Boolean) {
        val chainNext = afterLocationGranted
        afterLocationGranted = null
        if (granted) {
            startTrackingWhenStable()
            if (chainNext != null) {
                scheduleChainAfterLocation(chainNext)
            }
        }
        dispatchStatusEvent()
    }

    fun onBackgroundLocationResult(granted: Boolean) {
        if (granted) {
            startTrackingWhenStable()
        }
        dispatchStatusEvent()
    }

    /**
     * Izin lokasi dari peta / geolocation WebView.
     * Setelah user aktif mengizinkan lokasi, tampilkan notifikasi berikutnya (satu sesi).
     */
    fun onWebGeolocationResult(granted: Boolean) {
        if (granted) {
            startTrackingWhenStable()
            if (!isOnboardingDone() && !isNotificationGranted()) {
                scheduleChainAfterLocation { maybeRequestNotification(bypassLoginGrace = true) }
            }
        }
        dispatchStatusEvent()
    }

    fun onNotificationResult() {
        mainHandler.post {
            runCatching {
                if (!SmileApplication.isInForeground) {
                    pendingNotificationDispatch = true
                    return@post
                }
                pendingNotificationDispatch = false
                scheduleNotificationDispatchWork()
            }.onFailure {
                pendingNotificationDispatch = true
            }
        }
    }

    /** Hasil izin media/file (onboarding atau dari file chooser). */
    fun onMediaResult(granted: Boolean) {
        mainHandler.post {
            runCatching {
                WebViewFileChooser.onMediaPermissionResult(granted)
                // Akhir rantai onboarding: tandai selesai (allow atau deny).
                markOnboardingDone()
                dispatchStatusEvent()
            }
        }
    }

    fun flushPendingNotificationDispatchIfAny() {
        if (!pendingNotificationDispatch) return
        if (!SmileApplication.isInForeground) return
        pendingNotificationDispatch = false
        mainHandler.postDelayed({
            runCatching { scheduleNotificationDispatchWork() }
        }, POST_NOTIFICATION_UI_SETTLE_MS)
    }

    private fun scheduleNotificationDispatchWork() {
        mainHandler.postDelayed({
            runCatching {
                dispatchStatusEvent()
                // Lanjut rantai: notifikasi → media/file.
                scheduleChainAfterNotification {
                    maybeRequestMedia(bypassLoginGrace = true)
                }
            }
        }, POST_NOTIFICATION_UI_SETTLE_MS)
    }

    fun scheduleOnboardingIfNeeded() {
        if (onboardingQueuedThisProcess || isOnboardingDone()) return
        if (WebViewNavigationState.isSignInPage()) return

        onboardingQueuedThisProcess = true
        val delay = if (NativeLocationController.hasLocationPermission()) {
            ONBOARDING_DELAY_MS
        } else {
            ONBOARDING_DELAY_MS.coerceAtLeast(graceRemainingMs())
        }
        mainHandler.postDelayed({
            if (!SmileApplication.isInForeground) {
                mainHandler.postDelayed({ runOnboardingChain(force = false) }, 1_000L)
                return@postDelayed
            }
            runOnboardingChain(force = false)
        }, delay)
    }

    fun runOnboardingChain(force: Boolean) {
        if (!force && isOnboardingDone()) return
        if (!force && WebViewNavigationState.isSignInPage()) return
        if (!force && !canShowSystemPermissionDialogs(bypassLoginGrace = false)) {
            mainHandler.postDelayed({ runOnboardingChain(force) }, graceRemainingMs().coerceAtLeast(1_500L))
            return
        }

        if (WebViewNavigationState.isFormInputActive()) {
            mainHandler.postDelayed({ runOnboardingChain(force) }, 1_500L)
            return
        }

        when {
            !NativeLocationController.hasLocationPermission() ->
                requestLocationInternal(chainNotification = true)
            !isNotificationGranted() ->
                requestNotificationInternal(bypassLoginGrace = true)
            else -> {
                // Upload lampiran pakai system picker — tidak minta READ_MEDIA_*.
                LocationShiftSync.apply()
                markOnboardingDone()
            }
        }
    }

    fun requestLocationFromUser(chainNotificationAfter: Boolean = false) {
        if (NativeLocationController.hasLocationPermission()) {
            LocationShiftSync.apply()
            if (chainNotificationAfter) {
                maybeRequestNotification(bypassLoginGrace = true)
            }
            dispatchStatusEvent()
            return
        }
        requestLocationInternal(chainNotification = chainNotificationAfter)
    }

    /** Tidak meminta Selalu izinkan — FGS cukup dengan izin saat digunakan. */
    fun requestBackgroundLocationFromUser() {
        if (NativeLocationController.hasLocationPermission()) {
            LocationShiftSync.apply()
        } else {
            requestLocationInternal(chainNotification = false)
        }
        dispatchStatusEvent()
    }

    fun openAppLocationSettings() {
        openAppSettings(WebViewNavigationState.activityRef?.get())
    }

    fun requestNotificationFromUser() {
        if (isNotificationGranted()) {
            dispatchStatusEvent()
            return
        }
        if (!canShowSystemPermissionDialogs(bypassLoginGrace = true)) {
            mainHandler.postDelayed({ requestNotificationFromUser() }, 800L)
            return
        }
        requestNotificationInternal(bypassLoginGrace = true)
    }

    /**
     * Kompat bridge/diagnosis: izin media broad dihapus (Play policy).
     * Upload memakai system file picker di WebViewFileChooser — tidak minta READ_MEDIA_*.
     */
    fun requestMediaFromUser(fromFileChooser: Boolean = false) {
        if (fromFileChooser) {
            WebViewFileChooser.onMediaPermissionResult(true)
        }
        markOnboardingDone()
        dispatchStatusEvent()
    }

    fun getStatusJson(): String = buildStatusJson().toString()

    fun openAppSettings(activity: Activity?) {
        val ctx = activity ?: SmileApplication.instance
        val pkg = ctx.packageName
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", pkg, null)
            if (activity == null) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { ctx.startActivity(intent) }
    }

    fun openNotificationSettings() {
        PushNotificationHandler.openNotificationSettings()
    }

    private fun requestLocationInternal(chainNotification: Boolean) {
        if (chainNotification) {
            afterLocationGranted = { maybeRequestNotification(bypassLoginGrace = true) }
        }
        val requester = requestLocation
        if (requester == null) {
            afterLocationGranted = null
            return
        }
        mainHandler.post { requester.invoke() }
    }

    private fun requestNotificationInternal(bypassLoginGrace: Boolean) {
        if (!canShowSystemPermissionDialogs(bypassLoginGrace)) return
        val requester = requestNotification
        if (requester == null) return
        mainHandler.post { requester.invoke() }
    }

    private fun requestMediaInternal(bypassLoginGrace: Boolean) {
        if (!canShowSystemPermissionDialogs(bypassLoginGrace)) return
        val requester = requestMedia
        if (requester == null) {
            // APK lama tanpa launcher media: anggap selesai agar rantai tidak macet.
            markOnboardingDone()
            return
        }
        mainHandler.post { requester.invoke() }
    }

    private fun maybeRequestNotification(bypassLoginGrace: Boolean = false) {
        if (isNotificationGranted()) {
            maybeRequestMedia(bypassLoginGrace = true)
            return
        }
        requestNotificationInternal(bypassLoginGrace)
    }

    private fun maybeRequestMedia(@Suppress("UNUSED_PARAMETER") bypassLoginGrace: Boolean = false) {
        // Tidak minta READ_MEDIA_* — upload lewat system picker.
        markOnboardingDone()
        dispatchStatusEvent()
    }

    private fun scheduleChainAfterLocation(chainNext: () -> Unit) {
        mainHandler.postDelayed({
            if (!canShowSystemPermissionDialogs(bypassLoginGrace = true)) {
                mainHandler.postDelayed({ scheduleChainAfterLocation(chainNext) }, 500L)
                return@postDelayed
            }
            runCatching { chainNext.invoke() }
        }, CHAIN_LOCATION_TO_NOTIFICATION_MS)
    }

    private fun scheduleChainAfterNotification(chainNext: () -> Unit) {
        mainHandler.postDelayed({
            if (!canShowSystemPermissionDialogs(bypassLoginGrace = true)) {
                mainHandler.postDelayed({ scheduleChainAfterNotification(chainNext) }, 500L)
                return@postDelayed
            }
            runCatching { chainNext.invoke() }
        }, CHAIN_NOTIFICATION_TO_MEDIA_MS)
    }

    private fun canShowSystemPermissionDialogs(bypassLoginGrace: Boolean): Boolean {
        if (!SmileApplication.isInForeground) return false
        if (WebViewNavigationState.isSignInPage()) return false
        if (!bypassLoginGrace && System.currentTimeMillis() < postLoginGraceUntilMs) return false
        return true
    }

    private fun graceRemainingMs(): Long {
        val left = postLoginGraceUntilMs - System.currentTimeMillis()
        return if (left > 0L) left else 0L
    }

    private fun startTrackingWhenStable() {
        if (WebViewNavigationState.isSignInPage()) return
        if (System.currentTimeMillis() < postLoginGraceUntilMs) {
            mainHandler.postDelayed({ startTrackingWhenStable() }, graceRemainingMs().coerceAtLeast(500L))
            return
        }
        LocationShiftSync.apply()
        co.id.multifab.smile.fakegps.FakeGpsMonitor.start()
    }

    private fun injectPermissionGraceToWeb(untilMs: Long) {
        WebViewNavigationState.evaluateOnWeb(
            """
            (function() {
              window.__smilePermissionGraceUntil = $untilMs;
            })();
            """.trimIndent(),
        )
    }

    private fun isNotificationGranted(): Boolean {
        val ctx = SmileApplication.instance
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            NotificationManagerCompat.from(ctx).areNotificationsEnabled()
        }
    }

    private fun locationStatus(): StatusRow {
        val ctx = SmileApplication.instance
        val granted = NativeLocationController.hasLocationPermission(ctx)
        if (granted) {
            val label = if (NativeLocationController.hasBackgroundLocationPermission(ctx)) {
                "granted"
            } else {
                "saat digunakan"
            }
            return StatusRow(true, label)
        }
        val activity = WebViewNavigationState.activityRef?.get()
        val showRationale = activity?.let {
            ActivityCompat.shouldShowRequestPermissionRationale(it, Manifest.permission.ACCESS_FINE_LOCATION)
        } ?: false
        return StatusRow(false, if (showRationale) "ditolak — bisa minta lagi" else "tidak aktif")
    }

    private fun notificationStatus(): StatusRow {
        PushNotificationHandler.refreshPermissionCache()
        val perm = PushNotificationHandler.getPermission()
        return when (perm) {
            "granted" -> StatusRow(true, "aktif")
            "denied" -> StatusRow(false, "ditolak — buka pengaturan")
            else -> StatusRow(false, "tidak aktif")
        }
    }

    private fun mediaStatus(): StatusRow {
        // Play: tidak pakai READ_MEDIA_* — upload lewat system picker.
        return StatusRow(true, "system picker")
    }

    private fun buildStatusJson(): JSONObject {
        val loc = locationStatus()
        val notif = notificationStatus()
        val media = mediaStatus()
        return JSONObject().apply {
            put("locationGranted", loc.granted)
            put("locationStatus", loc.label)
            put("notificationGranted", notif.granted)
            put("notificationStatus", notif.label)
            put("mediaGranted", media.granted)
            put("mediaStatus", media.label)
            put("onboardingComplete", isOnboardingDone())
        }
    }

    private fun dispatchStatusEvent() {
        val detail = buildStatusJson().toString()
        WebViewNavigationState.evaluateOnWeb(
            """
            (function() {
              var detail = $detail;
              document.dispatchEvent(new CustomEvent('smile-native-permissions-status', { detail: detail }));
            })();
            """.trimIndent(),
        )
    }

    private fun prefs() =
        SmileApplication.instance.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)

    private fun isOnboardingDone(): Boolean = prefs().getBoolean(KEY_ONBOARDING_DONE, false)

    private fun markOnboardingDone() {
        prefs().edit().putBoolean(KEY_ONBOARDING_DONE, true).apply()
    }

    private data class StatusRow(val granted: Boolean, val label: String)
}
