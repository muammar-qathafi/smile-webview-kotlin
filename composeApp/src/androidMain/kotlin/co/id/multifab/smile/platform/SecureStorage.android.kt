/**
 * Status: AKTIF
 * Flow: SharedPreferences terenkripsi untuk SmileNative.getSecure/setSecure yang dipanggil web.
 * Takeout: jangan selama web masih menyimpan rahasia lewat bridge.
 */
package co.id.multifab.smile.platform

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import co.id.multifab.smile.SmileApplication

actual object SecureStorage {
    private val prefs by lazy {
        val context = SmileApplication.instance
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "smile_secure_storage",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    actual fun get(key: String): String? = prefs.getString(key, null)

    actual fun set(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    actual fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }
}
