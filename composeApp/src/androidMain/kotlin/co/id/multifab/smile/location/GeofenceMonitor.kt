/**
 * Status: SISA
 * Flow: Web masih mengirim daftar area dan status cuti ke sini, tetapi pengecekan keluar area hanya dipanggil dari LocationTrackerService yang stream-nya sudah dimatikan.
 * Takeout: kandidat bersama pemanggilan updateGeofenceConfig di SmileJsBridge, jika peringatan keluar area bukan lagi produk.
 */
package co.id.multifab.smile.location

import org.json.JSONArray
import org.json.JSONObject

object GeofenceMonitor {

    private var areas: JSONArray = JSONArray()
    private var hasLeaveExemption: Boolean = false
    private var lastAlertAt: Long = 0L
    private const val ALERT_COOLDOWN_MS = 5 * 60 * 1000L

    fun updateConfig(json: String) {
        val root = JSONObject(json)
        areas = root.optJSONArray("areas") ?: JSONArray()
        hasLeaveExemption = root.optBoolean("hasLeaveExemption", false)
    }

    fun setLeaveExemption(hasLeave: Boolean) {
        hasLeaveExemption = hasLeave
    }

    fun isInsideWorkArea(lat: Double, lng: Double): Boolean {
        for (i in 0 until areas.length()) {
            val area = areas.optJSONObject(i) ?: continue
            val bounds = area.optJSONObject("bounds") ?: continue
            if (pointInBounds(lat, lng, bounds)) return true
        }
        return areas.length() == 0
    }

    fun shouldAlertOutside(lat: Double, lng: Double): Boolean {
        if (areas.length() == 0) return false
        if (hasLeaveExemption) return false
        if (isInsideWorkArea(lat, lng)) return false
        val now = System.currentTimeMillis()
        if (now - lastAlertAt < ALERT_COOLDOWN_MS) return false
        lastAlertAt = now
        return true
    }

    private fun pointInBounds(lat: Double, lng: Double, bounds: JSONObject): Boolean {
        val north = bounds.optDouble("north", Double.NaN)
        val south = bounds.optDouble("south", Double.NaN)
        val east = bounds.optDouble("east", Double.NaN)
        val west = bounds.optDouble("west", Double.NaN)
        if (listOf(north, south, east, west).any { it.isNaN() }) return false
        val latOk = if (south <= north) lat in south..north else lat in north..south
        val lngOk = if (west <= east) lng in west..east else lng in east..west
        return latOk && lngOk
    }
}
