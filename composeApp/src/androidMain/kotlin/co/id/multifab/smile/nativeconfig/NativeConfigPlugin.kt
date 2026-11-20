/**
 * Status: AKTIF
 * Flow: Web memaksa refresh native-config (mis. setelah absen masuk) lalu apply shift tracking.
 */
package co.id.multifab.smile.nativeconfig

import co.id.multifab.smile.nativefeature.NativeFeaturePlugin
import org.json.JSONObject

class NativeConfigPlugin : NativeFeaturePlugin {
    override val name: String = "NativeConfig"
    override val version: Int = 1

    override fun methods(): Set<String> = setOf("refresh")

    override fun invoke(method: String, options: JSONObject): Any? = when (method) {
        "refresh" -> refresh()
        else -> throw IllegalArgumentException("Unknown method: $method")
    }

    private fun refresh(): JSONObject {
        NativeConfigRefresh.afterAttendanceCheckIn()
        return JSONObject().apply {
            put("ok", true)
            put("started", true)
        }
    }
}
