/**
 * Status: AKTIF
 * Flow: Cache token FCM di SharedPreferences, dibaca saat daftar ulang ke backend.
 * Takeout: jangan.
 */
package co.id.multifab.smile.fcm

import android.content.Context
import co.id.multifab.smile.config.AppConfig

object FcmTokenStore {
    private const val PREFS = "smile_fcm_prefs"
    private const val KEY_TOKEN = "fcm_token"

    fun get(context: Context): String? {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_TOKEN, null)
            ?.takeIf { it.isNotBlank() }
    }

    fun set(context: Context, token: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_TOKEN, token)
            .apply()
    }
}
