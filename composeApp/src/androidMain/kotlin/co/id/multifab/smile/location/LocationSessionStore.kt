/**
 * Status: AKTIF
 * Flow: Menyimpan sesi tracking (aktif, token, interval, error upload) di SharedPreferences.
 * Takeout: jangan.
 */
package co.id.multifab.smile.location

import android.content.Context
import co.id.multifab.smile.SmileApplication
import co.id.multifab.smile.nativeconfig.NativeAppConfig
import org.json.JSONObject

object LocationSessionStore {
    private const val PREFS = "smile_feature_location_tracking"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_SESSION = "session_json"
    private const val KEY_LAST_UPLOAD = "last_upload_at"
    private const val KEY_LAST_ERROR = "last_error"
    private const val KEY_LAST_BATCH_AT = "last_batch_attempt_at"
    private const val KEY_LAST_BATCH_OK = "last_batch_attempt_ok"
    /** Absen masuk meng-arm tracking sampai workEndAt (alarm START boleh jalan app tertutup). */
    private const val KEY_ARMED_UNTIL = "armed_until_ms"

    enum class BatchLamp {
        /** Belum pernah mencoba kirim batch. */
        Neutral,
        Ok,
        Fail,
    }

    private fun prefs() =
        SmileApplication.instance.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(): Boolean = prefs().getBoolean(KEY_ENABLED, false)

    /** commit() agar alarm START di latar tidak membaca enabled=false (race apply()). */
    fun setEnabled(enabled: Boolean) {
        prefs().edit().putBoolean(KEY_ENABLED, enabled).commit()
    }

    fun armShiftUntil(workEndAtMs: Long) {
        prefs().edit().putLong(KEY_ARMED_UNTIL, workEndAtMs).commit()
    }

    fun clearShiftArm() {
        prefs().edit().remove(KEY_ARMED_UNTIL).commit()
    }

    fun isShiftArmed(now: Long = System.currentTimeMillis()): Boolean {
        val until = prefs().getLong(KEY_ARMED_UNTIL, 0L)
        return until > now
    }

    fun armedUntilMs(): Long = prefs().getLong(KEY_ARMED_UNTIL, 0L)

    fun saveSession(session: JSONObject) {
        prefs().edit().putString(KEY_SESSION, session.toString()).apply()
    }

    fun getSession(): JSONObject? {
        val raw = prefs().getString(KEY_SESSION, null) ?: return null
        return runCatching { JSONObject(raw) }.getOrNull()
    }

    fun clearSession() {
        prefs().edit().remove(KEY_SESSION).apply()
    }

    fun getLastUploadAt(): Long = prefs().getLong(KEY_LAST_UPLOAD, 0L)

    fun getLastError(): String = prefs().getString(KEY_LAST_ERROR, "").orEmpty()

    fun getLastBatchAttemptAt(): Long = prefs().getLong(KEY_LAST_BATCH_AT, 0L)

    fun wasLastBatchOk(): Boolean = prefs().getBoolean(KEY_LAST_BATCH_OK, false)

    fun batchLamp(): BatchLamp {
        if (getLastBatchAttemptAt() <= 0L) return BatchLamp.Neutral
        return if (wasLastBatchOk() && getLastError().isBlank()) BatchLamp.Ok else BatchLamp.Fail
    }

    fun recordBatchAttempt(success: Boolean, at: Long = System.currentTimeMillis(), error: String = "") {
        val edit = prefs().edit()
            .putLong(KEY_LAST_BATCH_AT, at)
            .putBoolean(KEY_LAST_BATCH_OK, success)
        if (success) {
            edit.putLong(KEY_LAST_UPLOAD, at)
                .remove(KEY_LAST_ERROR)
        } else {
            edit.putString(KEY_LAST_ERROR, error.ifBlank { "batch_upload_failed" })
        }
        edit.apply()
    }

    fun inWorkWindow(now: Long = System.currentTimeMillis()): Boolean {
        if (!isEnabled() && getSession() == null && !isShiftArmed(now)) return false
        return LocationShiftSync.isNearOrInWindow(now)
    }

    fun hasShiftWindow(): Boolean {
        val session = getSession() ?: return false
        val start = session.optLong("workStartAt", 0L)
        val end = session.optLong("workEndAt", 0L)
        if (start <= 0L || end <= 0L) return false
        return end - start < 20 * 60 * 60 * 1000L
    }

