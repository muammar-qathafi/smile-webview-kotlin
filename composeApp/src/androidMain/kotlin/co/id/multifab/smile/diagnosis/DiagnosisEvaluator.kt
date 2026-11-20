/**
 * Status: AKTIF
 * Flow: Mengecek item diagnosis dari native-config (login, izin lokasi, notifikasi, jaringan, token FCM) lalu menyusun laporan.
 * Takeout: jangan.
 */
package co.id.multifab.smile.diagnosis

import android.app.NotificationManager
import android.content.Context
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import co.id.multifab.smile.SmileApplication
import co.id.multifab.smile.config.AppConfig
import co.id.multifab.smile.fcm.NotificationHelper
import co.id.multifab.smile.location.NativeLocationController
import co.id.multifab.smile.nativeconfig.DiagnosisItemConfig
import co.id.multifab.smile.nativeconfig.NativeAppConfig
import co.id.multifab.smile.nativeconfig.NativeConfigStore
import co.id.multifab.smile.fcm.FcmBackendSync
import co.id.multifab.smile.platform.PushNotificationHandler

data class DiagnosisItemResult(
    val config: DiagnosisItemConfig,
    val passed: Boolean,
    val detail: String,
)

data class DiagnosisReport(
    val items: List<DiagnosisItemResult>,
    val allPassed: Boolean,
    val notificationTitle: String,
    val notificationBody: String,
)

object DiagnosisEvaluator {

    fun evaluate(config: NativeAppConfig = NativeConfigStore.get()): DiagnosisReport {
        val ctx = SmileApplication.instance
        val items = config.diagnosis.items.map { item ->
            val (passed, detail) = evaluateCheck(ctx, item.check)
            DiagnosisItemResult(item, passed, detail)
        }
        val allPassed = items.isNotEmpty() && items.all { it.passed }
        val loc = config.locationNotification
        val body = if (allPassed) loc.readyText else loc.incompleteText
        return DiagnosisReport(
            items = items,
            allPassed = allPassed,
            notificationTitle = loc.title,
            notificationBody = body,
        )
    }

    fun currentReport(): DiagnosisReport = evaluate()

    private fun evaluateCheck(context: Context, check: String): Pair<Boolean, String> {
        return when (check) {
            "POST_NOTIFICATIONS" -> {
                val perm = PushNotificationHandler.getPermission()
                (perm == "granted") to perm
            }
            "ACCESS_FINE_LOCATION" -> {
                val ok = NativeLocationController.hasLocationPermission()
                val detail = when {
                    !ok -> "belum diizinkan"
                    NativeLocationController.hasBackgroundLocationPermission() -> "granted"
                    else -> "saat digunakan"
                }
                ok to detail
            }
            "ACCESS_BACKGROUND_LOCATION" -> {
                // Konsep lama: cukup izin saat digunakan (FGS), tidak wajib Selalu izinkan.
                val ok = NativeLocationController.hasLocationPermission(context)
                ok to if (ok) "saat digunakan" else "belum diizinkan"
            }
            "READ_MEDIA_IMAGES", "READ_MEDIA", "READ_EXTERNAL_STORAGE" -> {
                // Upload lewat system picker — tidak butuh izin broad.
                true to "system picker"
            }
            "GPS_PROVIDER" -> {
                val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
                val gps = lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
                gps to if (gps) "enabled" else "disabled"
            }
            "NETWORK" -> {
                val ok = isNetworkAvailable(context)
                ok to if (ok) "online" else "offline"
            }
            "FIREBASE_CONFIG" -> {
                val ok = PushNotificationHandler.isFirebaseConfigured()
                ok to if (ok) "configured" else "missing"
            }
            "LOGIN" -> co.id.multifab.smile.session.LoginSessionStore.evaluate()
            "FCM_TOKEN" -> {
                val token = PushNotificationHandler.getCachedToken()
                val ok = token.isNotBlank()
                ok to if (ok) "ready" else "empty"
            }
            "FCM_BE_REGISTERED" -> FcmBackendSync.evaluateRegistration()
            "NOTIFICATION_CHANNEL" -> {
                NotificationHelper.ensureChannel(context)
                val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    val nm = context.getSystemService(NotificationManager::class.java)
                        ?: return false to "no_manager"
                    val channel = nm.getNotificationChannel(AppConfig.FCM_CHANNEL_ID)
                    channel != null && channel.importance != NotificationManager.IMPORTANCE_NONE
                } else {
                    NotificationManagerCompat.from(context).areNotificationsEnabled()
                }
                ok to if (ok) "active" else "inactive"
            }
            else -> false to "unknown_check"
        }
    }

    private fun isNetworkAvailable(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
