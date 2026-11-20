/**
 * Status: AKTIF
 * Flow: Mengambil native-config dari backend sekali per proses. Offline memakai cache dan aset APK.
 * Takeout: jangan. Kebijakan interval, Fake GPS, dan teks diagnosis datang dari sini.
 */
package co.id.multifab.smile.nativeconfig

import co.id.multifab.smile.util.SmileLog

import android.content.Context
import co.id.multifab.smile.SmileApplication
import co.id.multifab.smile.config.AppConfig
import co.id.multifab.smile.session.LoginSessionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Config remote-first untuk shell native.
 *
 * Refresh BE: sekali per proses (cold start) + paksa refresh lewat NativeConfig.refresh (absen masuk).
 * Offline: SharedPreferences cache + asset APK.
 */
object NativeConfigStore {
    private const val TAG = "NativeConfigStore"
    private const val PREFS = "smile_native_config"
    private const val KEY_JSON = "config_json"
    private const val KEY_FETCHED_AT = "fetched_at"
    private const val ASSET_PATH = "smile/native-config.json"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val refreshedThisProcess = AtomicBoolean(false)
    private val fetching = AtomicBoolean(false)

    @Volatile
    private var memory: NativeAppConfig? = null

    private val listeners = mutableListOf<(NativeAppConfig) -> Unit>()

    fun get(): NativeAppConfig {
        memory?.let { return it }
        val assets = loadFromAssets()
        val prefs = loadFromPrefs()
        val chosen = when {
            prefs != null && (assets == null || prefs.version >= assets.version) -> prefs
            assets != null -> assets
            else -> NativeAppConfig.defaultConfig()
        }
        memory = chosen
        return chosen
    }

    /**
     * Tarik BE paling banyak sekali per hidup proses.
     * Dipanggil dari Application / MainActivity cold open.
     */
    fun refreshOncePerProcessAsync(onDone: ((Boolean) -> Unit)? = null) {
        if (refreshedThisProcess.get()) {
            onDone?.invoke(true)
            return
        }
        refreshAsync(force = false, onDone = onDone)
    }

    fun refreshAsync(force: Boolean = false, onDone: ((Boolean) -> Unit)? = null) {
        if (!force && refreshedThisProcess.get()) {
            onDone?.invoke(true)
            return
        }
        if (!fetching.compareAndSet(false, true)) {
            onDone?.invoke(false)
            return
        }
        scope.launch {
            val ok = runCatching {
                fetchRemoteAndCache()
                refreshedThisProcess.set(true)
                true
            }.getOrElse { e ->
                SmileLog.w(TAG, "refresh failed: ${e.message}")
                false
            }
            fetching.set(false)
            withContext(Dispatchers.Main) {
                onDone?.invoke(ok)
            }
        }
    }

    /** @deprecated Gunakan refreshOncePerProcessAsync — jangan force tiap screen. */
    fun forceRefreshAsync(onDone: ((Boolean) -> Unit)? = null) =
        refreshOncePerProcessAsync(onDone)

    fun addListener(listener: (NativeAppConfig) -> Unit) {
        synchronized(listeners) { listeners.add(listener) }
    }

    fun removeListener(listener: (NativeAppConfig) -> Unit) {
        synchronized(listeners) { listeners.remove(listener) }
    }

    private fun notifyListeners(config: NativeAppConfig) {
        val copy = synchronized(listeners) { listeners.toList() }
        copy.forEach { runCatching { it(config) } }
    }

    private suspend fun fetchRemoteAndCache() {
        val url = URL(AppConfig.ERP_API_BASE.trimEnd('/') + AppConfig.NATIVE_CONFIG_PATH)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 12_000
            readTimeout = 12_000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
            useCaches = false
            setRequestProperty("Cache-Control", "no-cache")
            val token = LoginSessionStore.authToken()
            if (token.isNotBlank()) {
                setRequestProperty("Authorization", "Bearer $token")
            }
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                SmileLog.w(TAG, "Remote config HTTP $code")
                return
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            applyConfig(json)
            val applied = get()
            SmileLog.i(
                TAG,
                "BE config applied intervalMs=${applied.locationTracking.intervalMs} " +
                    "shift=${applied.locationTracking.workShift.timeIn}-${applied.locationTracking.workShift.timeOut}",
            )
        } finally {
            conn.disconnect()
        }
    }

    private fun applyConfig(json: JSONObject) {
        val config = NativeAppConfig.fromJson(json)
        val assets = loadFromAssets()
        if (assets != null && config.version < assets.version) {
            SmileLog.w(TAG, "skip remote config v${config.version} < asset v${assets.version}")
            memory = assets
            notifyListeners(assets)
            return
        }
        memory = config
        prefs().edit()
            .putString(KEY_JSON, json.toString())
            .putLong(KEY_FETCHED_AT, System.currentTimeMillis())
            .apply()
        notifyListeners(config)
    }

    private fun loadFromPrefs(): NativeAppConfig? {
        val raw = prefs().getString(KEY_JSON, null) ?: return null
        return runCatching {
            NativeAppConfig.fromJson(JSONObject(raw))
        }.getOrNull()
    }

    private fun loadFromAssets(): NativeAppConfig? {
        val ctx = SmileApplication.instance
        return runCatching {
            ctx.assets.open(ASSET_PATH).bufferedReader().use { reader ->
                NativeAppConfig.fromJson(JSONObject(reader.readText()))
            }
        }.getOrNull()
    }

    private fun prefs() =
        SmileApplication.instance.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun hasRemoteCache(): Boolean = prefs().getLong(KEY_FETCHED_AT, 0L) > 0L

    fun locationIntervalMs(config: NativeAppConfig = get()): Long =
        config.locationTracking.resolvedIntervalMs()

    fun locationQueueFlushSize(config: NativeAppConfig = get()): Int =
        config.locationTracking.resolvedQueueFlushSize()

    fun locationMaxLocalQueue(config: NativeAppConfig = get()): Int =
        config.locationTracking.resolvedMaxLocalQueue()

    fun locationFlushMaxAgeMs(config: NativeAppConfig = get()): Long =
        config.locationTracking.resolvedFlushMaxAgeMs()

    fun locationBatchMaxPoints(config: NativeAppConfig = get()): Int =
        config.locationTracking.resolvedBatchMaxPoints()

    fun workShift(config: NativeAppConfig = get()): WorkShiftConfig =
        config.locationTracking.workShift
}
