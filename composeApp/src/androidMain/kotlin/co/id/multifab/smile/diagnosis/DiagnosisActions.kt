/**
 * Status: AKTIF
 * Flow: Aksi tombol di layar diagnosis: minta izin, buka pengaturan, daftarkan push ke backend.
 * Takeout: jangan.
 */
package co.id.multifab.smile.diagnosis

import android.content.Intent
import android.provider.Settings
import co.id.multifab.smile.SmileApplication
import co.id.multifab.smile.fcm.FcmBackendSync
import co.id.multifab.smile.location.LocationTrackerService
import co.id.multifab.smile.permission.NativePermissionCoordinator
import co.id.multifab.smile.platform.PushNotificationHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object DiagnosisActions {
    fun runAction(action: String?) {
        when (action) {
            "request_notification" -> NativePermissionCoordinator.requestNotificationFromUser()
            "request_location" -> NativePermissionCoordinator.requestLocationFromUser(chainNotificationAfter = false)
            "request_media" -> NativePermissionCoordinator.requestMediaFromUser(fromFileChooser = false)
            "open_app_location_settings" -> NativePermissionCoordinator.openAppLocationSettings()
            "open_location_settings" -> openLocationSettings()
            "register_push" -> {
                if (PushNotificationHandler.isFirebaseConfigured()) {
                    CoroutineScope(Dispatchers.IO).launch {
                        val cached = PushNotificationHandler.getCachedToken()
                        if (cached.isBlank()) {
                            runCatching { PushNotificationHandler.getToken(forceRefresh = false) }
                        }
                        FcmBackendSync.ensureRegistered()
                        LocationTrackerService.refreshForegroundNotification(SmileApplication.instance)
                    }
                }
            }
            "sync_fcm_to_backend" -> {
                CoroutineScope(Dispatchers.IO).launch {
                    runCatching { FcmBackendSync.ensureRegistered(forceSync = true) }
                    LocationTrackerService.refreshForegroundNotification(SmileApplication.instance)
                }
            }
            "retry", null -> Unit
            else -> Unit
        }
        LocationTrackerService.refreshForegroundNotification(SmileApplication.instance)
    }

    private fun openLocationSettings() {
        val ctx = SmileApplication.instance
        val intent = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { ctx.startActivity(intent) }
    }
}
