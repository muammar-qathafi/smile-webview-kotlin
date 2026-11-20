/**
 * Status: AKTIF
 * Flow: Bagian 2 — gerbang simpan lokal. Hanya menerima titik saat dalam jam shift; di luar shift ditolak.
 * Takeout: jangan.
 */
package co.id.multifab.smile.location

import co.id.multifab.smile.util.SmileLog
import org.json.JSONObject

/**
 * Satu-satunya jalur tulis ke [NativeLocationStore].
 * Di luar jendela shift penyimpanan ditolak.
 */
object LocationQueueGate {
    private const val TAG = "LocationQueueGate"

    /**
     * @return true jika titik disimpan di lokal.
     */
    fun offer(point: JSONObject): Boolean {
        if (LocationTrackingKillSwitch.isOff()) {
            SmileLog.d(TAG, "reject save — force-off")
            return false
        }
        if (!LocationShiftSync.currentWindow().inWindow) {
            SmileLog.d(TAG, "reject save — outside shift")
            return false
        }
        NativeLocationStore.appendPoint(point)
        return true
    }
}
