/**
 * Status: AKTIF
 * Flow: Nama perangkat dan versi APK yang dikirim saat daftar FCM dan saat web menanyakan info native.
 * Takeout: jangan.
 */
package co.id.multifab.smile.platform

import android.os.Build
import co.id.multifab.smile.SmileApplication
import co.id.multifab.smile.config.AppConfig

actual object DeviceInfo {
    actual fun getPlatform(): String = "android"

    actual fun isNative(): Boolean = true

    actual fun getDeviceName(): String {
        val manufacturer = Build.MANUFACTURER.orEmpty()
        val model = Build.MODEL.orEmpty()
        return "Smile Multifab Android • $manufacturer $model".take(190)
    }

    actual fun getAppVersion(): String {
        val context = SmileApplication.instance
        return runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            info.versionName ?: "2.3.0"
        }.getOrDefault("2.3.0")
    }
}
