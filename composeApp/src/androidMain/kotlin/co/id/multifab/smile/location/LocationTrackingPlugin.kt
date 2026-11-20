/**
 * Status: AKTIF
 * Flow: Fitur native LocationTracking yang dipanggil web: enable, disable, updateWindow, flush, getStatus.
 * Takeout: jangan.
 */
package co.id.multifab.smile.location

import co.id.multifab.smile.nativeconfig.NativeConfigStore
import co.id.multifab.smile.nativefeature.NativeFeaturePlugin
import org.json.JSONObject

class LocationTrackingPlugin : NativeFeaturePlugin {
    override val name: String = "LocationTracking"
    override val version: Int = 2

    override fun methods(): Set<String> = setOf(
        "enable",
        "disable",
        "updateWindow",
        "flush",
        "getStatus",
    )

    override fun invoke(method: String, options: JSONObject): Any? = when (method) {
        "enable" -> enable(options)
        "disable" -> disable()
        "updateWindow" -> updateWindow(options)
        "flush" -> {
            // Hanya kirim jika antrian mencapai queueFlushSize (bukan force).
            LocationBatchSender.trySendIfDue()
            status().put("ok", true)
        }
        "getStatus" -> status()
        else -> throw IllegalArgumentException("Unknown method: $method")
    }

    private fun enable(options: JSONObject): JSONObject {
        if (LocationTrackingKillSwitch.isOff()) {
            LocationTrackingKillSwitch.enforceOff()
            return JSONObject()
                .put("ok", false)
                .put("error", "location_tracking_force_off")
                .put("enabled", false)
        }
        if (!NativeLocationController.hasLocationPermission()) {
            return JSONObject()
                .put("ok", false)
                .put("error", "location_permission_denied")
        }
        val session = JSONObject().apply {
            put("employeeId", options.optString("employeeId"))
            put("fullName", options.optString("fullName"))
            put("authToken", options.optString("authToken"))
            put("endpointBase", options.optString("endpointBase"))
            put("intervalMs", NativeConfigStore.locationIntervalMs())
        }
        LocationSessionStore.saveSession(session)
        LocationSessionStore.setEnabled(true)
        // Config BE hanya sekali per proses; lalu apply shift lokal.
        NativeConfigStore.refreshOncePerProcessAsync {
            LocationShiftSync.apply()
        }
        LocationShiftSync.apply()
        return status().put("ok", true)
    }

    private fun disable(): JSONObject {
        LocationBatchSender.trySendIfDue()
        LocationSessionStore.setEnabled(false)
        LocationSessionStore.clearShiftArm()
        NativeLocationController.stopTracking()
        LocationTrackingWork.cancelAll()
        LocationSessionStore.clearSession()
        return JSONObject().put("ok", true).put("enabled", false)
    }

    private fun updateWindow(options: JSONObject): JSONObject {
        val session = LocationSessionStore.getSession() ?: JSONObject()
        session.put("intervalMs", NativeConfigStore.locationIntervalMs())
        if (options.has("authToken")) session.put("authToken", options.optString("authToken"))
        if (options.has("fullName")) session.put("fullName", options.optString("fullName"))
        if (options.has("endpointBase")) session.put("endpointBase", options.optString("endpointBase"))
        LocationSessionStore.saveSession(session)
        LocationShiftSync.applyCachedOrNormal()
        return status().put("ok", true)
    }

    private fun status(): JSONObject =
        LocationSessionStore.statusJson(
            tracking = NativeLocationController.isTracking(),
            queuedCount = NativeLocationStore.pendingCount(),
        )
}
