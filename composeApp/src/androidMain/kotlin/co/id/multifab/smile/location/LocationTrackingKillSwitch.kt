/**
 * Status: AKTIF
 * Flow: Saklar darurat mematikan tracking lokasi (FGS, tick, capture).
 * Takeout: jangan — set FORCE_OFF = true hanya untuk rilis darurat.
 */
package co.id.multifab.smile.location

import android.content.Context
import co.id.multifab.smile.SmileApplication
import co.id.multifab.smile.util.SmileLog

/**
 * Kill-switch sementara. Saat [FORCE_OFF] = true:
 * tidak ada FGS lokasi, tick GPS, atau capture titik.
 */
object LocationTrackingKillSwitch {
    private const val TAG = "LocationKillSwitch"

    /**
     * true = tracking lokasi dimatikan total (rilis darurat).
     * false = perilaku normal.
     */
    const val FORCE_OFF = false

    fun isOff(): Boolean = FORCE_OFF

    /** Hentikan tracking yang sudah jalan; batalkan notifikasi lokasi lama. */
    fun enforceOff(context: Context = SmileApplication.instance) {
        if (!FORCE_OFF) return
        LocationSessionStore.setEnabled(false)
        LocationSessionStore.clearShiftArm()
        NativeLocationController.stopTracking()
        LocationGpsAlert.cancel(context)
        LocationTrackerService.showPreparationNotification(context)
        SmileLog.i(TAG, "location tracking force-off enforced")
    }
}
