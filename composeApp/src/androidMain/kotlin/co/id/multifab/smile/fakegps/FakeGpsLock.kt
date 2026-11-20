/**
 * Status: AKTIF
 * Flow: Sticky lock dari mock lokasi. Unlock setelah beberapa fix fresh non-mock.
 * Takeout: jangan.
 */
package co.id.multifab.smile.fakegps

import android.content.Context
import android.location.Location
import android.os.Build
import co.id.multifab.smile.SmileApplication

object FakeGpsLock {
    private const val PREFS = "smile_fake_gps_lock"
    private const val KEY_LOCKED = "locked"
    private const val KEY_REASON = "reason"
    private const val KEY_CLEARED_APPO_PS_FALSE_POSITIVE = "cleared_appops_fp_v1"

    private const val MAX_FIX_AGE_MS = 10_000L
    private const val UNLOCK_STREAK = 2

    @Volatile
    private var locked: Boolean = false

    @Volatile
    private var reason: String = ""

    @Volatile
    private var nonMockStreak: Int = 0

    @Volatile
    private var loaded: Boolean = false

    fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val prefs = prefs()
            locked = prefs.getBoolean(KEY_LOCKED, false)
            reason = prefs.getString(KEY_REASON, "").orEmpty()
            // Bebaskan user yang terjebak false-positive AppOps (build 2.6.12).
            if (locked && isLegacyAppOpsFalsePositive(reason) &&
                !prefs.getBoolean(KEY_CLEARED_APPO_PS_FALSE_POSITIVE, false)
            ) {
                locked = false
                reason = ""
                nonMockStreak = 0
                prefs.edit()
                    .putBoolean(KEY_LOCKED, false)
                    .putString(KEY_REASON, "")
                    .putBoolean(KEY_CLEARED_APPO_PS_FALSE_POSITIVE, true)
                    .apply()
            }
            loaded = true
        }
    }

    fun isLocked(): Boolean {
        ensureLoaded()
        return locked
    }

    fun reason(): String {
        ensureLoaded()
        return reason
    }

    fun lock(newReason: String) {
        ensureLoaded()
        val text = newReason.ifBlank { "Fake GPS terdeteksi" }
        nonMockStreak = 0
        val changed = !locked || reason != text
        locked = true
        reason = text
        if (changed) persist()
    }

    fun tryUnlock(location: Location): Boolean {
        ensureLoaded()
        if (!locked) return true
        if (isSystemMock(location)) {
            nonMockStreak = 0
            return false
        }
        if (fixAgeMs(location) > MAX_FIX_AGE_MS) {
            return false
        }
        nonMockStreak += 1
        if (nonMockStreak < UNLOCK_STREAK) return false
        unlock()
        return true
    }

    fun unlock() {
        ensureLoaded()
        if (!locked && reason.isEmpty() && nonMockStreak == 0) return
        locked = false
        reason = ""
        nonMockStreak = 0
        persist()
    }

    private fun isLegacyAppOpsFalsePositive(reasonText: String): Boolean =
        reasonText.startsWith("Aplikasi mock location aktif") ||
            reasonText.contains("Aplikasi mock location masih aktif")

    private fun persist() {
        prefs().edit()
            .putBoolean(KEY_LOCKED, locked)
            .putString(KEY_REASON, reason)
            .apply()
    }

    private fun prefs() =
        SmileApplication.instance.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun isSystemMock(location: Location): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            location.isMock
        } else {
            @Suppress("DEPRECATION")
            location.isFromMockProvider
        }
    }

    private fun fixAgeMs(location: Location): Long {
        val now = System.currentTimeMillis()
        val age = now - location.time
        return if (age >= 0L) age else Long.MAX_VALUE
    }
}