    /**
     * Sesi untuk kirim batch (termasuk retry di luar shift). Tidak mensyaratkan jendela kerja.
     */
    fun ensureUploadSessionForBatch(): JSONObject? {
        val existing = getSession()
        if (existing != null &&
            existing.optString("fullName").isNotBlank() &&
            existing.optString("endpointBase").isNotBlank()
        ) {
            if (!hasShiftWindow()) {
                LocationShiftSync.applyCachedOrNormal()
            }
            return getSession()
        }
        if (!co.id.multifab.smile.session.LoginSessionStore.isLoggedIn()) return null
        val name = co.id.multifab.smile.session.LoginSessionStore.displayName().trim()
        if (name.isBlank()) return null
        val session = JSONObject().apply {
            put("employeeId", co.id.multifab.smile.session.LoginSessionStore.badge())
            put("fullName", name)
            put("authToken", co.id.multifab.smile.session.LoginSessionStore.authToken())
            put("endpointBase", co.id.multifab.smile.config.AppConfig.LOG_API_BASE)
            put("intervalMs", co.id.multifab.smile.nativeconfig.NativeConfigStore.locationIntervalMs())
        }
        saveSession(session)
        setEnabled(true)
        LocationShiftSync.applyCachedOrNormal()
        return getSession()
    }

    fun updateIntervalMs(intervalMs: Long) {
        val session = getSession() ?: return
        session.put("intervalMs", intervalMs)
        saveSession(session)
    }

    fun shiftSource(): String = getSession()?.optString("shiftSource").orEmpty()

    /** Jam shift dari native-config yang baru di-refresh (check-in). */
    fun applyNativeWorkShift(timeIn: String, timeOut: String, label: String, source: String) {
        val session = getSession() ?: JSONObject()
        session.put("shiftTimeIn", timeIn)
        session.put("shiftTimeOut", timeOut)
        session.put("shiftLabel", label.ifBlank { "Shift" })
        session.put("shiftSource", source.ifBlank { "native-config" })
        saveSession(session)
    }

    /** Jam karyawan dari shift_label attendance-context. Menang atas workShift ERP. */
    fun applyAttendanceShift(timeIn: String, timeOut: String, label: String) {
        val session = getSession() ?: JSONObject()
        session.put("shiftTimeIn", timeIn)
        session.put("shiftTimeOut", timeOut)
        session.put("shiftLabel", label.ifBlank { "Shift" })
        session.put("shiftSource", "attendance-context")
        saveSession(session)
    }

    /** Hapus jam shift tersimpan agar [LocationShiftSync] pakai native-config terbaru. */
    fun clearShiftOverridesFromSession() {
        val session = getSession() ?: return
        session.remove("shiftTimeIn")
        session.remove("shiftTimeOut")
        session.remove("shiftLabel")
        session.remove("shiftSource")
        session.remove("workStartAt")
        session.remove("workEndAt")
        saveSession(session)
    }

    fun statusJson(tracking: Boolean, queuedCount: Int): JSONObject =
        JSONObject().apply {
            put("enabled", isEnabled())
            put("tracking", tracking)
            put("inWindow", inWorkWindow())
            put("queuedCount", queuedCount)
            put("queueFlushSize", co.id.multifab.smile.nativeconfig.NativeConfigStore.locationQueueFlushSize())
            put("maxLocalQueue", co.id.multifab.smile.nativeconfig.NativeConfigStore.locationMaxLocalQueue())
            put("lastUploadAt", getLastUploadAt())
            put("lastError", getLastError())
            put("lastBatchAttemptAt", getLastBatchAttemptAt())
            put("lastBatchOk", wasLastBatchOk())
            put("batchLamp", batchLamp().name.lowercase())
            val session = getSession()
            if (session != null) {
                put("workStartAt", session.optLong("workStartAt", 0L))
                put("workEndAt", session.optLong("workEndAt", 0L))
                put("shiftLabel", session.optString("shiftLabel"))
                put("shiftSource", session.optString("shiftSource"))
                put("intervalMs", session.optLong("intervalMs", NativeAppConfig.BOOTSTRAP_INTERVAL_MS))
            }
        }
}
