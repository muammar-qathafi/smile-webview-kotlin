/**
 * Status: AKTIF
 * Flow: Kontrak expect untuk push, penyimpanan terenkripsi, dan info perangkat. Implementasi ada di androidMain.
 * Takeout: jangan.
 */
package co.id.multifab.smile.platform

import kotlinx.serialization.Serializable

/** Hasil registrasi / status push notification native. */
@Serializable
data class PushDiagnostics(
    val supported: Boolean = true,
    val permission: String = "default",
    val token: String = "",
    val status: String = "idle",
    val message: String = "",
    val platform: String = "android",
)

/** Payload push yang diteruskan ke WebView via CustomEvent. */
data class PushPayload(
    val title: String,
    val body: String,
    val data: Map<String, String> = emptyMap(),
    val type: String = "",
)

expect object PushNotificationHandler {
    suspend fun requestPermission(): String
    fun getPermission(): String
    suspend fun getToken(forceRefresh: Boolean = false): String
    fun getDiagnostics(requestPermission: Boolean = false): PushDiagnostics
    fun refreshPermissionCache()
    fun openNotificationSettings()
    fun onTokenRefresh(listener: (String) -> Unit)
    fun onMessageReceived(listener: (PushPayload) -> Unit)
}

expect object SecureStorage {
    fun get(key: String): String?
    fun set(key: String, value: String)
    fun remove(key: String)
}

expect object DeviceInfo {
    fun getPlatform(): String
    fun isNative(): Boolean
    fun getDeviceName(): String
    fun getAppVersion(): String
}
