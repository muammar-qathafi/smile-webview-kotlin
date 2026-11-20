/**
 * Status: AKTIF
 * Flow: Kontrak fitur native baru (bukan plugin Capacitor). Web memanggil lewat SmileNative.invokeFeature.
 * Takeout: jangan.
 */
package co.id.multifab.smile.nativefeature

import org.json.JSONObject

/**
 * Kontrak fitur native baru (bukan Capacitor plugin lama).
 * CapacitorBridge tetap frozen — fitur baru lewat registry + SmileNative.invokeFeature.
 */
interface NativeFeaturePlugin {
    val name: String
    val version: Int
    fun methods(): Set<String>
    fun invoke(method: String, options: JSONObject): Any?
}
