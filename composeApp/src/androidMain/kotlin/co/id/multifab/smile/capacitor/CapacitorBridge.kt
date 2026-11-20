/**
 * Status: COMPAT
 * Flow: Lapisan beku plugin Capacitor: LocalNotifications, Preferences, Filesystem. Fitur baru tidak ditambah di sini.
 * Takeout: jangan selama web masih capacitorInvoke. LocalNotifications juga dipakai jadwal TTS tertunda.
 */
package co.id.multifab.smile.capacitor

import org.json.JSONObject

/**
 * Capacitor compat layer — FROZEN.
 * Jangan menambah method surface baru di sini untuk fitur Smile.
 * Fitur baru → NativeFeatureRegistry + SmileNative.invokeFeature.
 */
object CapacitorBridge {

    fun invoke(plugin: String, method: String, optionsJson: String): String {
        return try {
            val options = if (optionsJson.isBlank()) JSONObject() else JSONObject(optionsJson)
            val data = when (plugin) {
                "LocalNotifications" -> handleLocalNotifications(method, options)
                "Preferences" -> handlePreferences(method, options)
                "Filesystem" -> handleFilesystem(method, options)
                else -> throw IllegalArgumentException("Unknown plugin: $plugin")
            }
            success(data)
        } catch (e: Exception) {
            error(e.message ?: "capacitor_invoke_failed")
        }
    }

    private fun handleLocalNotifications(method: String, options: JSONObject): Any? = when (method) {
        "checkPermissions" -> LocalNotificationManager.checkPermissions()
        "requestPermissions" -> LocalNotificationManager.requestPermissions()
        "createChannel" -> {
            LocalNotificationManager.createChannel(options)
            null
        }
        "schedule" -> {
            LocalNotificationManager.schedule(options)
            null
        }
        "cancel" -> {
            LocalNotificationManager.cancel(options)
            null
        }
        "getPending" -> LocalNotificationManager.getPending()
        "checkExactNotificationSetting" -> LocalNotificationManager.checkExactNotificationSetting()
        "changeExactNotificationSetting" -> LocalNotificationManager.changeExactNotificationSetting()
        else -> throw IllegalArgumentException("Unknown LocalNotifications.$method")
    }

    private fun handlePreferences(method: String, options: JSONObject): JSONObject = when (method) {
        "get" -> {
            val value = PreferencesStore.get(options.getString("key"))
            JSONObject().apply { put("value", value) }
        }
        "set" -> {
            PreferencesStore.set(options.getString("key"), options.getString("value"))
            JSONObject()
        }
        "remove" -> {
            PreferencesStore.remove(options.getString("key"))
            JSONObject()
        }
        else -> throw IllegalArgumentException("Unknown Preferences.$method")
    }

    private fun handleFilesystem(method: String, options: JSONObject): JSONObject = when (method) {
        "writeFile" -> FilesystemManager.writeFile(options)
        "readFile" -> FilesystemManager.readFile(options)
        "stat" -> FilesystemManager.stat(options)
        "mkdir" -> FilesystemManager.mkdir(options)
        else -> throw IllegalArgumentException("Unknown Filesystem.$method")
    }

    private fun success(data: Any?): String {
        val root = JSONObject()
        if (data != null) root.put("data", data)
        return root.toString()
    }

    private fun error(message: String): String =
        JSONObject().put("error", message).toString()
}
