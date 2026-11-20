/**
 * Status: AKTIF
 * Flow: Daftar dan pemanggil fitur native. LocationTracking, NativeConfig (refresh setelah absen).
 * Takeout: jangan. Fitur yang tidak terdaftar mengembalikan unknown_feature ke web.
 */
package co.id.multifab.smile.nativefeature

import org.json.JSONArray
import org.json.JSONObject

object NativeFeatureRegistry {
    private val plugins = linkedMapOf<String, NativeFeaturePlugin>()

    fun register(plugin: NativeFeaturePlugin) {
        plugins[plugin.name] = plugin
    }

    fun get(name: String): NativeFeaturePlugin? = plugins[name]

    fun list(): JSONArray {
        val arr = JSONArray()
        plugins.values.forEach { plugin ->
            arr.put(
                JSONObject().apply {
                    put("name", plugin.name)
                    put("version", plugin.version)
                    put("methods", JSONArray(plugin.methods().toList()))
                },
            )
        }
        return arr
    }

    fun invoke(feature: String, method: String, optionsJson: String): String {
        return try {
            val plugin = plugins[feature]
                ?: return error("unknown_feature", "Feature not registered: $feature")
            if (method !in plugin.methods()) {
                return error("unknown_method", "Unknown $feature.$method")
            }
            val options = if (optionsJson.isBlank()) JSONObject() else JSONObject(optionsJson)
            val data = plugin.invoke(method, options)
            success(data)
        } catch (e: Exception) {
            error(e.message ?: "invoke_failed", e.message ?: "invoke_failed")
        }
    }

    private fun success(data: Any?): String {
        val root = JSONObject()
        if (data != null) root.put("data", data)
        return root.toString()
    }

    private fun error(code: String, message: String): String =
        JSONObject()
            .put("error", message)
            .put("code", code)
            .toString()
}
