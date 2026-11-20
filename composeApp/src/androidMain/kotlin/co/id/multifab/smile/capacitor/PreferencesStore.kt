/**
 * Status: COMPAT
 * Flow: SharedPreferences smile_capacitor_prefs untuk plugin Preferences (get/set/remove) yang dipanggil web.
 * Takeout: kandidat jika web sudah tidak memakai Capacitor Preferences. Kode native lain tidak membaca store ini.
 */
package co.id.multifab.smile.capacitor

import android.content.Context
import co.id.multifab.smile.SmileApplication

object PreferencesStore {

    private const val PREFS = "smile_capacitor_prefs"

    private fun prefs() =
        SmileApplication.instance.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun get(key: String): String? = prefs().getString(key, null)

    fun set(key: String, value: String) {
        prefs().edit().putString(key, value).apply()
    }

    fun remove(key: String) {
        prefs().edit().remove(key).apply()
    }
}
