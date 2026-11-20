/**
 * Status: AKTIF
 * Flow: Setelah reboot atau update APK: batalkan alarm pengingat lokal yang masih terpasang,
 * lalu restore alarm START/STOP shift + tracking jika masih dalam jendela / sudah di-arm absen masuk.
 * Takeout: jangan.
 */
package co.id.multifab.smile.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import co.id.multifab.smile.capacitor.LocalNotificationManager
import co.id.multifab.smile.location.LocationShiftSync
import co.id.multifab.smile.location.LocationTrackerService
import co.id.multifab.smile.location.LocationTrackingKillSwitch
import co.id.multifab.smile.location.NativeLocationController
import co.id.multifab.smile.util.SmileLog

/**
 * Setelah reboot / update APK: baca sesi lokal.
 * Dalam shift atau sudah absen masuk (armed) → jadwalkan ulang alarm START/STOP + tick.
 */
class SmileBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (
            action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED &&
            action != Intent.ACTION_LOCKED_BOOT_COMPLETED
        ) {
            return
        }
        val app = context.applicationContext
        LocalNotificationManager.cancelInstalledAlarms()
        if (LocationTrackingKillSwitch.isOff()) {
            // Tracking mati; enforceOff memasang ulang notifikasi persiapan Diagnosis.
            LocationTrackingKillSwitch.enforceOff(app)
            SmileLog.i(TAG, "boot — tracking force-off, diagnosis prep notif shown")
            return
        }
        LocationTrackerService.showPreparationNotification(app)

        val pending = goAsync()
        val pm = app.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val wake = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "smile:boot_location")
        wake?.acquire(60_000L)
        Thread {
            try {
                val phase = LocationShiftSync.apply()
                LocationTrackerService.refreshForegroundNotification(app)
                SmileLog.i(TAG, "boot — phase=$phase tracking=${NativeLocationController.isTracking()}")
            } catch (e: Exception) {
                SmileLog.w(TAG, "boot restore failed: ${e.message}")
            } finally {
                if (wake?.isHeld == true) runCatching { wake.release() }
                pending.finish()
            }
        }.start()
    }

    companion object {
        private const val TAG = "SmileBoot"
    }
}
