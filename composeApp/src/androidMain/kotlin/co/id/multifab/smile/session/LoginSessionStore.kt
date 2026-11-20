/**
 * Status: AKTIF
 * Flow: Menyalin token dan profil dari localStorage web ke cache native. Dipakai FCM, upload lokasi, log Fake GPS, dan diagnosis.
 * Takeout: jangan.
 */
package co.id.multifab.smile.session

import co.id.multifab.smile.util.SmileLog

import android.content.Context
import android.util.Base64
import co.id.multifab.smile.SmileApplication
import co.id.multifab.smile.bridge.WebViewBridgeRegistry
import co.id.multifab.smile.config.AppConfig
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resume

/**
 * Sesi login native: token + profil dari localStorage web, di-cache SharedPreferences.
 * Dipakai diagnosis, registrasi FCM, reminder, dan cek lain yang perlu tahu siapa yang login.
 */
object LoginSessionStore {
    private const val TAG = "LoginSessionStore"
    private const val PREFS = "smile_login_session"
    private const val KEY_ERP_TOKEN = "erp_token"
    private const val KEY_BADGE = "badge"
    private const val KEY_NAME = "name"
    private const val KEY_SITE = "site"
    private const val KEY_PROFILE_JSON = "profile_json"
    private const val KEY_FCM_SYNCED_WEB = "fcm_token_synced_web"

    private const val LS_ERP_TOKEN = "smile_erp_token_v1"
    private const val LS_BADGE = "smile_badge_number_v1"
    private const val LS_NAME = "smile_employee_name_v1"
    private const val LS_SITE = "smile_erp_site_v1"
    private const val LS_PROFILE = "erp_employee_profile_v1"
    private const val LS_FCM_SYNCED = "fcm_token_synced_v2"

    @Volatile
    var cachedErpToken: String = ""
        private set

    @Volatile
    var cachedBadge: String = ""
        private set

    @Volatile
    var cachedName: String = ""
        private set

    @Volatile
    var cachedSite: String = ""
        private set

    @Volatile
    var cachedProfileJson: String = ""
        private set

    @Volatile
    var cachedWebFcmSynced: String = ""
        private set

    fun isLoggedIn(): Boolean {
        hydrateFromPrefsIfNeeded()
        return isTokenUsable(cachedErpToken) && cachedBadge.isNotBlank()
    }

    fun authToken(): String {
        hydrateFromPrefsIfNeeded()
        return if (isTokenUsable(cachedErpToken)) cachedErpToken else ""
    }

    fun badge(): String {
        hydrateFromPrefsIfNeeded()
        return cachedBadge
    }

    fun displayName(): String {
        hydrateFromPrefsIfNeeded()
        return cachedName
    }

    fun site(): String {
        hydrateFromPrefsIfNeeded()
        return cachedSite.ifBlank { "A001" }
    }

    fun webFcmSynced(): String {
        hydrateFromPrefsIfNeeded()
        return cachedWebFcmSynced
    }

    fun evaluate(): Pair<Boolean, String> {
        hydrateFromPrefsIfNeeded()
        val token = cachedErpToken.trim()
        if (token.isBlank()) return false to "Belum login"
        if (!isTokenUsable(token)) return false to "Sesi login kedaluwarsa"
        val badge = cachedBadge.trim()
        if (badge.isBlank()) return false to "Profil belum ada di cache"
        val name = cachedName.trim()
        return true to if (name.isNotBlank()) "$badge · $name" else badge
    }

