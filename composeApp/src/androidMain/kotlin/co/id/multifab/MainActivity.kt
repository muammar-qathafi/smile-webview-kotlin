/**
 * Status: AKTIF
 * Flow: Activity utama. Memuat WebView, tombol back, izin, dan monitor Fake GPS.
 * Takeout: jangan.
 */
package co.id.multifab

import co.id.multifab.smile.util.SmileLog

import android.Manifest
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import co.id.multifab.smile.App
import co.id.multifab.smile.SmileApplication
import co.id.multifab.smile.bridge.WebViewBridgeRegistry
import co.id.multifab.smile.capacitor.AppStateBridge
import co.id.multifab.smile.fcm.FcmBackendSync
import co.id.multifab.smile.fakegps.FakeGpsGuard
import co.id.multifab.smile.fakegps.FakeGpsLock
import co.id.multifab.smile.fakegps.FakeGpsMonitor
import co.id.multifab.smile.fcm.NotificationHelper
import co.id.multifab.smile.platform.PushNotificationHandler
import co.id.multifab.smile.permission.NativePermissionCoordinator
import co.id.multifab.smile.location.LocationTrackerService
import co.id.multifab.smile.ui.NativeOverlayState
import co.id.multifab.smile.webview.WebViewFileChooser
import co.id.multifab.smile.webview.WebViewNavigationState
import co.id.multifab.smile.webview.WebViewPermissionHandler

class MainActivity : ComponentActivity() {

