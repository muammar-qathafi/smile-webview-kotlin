/**
 * Status: AKTIF
 * Flow: Wrapper android.util.Log — hanya menulis saat BuildConfig.DEBUG. Release: no-op (lebih ringan).
 * Takeout: jangan ganti dengan Log langsung; logika app tidak boleh bergantung pada log.
 */
package co.id.multifab.smile.util

import android.util.Log
import co.id.multifab.BuildConfig

object SmileLog {
    private val enabled: Boolean = BuildConfig.DEBUG

    fun v(tag: String, message: String) {
        if (enabled) Log.v(tag, message)
    }

    fun d(tag: String, message: String) {
        if (enabled) Log.d(tag, message)
    }

    fun i(tag: String, message: String) {
        if (enabled) Log.i(tag, message)
    }

    fun w(tag: String, message: String) {
        if (enabled) Log.w(tag, message)
    }

    fun w(tag: String, message: String, tr: Throwable?) {
        if (!enabled) return
        if (tr != null) Log.w(tag, message, tr) else Log.w(tag, message)
    }

    fun e(tag: String, message: String) {
        if (enabled) Log.e(tag, message)
    }

    fun e(tag: String, message: String, tr: Throwable?) {
        if (!enabled) return
        if (tr != null) Log.e(tag, message, tr) else Log.e(tag, message)
    }
}