    suspend fun refreshFromWeb() {
        hydrateFromPrefsIfNeeded()
        val raw = readLocalStorageBundle()
        if (raw.isBlank()) return
        val obj = runCatching { JSONObject(raw) }.getOrNull() ?: return

        val token = obj.optString("token").trim()
        val badge = obj.optString("badge").trim()
        val name = obj.optString("name").trim()
        val site = obj.optString("site").trim()
        val profile = obj.optString("profile").trim()
        val fcmSynced = obj.optString("fcmSynced").trim()

        val fromProfile = parseProfileFields(profile)
        applySession(
            token = token.ifBlank { cachedErpToken },
            badge = badge.ifBlank { fromProfile.badge }.ifBlank { cachedBadge },
            name = name.ifBlank { fromProfile.name }.ifBlank { cachedName },
            site = site.ifBlank { fromProfile.site }.ifBlank { cachedSite },
            profileJson = profile.ifBlank { cachedProfileJson },
            webFcmSynced = fcmSynced.ifBlank { cachedWebFcmSynced },
        )
        if (cachedBadge.isBlank() && cachedErpToken.isNotBlank()) {
            badgeFromJwt(cachedErpToken).takeIf { it.isNotBlank() }?.let { jwtBadge ->
                cachedBadge = jwtBadge
                prefs().edit().putString(KEY_BADGE, jwtBadge).apply()
            }
        }
    }

    /** GET /api/v1/profile jika token ada tapi profil/badge masih kosong. */
    suspend fun fetchProfileIfNeeded(): Boolean {
        hydrateFromPrefsIfNeeded()
        val token = authToken()
        if (token.isBlank()) return false
        if (cachedBadge.isNotBlank() && cachedProfileJson.isNotBlank()) return true
        return fetchProfileFromApi()
    }

