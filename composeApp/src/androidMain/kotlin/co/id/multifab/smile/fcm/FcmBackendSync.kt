/**
 * Status: AKTIF
 * Flow: Mendaftarkan token FCM + badge user ke API ERP supaya /send-notif sampai ke perangkat ini.
 * Takeout: jangan.
 */
package co.id.multifab.smile.fcm

import co.id.multifab.smile.util.SmileLog

import android.content.Context
import android.os.Build
import co.id.multifab.smile.SmileApplication
import co.id.multifab.smile.config.AppConfig
import co.id.multifab.smile.location.LocationSessionStore
import co.id.multifab.smile.location.LocationTrackerService
import co.id.multifab.smile.platform.PushNotificationHandler
import co.id.multifab.smile.session.LoginSessionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

object FcmBackendSync {
    private const val TAG = "FcmBackendSync"
    private const val PREFS = "smile_fcm_backend_sync"
    private const val KEY_SYNCED_MARKER = "synced_badge_token"
    private const val KEY_DEVICE_KEY = "native_device_key"

    val cachedErpToken: String
        get() = LoginSessionStore.authToken()

    val cachedBadge: String
        get() = LoginSessionStore.badge()

    val cachedSite: String
        get() = LoginSessionStore.site()

    val cachedWebFcmSynced: String
        get() = LoginSessionStore.webFcmSynced()

    private val inFlight = AtomicBoolean(false)

    @Volatile
    private var pendingRetry = false

    fun clearSyncMarkerIfTokenChanged(newToken: String) {
        val marker = prefs().getString(KEY_SYNCED_MARKER, null) ?: return
        val tokenPart = marker.substringAfter('|', "")
        if (tokenPart.isNotBlank() && tokenPart != newToken) {
            prefs().edit().remove(KEY_SYNCED_MARKER).apply()
        }
    }

    /**
     * Dipanggil saat app dibuka / WebView siap / token FCM berubah / setelah login.
     * Token FCM di-fetch jika cache kosong. POST ke BE hanya jika token belum ada di server.
     */
    fun ensureRegisteredAsync(reason: String = "app") {
        if (!PushNotificationHandler.isFirebaseConfigured()) return
        CoroutineScope(Dispatchers.IO).launch {
            val result = ensureRegistered()
            result.onSuccess { SmileLog.i(TAG, "$reason: $it") }
                .onFailure { SmileLog.w(TAG, "$reason: ${it.message}") }
            runCatching {
                LocationTrackerService.refreshForegroundNotification(SmileApplication.instance)
            }
        }
    }

    suspend fun ensureRegistered(forceSync: Boolean = false): Result<String> {
        if (!inFlight.compareAndSet(false, true) && !forceSync) {
            pendingRetry = true
            return Result.success("Sedang diproses")
        }
        return try {
            syncToBackend(force = forceSync)
        } finally {
            inFlight.set(false)
            if (pendingRetry) {
                pendingRetry = false
                ensureRegisteredAsync("retry")
            }
        }
    }

    fun evaluateRegistration(): Pair<Boolean, String> {
        val fcmToken = PushNotificationHandler.getCachedToken().trim()
        if (fcmToken.isBlank()) return false to "Token FCM kosong"

        if (!LoginSessionStore.isLoggedIn()) {
            return false to "Belum login — token ERP tidak ada"
        }

        val badge = LoginSessionStore.badge().trim()
        if (badge.isBlank()) return false to "Badge login tidak dikenali"

        val marker = prefs().getString(KEY_SYNCED_MARKER, null)
        val expected = syncMarker(badge, fcmToken)
        if (marker == expected) return true to "Terdaftar (badge $badge)"

        if (LoginSessionStore.webFcmSynced() == fcmToken && badge.isNotBlank()) {
            markSynced(badge, fcmToken)
            return true to "Terdaftar (badge $badge)"
        }

        return false to "Belum disimpan ke server untuk perangkat ini"
    }

    suspend fun refreshWebSessionCache() {
        LoginSessionStore.refreshFromWeb()
        LoginSessionStore.fetchProfileIfNeeded()
    }

    /**
     * @param force kirim ulang ke BE meski marker sudah sama (aksi diagnosis).
     * Token FCM selalu di-reuse dari cache jika sudah ada.
     */
    suspend fun syncToBackend(force: Boolean = false): Result<String> = withContext(Dispatchers.IO) {
        refreshWebSessionCache()

        val fcmToken = resolveFcmToken()
        if (fcmToken.isBlank()) {
            return@withContext Result.failure(IllegalStateException("Token FCM belum tersedia"))
        }

        val auth = resolveAuthToken().trim()
        if (auth.isBlank()) {
            return@withContext Result.failure(IllegalStateException("Token login tidak tersedia — login dulu di Smile"))
        }

        val badge = resolveBadge(auth).trim()
        if (badge.isBlank()) {
            return@withContext Result.failure(IllegalStateException("Badge tidak dikenali"))
        }

        if (!force) {
            val marker = prefs().getString(KEY_SYNCED_MARKER, null)
            if (marker == syncMarker(badge, fcmToken)) {
                return@withContext Result.success("Sudah terdaftar")
            }
            if (LoginSessionStore.webFcmSynced() == fcmToken) {
                markSynced(badge, fcmToken)
                return@withContext Result.success("Sudah terdaftar (web)")
            }
            when (lookupExistingBackendToken(auth, fcmToken)) {
                BackendTokenLookup.Match -> {
                    markSynced(badge, fcmToken)
                    return@withContext Result.success("Sudah terdaftar di server")
                }
                BackendTokenLookup.Unknown -> Unit
                BackendTokenLookup.Missing -> Unit
            }
        }

        val body = JSONObject().apply {
            put("fcm_id", fcmToken)
            put("device", buildDeviceName())
            put("send_login_notification", false)
        }

        val url = URL("${AppConfig.ERP_API_BASE}/api/v1/profile/fcm-token")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = 20_000
            doOutput = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $auth")
        }