    /** Global file picker untuk semua &lt;input type="file"&gt; di WebView. */
    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        WebViewFileChooser.onActivityResult(result.resultCode, result.data)
    }

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        WebViewPermissionHandler.onCameraPermissionResult(granted)
    }

    private val backgroundLocationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        runCatching {
            NativePermissionCoordinator.onBackgroundLocationResult(granted)
        }.onFailure { e ->
            SmileLog.e("MainActivity", "Background location callback failed", e)
            NativePermissionCoordinator.onBackgroundLocationResult(false)
        }
    }

    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        runCatching {
            val granted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
            val fromCoordinator = WebViewPermissionHandler.consumeCoordinatorLocationRequest()
            WebViewPermissionHandler.onLocationPermissionResult(granted)
            if (fromCoordinator) {
                NativePermissionCoordinator.onLocationResult(granted)
            } else {
                NativePermissionCoordinator.onWebGeolocationResult(granted)
            }
        }.onFailure { e ->
            SmileLog.e("MainActivity", "Location permission callback failed", e)
            WebViewPermissionHandler.onLocationPermissionResult(false)
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        runCatching {
            PushNotificationHandler.onPermissionResult(granted)
        }.onFailure { e ->
            SmileLog.e("MainActivity", "Notification permission callback failed", e)
            PushNotificationHandler.onPermissionResult(false)
        }
        // Tunda side-effect web sampai activity kembali foreground (dialog sistem → onStop di Honor).
        NativePermissionCoordinator.onNotificationResult()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)

        NotificationHelper.ensureChannel(this)
        PushNotificationHandler.bindActivity(this)
        PushNotificationHandler.registerPermissionRequester {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                PushNotificationHandler.onPermissionResult(
                    androidx.core.app.NotificationManagerCompat.from(this).areNotificationsEnabled(),
                )
            }
        }
        WebViewFileChooser.registerLauncher { intent ->
            runCatching { fileChooserLauncher.launch(intent) }
                .onFailure { e ->
                    SmileLog.e("MainActivity", "File chooser launch failed", e)
                    WebViewFileChooser.onActivityResult(RESULT_CANCELED, null)
                }
        }
        WebViewPermissionHandler.registerCameraPermissionRequester {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
        WebViewPermissionHandler.registerLocationPermissionRequester {
            locationPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                ),
            )
        }
        NativePermissionCoordinator.registerRequesters(
            locationRequester = {
                WebViewPermissionHandler.markCoordinatorLocationRequest()
                locationPermissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                    ),
                )
            },
            backgroundLocationRequester = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    backgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                } else {
                    NativePermissionCoordinator.onBackgroundLocationResult(true)
                }
            },
            notificationRequester = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    PushNotificationHandler.onPermissionResult(
                        androidx.core.app.NotificationManagerCompat.from(this).areNotificationsEnabled(),
                    )
                    NativePermissionCoordinator.onNotificationResult()
                }
            },
            mediaRequester = {
                // Jangan launch READ_MEDIA_* — Play policy; upload lewat system picker.
                NativePermissionCoordinator.onMediaResult(true)
            },
        )
        WebViewNavigationState.bindActivity(this)

        // API 35+: hindari statusBarColor/navigationBarColor deprecated — pakai SystemBarStyle.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.WHITE, Color.WHITE),
            navigationBarStyle = SystemBarStyle.light(Color.WHITE, Color.WHITE),
        )
        // adjustResize|stateHidden sudah di AndroidManifest; WebViewInsetsHelper atur padding IME.

        setContent {
            BackHandler {
                when {
                    NativeOverlayState.showDiagnosis -> {
                        NativeOverlayState.closeDiagnosis()
                        return@BackHandler
                    }
                    NativeOverlayState.showFakeGps || FakeGpsLock.isLocked() -> {
                        // Sticky lock: back tidak melepas overlay; picu recheck fused.
                        FakeGpsMonitor.requestFreshFixNow()
                        if (!FakeGpsLock.isLocked()) {
                            NativeOverlayState.closeFakeGps()
                        }
                        return@BackHandler
                    }
                }
                val controller = WebViewBridgeRegistry.get()
                if (controller?.canGoBack() == true) {
                    controller.goBack()
                } else if (WebViewNavigationState.shouldFinishOnBack(canGoBack = false)) {
                    finish()
                } else if (WebViewNavigationState.isSignInPage()) {
                    Toast.makeText(
                        this@MainActivity,
                        "Tekan kembali sekali lagi untuk keluar",
                        Toast.LENGTH_SHORT,
                    ).show()
                } else {
                    finish()
                }
            }
            App()
        }

        handleLaunchIntent(intent)
    }

    override fun onDestroy() {
        WebViewNavigationState.unbindActivity(this)
        PushNotificationHandler.unbindActivity(this)
        super.onDestroy()
    }

    override fun onStart() {
        super.onStart()
        SmileApplication.isInForeground = true
        PushNotificationHandler.refreshPermissionCache()
        AppStateBridge.dispatch(isActive = true)
        NativePermissionCoordinator.flushPendingNotificationDispatchIfAny()
        FcmBackendSync.ensureRegisteredAsync("onStart")
        co.id.multifab.smile.location.LocationShiftSync.ensureTrackingForCurrentWindow()
        FakeGpsLock.ensureLoaded()
        FakeGpsGuard.syncFromLock()
        FakeGpsMonitor.start()
    }

    override fun onResume() {
        super.onResume()
        // Backup: beberapa OEM mengantar extra hanya lewat getIntent() setelah resume.
        handleLaunchIntent(intent)
        FakeGpsLock.ensureLoaded()
        FakeGpsGuard.syncFromLock()
        FakeGpsMonitor.start()
    }

    override fun onStop() {
        SmileApplication.isInForeground = false
        AppStateBridge.dispatch(isActive = false)
        FakeGpsMonitor.stop()
        super.onStop()
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleLaunchIntent(intent)
    }

    private fun handleLaunchIntent(intent: android.content.Intent?) {
        if (intent == null) return
        // Intent diagnosis dari notifikasi lokasi lama: dibersihkan, overlay tidak dibuka.
        val screen = intent.getStringExtra(LocationTrackerService.EXTRA_NATIVE_SCREEN)
        if (
            screen == LocationTrackerService.SCREEN_DIAGNOSIS ||
            intent.action == LocationTrackerService.ACTION_OPEN_DIAGNOSIS
        ) {
            intent.removeExtra(LocationTrackerService.EXTRA_NATIVE_SCREEN)
            if (intent.action == LocationTrackerService.ACTION_OPEN_DIAGNOSIS) {
                intent.action = Intent.ACTION_MAIN
            }
            return
        }
        intent.getStringExtra("smile_push_title") ?: return
    }
}