    fun fetchProfileFromApi(): Boolean {
        val token = authToken()
        if (token.isBlank()) return false
        val url = URL("${AppConfig.ERP_API_BASE.trimEnd('/')}/api/v1/profile")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Authorization", "Bearer $token")
        }
        return try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                SmileLog.w(TAG, "GET profile HTTP $code")
                return false
            }
            val root = JSONObject(text)
            val employee = root.optJSONObject("employee") ?: root
            val fields = extractEmployeeFields(employee)
            applySession(
                token = cachedErpToken,
                badge = fields.badge.ifBlank { cachedBadge },
                name = fields.name.ifBlank { cachedName },
                site = fields.site.ifBlank { cachedSite },
                profileJson = employee.toString(),
                webFcmSynced = cachedWebFcmSynced,
            )
            true
        } catch (e: Exception) {
            SmileLog.w(TAG, "GET profile: ${e.message}")
            false
        } finally {
            conn.disconnect()
        }
    }

    private fun applySession(
        token: String,
        badge: String,
        name: String,
        site: String,
        profileJson: String,
        webFcmSynced: String,
    ) {
        if (token.isNotBlank()) cachedErpToken = token
        if (badge.isNotBlank()) cachedBadge = badge
        if (name.isNotBlank()) cachedName = name
        if (site.isNotBlank()) cachedSite = site
        if (profileJson.isNotBlank()) cachedProfileJson = profileJson
        if (webFcmSynced.isNotBlank()) cachedWebFcmSynced = webFcmSynced
        prefs().edit()
            .putString(KEY_ERP_TOKEN, cachedErpToken)
            .putString(KEY_BADGE, cachedBadge)
            .putString(KEY_NAME, cachedName)
            .putString(KEY_SITE, cachedSite)
            .putString(KEY_PROFILE_JSON, cachedProfileJson)
            .putString(KEY_FCM_SYNCED_WEB, cachedWebFcmSynced)
            .apply()
    }

    private fun hydrateFromPrefsIfNeeded() {
        if (cachedErpToken.isNotBlank() || cachedBadge.isNotBlank()) return
        val p = prefs()
        cachedErpToken = p.getString(KEY_ERP_TOKEN, "").orEmpty()
        cachedBadge = p.getString(KEY_BADGE, "").orEmpty()
        cachedName = p.getString(KEY_NAME, "").orEmpty()
        cachedSite = p.getString(KEY_SITE, "").orEmpty()
        cachedProfileJson = p.getString(KEY_PROFILE_JSON, "").orEmpty()
        cachedWebFcmSynced = p.getString(KEY_FCM_SYNCED_WEB, "").orEmpty()
        if (cachedBadge.isBlank() && cachedProfileJson.isNotBlank()) {
            val fields = parseProfileFields(cachedProfileJson)
            if (fields.badge.isNotBlank()) cachedBadge = fields.badge
            if (cachedName.isBlank()) cachedName = fields.name
            if (cachedSite.isBlank()) cachedSite = fields.site
        }
        if (cachedBadge.isBlank() && cachedErpToken.isNotBlank()) {
            cachedBadge = badgeFromJwt(cachedErpToken)
        }
    }

    private fun parseProfileFields(raw: String): ProfileFields {
        if (raw.isBlank()) return ProfileFields()
        val obj = runCatching { JSONObject(raw) }.getOrNull() ?: return ProfileFields()
        val employee = obj.optJSONObject("employee") ?: obj
        return extractEmployeeFields(employee)
    }

    private fun extractEmployeeFields(employee: JSONObject): ProfileFields {
        val badge = firstNonBlank(
            employee,
            "badge_number",
            "badgeNumber",
            "badge",
            "employee_id",
            "employeeId",
        )
        val name = firstNonBlank(
            employee,
            "full_name",
            "fullName",
            "name",
            "username",
            "email",
        )
        val site = firstNonBlank(
            employee,
            "site",
            "site_name",
            "siteName",
            "site_id",
        )
        return ProfileFields(badge, name, site)
    }

    private fun firstNonBlank(obj: JSONObject, vararg keys: String): String {
        for (key in keys) {
            val v = obj.optString(key).trim()
            if (v.isNotBlank()) return v
        }
        return ""
    }

    fun isTokenUsable(token: String): Boolean {
        val raw = token.trim()
        if (raw.isBlank()) return false
        val parts = raw.split('.')
        if (parts.size < 2) return raw.isNotBlank()
        val payload = decodeJwtPayload(raw) ?: return true
        val exp = payload.optLong("exp", 0L)
        if (exp <= 0L) return true
        return exp * 1000L > System.currentTimeMillis() - 30_000L
    }

    fun badgeFromJwt(token: String): String {
        val obj = decodeJwtPayload(token) ?: return ""
        val emp = obj.optJSONObject("emp")
        val fromEmp = emp?.let {
            firstNonBlank(it, "badge_number", "badgeNumber", "badge", "employee_id", "employeeId")
        }.orEmpty()
        if (fromEmp.isNotBlank()) return fromEmp
        return firstNonBlank(
            obj,
            "badge_number",
            "badgeNumber",
            "badge",
            "employee_id",
            "employeeId",
            "sub",
        )
    }

    private fun decodeJwtPayload(token: String): JSONObject? {
        val parts = token.split('.')
        if (parts.size < 2) return null
        val payload = parts[1]
        val padded = payload + "=".repeat((4 - payload.length % 4) % 4)
        val json = runCatching {
            String(Base64.decode(padded, Base64.URL_SAFE or Base64.NO_WRAP), Charsets.UTF_8)
        }.getOrNull() ?: return null
        return runCatching { JSONObject(json) }.getOrNull()
    }

    private suspend fun readLocalStorageBundle(): String {
        val script = """
            (function(){
              try {
                return JSON.stringify({
                  token: localStorage.getItem('$LS_ERP_TOKEN') || '',
                  badge: localStorage.getItem('$LS_BADGE') || '',
                  name: localStorage.getItem('$LS_NAME') || '',
                  site: localStorage.getItem('$LS_SITE') || '',
                  profile: localStorage.getItem('$LS_PROFILE') || '',
                  fcmSynced: localStorage.getItem('$LS_FCM_SYNCED') || ''
                });
              } catch (e) { return '{}'; }
            })();
        """.trimIndent()
        return suspendCancellableCoroutine { cont ->
            val controller = WebViewBridgeRegistry.get()
            if (controller == null) {
                cont.resume("")
                return@suspendCancellableCoroutine
            }
            controller.evaluateJavascript(script) { raw ->
                cont.resume(unquoteJsString(raw))
            }
        }
    }

    private fun unquoteJsString(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed == "null" || trimmed.isEmpty()) return ""
        if (trimmed.length >= 2 && trimmed.first() == '"' && trimmed.last() == '"') {
            return trimmed.substring(1, trimmed.length - 1)
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
        }
        return trimmed
    }

    private fun prefs() =
        SmileApplication.instance.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private data class ProfileFields(
        val badge: String = "",
        val name: String = "",
        val site: String = "",
    )
}
