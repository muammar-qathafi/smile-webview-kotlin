/**
 * Status: AKTIF
 * Flow: URL web production, channel FCM, dan konstanta shell. UI di-load dari server, bukan dari aset APK.
 * Takeout: jangan.
 */
package co.id.multifab.smile.config

/**
 * Konfigurasi shell native — setara `capacitor.config.ts` di smile-1.1.
 * UI di-load dari URL remote agar update tanpa publish Play Store.
 */
object AppConfig {
    const val APP_ID = "co.id.multifab"
    const val APP_NAME = "Smile Multifab"

    /** Production web app — sama seperti Capacitor server.url */
    const val WEB_URL = "https://smile.multifab.co.id"

    /** Untuk development lokal (ganti via build flavor jika perlu). */
    const val DEV_WEB_URL = "http://10.0.2.2:5174"

    const val USE_REMOTE_WEB = true

    val webUrl: String
        get() = if (USE_REMOTE_WEB) WEB_URL else DEV_WEB_URL

    val allowedDomains: List<String> = listOf(
        "multifab.co.id",
        "vercel.app",
        "erp-multifab.com",
        "firebaseapp.com",
        "googleapis.com",
    )

    const val FCM_CHANNEL_ID = "smile_fcm_default"
    const val FCM_CHANNEL_NAME = "Notifikasi Smile"
    const val JS_BRIDGE_NAME = "SmileNative"

    const val USER_AGENT_SUFFIX = " SmileMultifab/2.3.0 KMP"

    /** ERP API — native config & authenticated Smile APIs. */
    const val ERP_API_BASE = "https://api-mms-erp.multifab.co.id"

    /** Log API — simpan lokasi (single web / batch native). */
    const val LOG_API_BASE = "https://api-mms-log.multifab.co.id"

    const val NATIVE_CONFIG_PATH = "/api/v1/smile/native-config"

    /** Native batch upload langsung ke Log API. */
    const val LOCATION_BATCH_PATH = "/api/log/location/batch"
}
