/**
 * Status: AKTIF
 * Flow: POST alasan Fake GPS ke /api/v1/home/attendance-fake-gps dengan token login.
 * Takeout: jangan jika audit Fake GPS ke ERP masih dipakai.
 */
package co.id.multifab.smile.fakegps

import co.id.multifab.smile.config.AppConfig
import co.id.multifab.smile.fcm.FcmBackendSync
import co.id.multifab.smile.location.LocationSessionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object FakeGpsLogger {
    fun logAsync(reason: String) {
        val token = LocationSessionStore.getSession()?.optString("authToken").orEmpty().trim()
            .ifBlank { FcmBackendSync.cachedErpToken.trim() }
        if (token.isBlank()) return

        CoroutineScope(Dispatchers.IO).launch {
            runCatching {
                val url = URL(AppConfig.ERP_API_BASE.trimEnd('/') + "/api/v1/home/attendance-fake-gps")
                val body = JSONObject().apply {
                    put("reason", reason)
                    put("detection_reason", reason)
                    put("detection_type", "native_mock")
                    put("source", "smile_native")
                    put(
                        "raw_payload",
                        JSONObject().apply {
                            put("reason", reason)
                            put("platform", "android")
                            put("detection_type", "native_mock")
                        },
                    )
                }
                val conn = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 15_000
                    readTimeout = 15_000
                    doOutput = true
                    setRequestProperty("Accept", "application/json")
                    setRequestProperty("Content-Type", "application/json")
                    setRequestProperty("Authorization", "Bearer $token")
                }
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                conn.responseCode
                conn.disconnect()
            }
        }
    }
}