        return@withContext try {
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val responseText = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val msg = runCatching {
                    JSONObject(responseText).optString("message")
                }.getOrNull()?.takeIf { it.isNotBlank() }
                    ?: "Gagal menyimpan FCM (HTTP $code)"
                Result.failure(IllegalStateException(msg))
            } else {
                markSynced(badge, fcmToken)
                Result.success("Token FCM terdaftar untuk badge $badge")
            }
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            conn.disconnect()
        }
    }

    /** Cache dulu; Firebase hanya jika token belum ada. */
    private suspend fun resolveFcmToken(): String {
        val cached = PushNotificationHandler.getCachedToken().trim()
        if (cached.isNotBlank()) return cached
        return runCatching {
            PushNotificationHandler.getToken(forceRefresh = false)
        }.getOrNull()?.trim().orEmpty()
    }

    private fun markSynced(badge: String, fcmToken: String) {
        prefs().edit().putString(KEY_SYNCED_MARKER, syncMarker(badge, fcmToken)).apply()
    }

    private fun syncMarker(badge: String, fcmToken: String) = "$badge|$fcmToken"

    fun currentAuthToken(): String = resolveAuthToken()

    fun currentSiteCode(): String = LoginSessionStore.site()

    private fun resolveAuthToken(): String {
        val sessionToken = LocationSessionStore.getSession()?.optString("authToken").orEmpty().trim()
        if (sessionToken.isNotBlank() && LoginSessionStore.isTokenUsable(sessionToken)) return sessionToken
        val fromLogin = LoginSessionStore.authToken()
        if (fromLogin.isNotBlank()) return fromLogin
        return ""
    }

    private fun resolveBadge(authToken: String): String {
        val fromLogin = LoginSessionStore.badge().trim()
        if (fromLogin.isNotBlank()) return fromLogin
        val session = LocationSessionStore.getSession()
        val fromSession = session?.optString("badge").orEmpty().trim()
            .ifBlank { session?.optString("employeeId").orEmpty().trim() }
        if (fromSession.isNotBlank()) return fromSession
        return LoginSessionStore.badgeFromJwt(authToken)
    }

    private fun lookupExistingBackendToken(auth: String, fcmToken: String): BackendTokenLookup {
        val url = URL("${AppConfig.ERP_API_BASE.trimEnd('/')}/api/v1/profile/fcm-token")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12_000
            readTimeout = 12_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Authorization", "Bearer $auth")
        }
        return try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code == 404 || code == 405) return BackendTokenLookup.Unknown
            if (code !in 200..299) {
                SmileLog.w(TAG, "GET fcm-token HTTP $code")
                return BackendTokenLookup.Unknown
            }
            val tokens = extractFcmIds(text)
            if (tokens.any { it == fcmToken }) BackendTokenLookup.Match else BackendTokenLookup.Missing
        } catch (e: Exception) {
            SmileLog.w(TAG, "GET fcm-token: ${e.message}")
            BackendTokenLookup.Unknown
        } finally {
            conn.disconnect()
        }
    }

    private fun extractFcmIds(text: String): List<String> {
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return emptyList()
        val arr: JSONArray = when {
            root.optJSONArray("tokens") != null -> root.getJSONArray("tokens")
            root.optJSONArray("data") != null -> root.getJSONArray("data")
            else -> JSONArray()
        }
        val ids = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i)
            val id = when {
                item != null -> item.optString("fcm_id").ifBlank { item.optString("fcm_token") }.trim()
                else -> arr.optString(i).trim()
            }
            if (id.isNotBlank()) ids.add(id)
        }
        root.optString("fcm_id").trim().takeIf { it.isNotBlank() }?.let { ids.add(it) }
        return ids
    }

    private fun buildDeviceName(): String {
        val key = prefs().getString(KEY_DEVICE_KEY, null)
            ?: "android-${UUID.randomUUID().toString().take(8)}".also {
                prefs().edit().putString(KEY_DEVICE_KEY, it).apply()
            }
        val model = Build.MODEL?.trim().orEmpty().ifBlank { "Android" }
        return "Smile Multifab Android • $model • $key".take(190)
    }

    private fun prefs() =
        SmileApplication.instance.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private enum class BackendTokenLookup { Match, Missing, Unknown }
}
