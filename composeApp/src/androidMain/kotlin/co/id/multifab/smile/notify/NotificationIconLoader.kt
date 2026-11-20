/**
 * Status: PENDUKUNG
 * Flow: Memasang ikon kecil dan large-icon notifikasi. Bisa unduh gambar remote bila bukan mode logo standar.
 * Takeout: jangan selama tray FCM dan pengingat masih tampil.
 */
package co.id.multifab.smile.notify

import co.id.multifab.smile.util.SmileLog

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.app.NotificationCompat
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.min

object NotificationIconLoader {
    private const val TAG = "SmileNotifIcon"
    private const val DOWNLOAD_TIMEOUT_MS = 4_000
    private const val MAX_EDGE_PX = 512

    fun loadRemoteBitmap(url: String?): Bitmap? {
        val trimmed = url?.trim().orEmpty()
        if (trimmed.isBlank() || !trimmed.startsWith("http")) return null
        return runCatching {
            val connection = (URL(trimmed).openConnection() as HttpURLConnection).apply {
                connectTimeout = DOWNLOAD_TIMEOUT_MS
                readTimeout = DOWNLOAD_TIMEOUT_MS
                instanceFollowRedirects = true
                doInput = true
            }
            connection.connect()
            if (connection.responseCode !in 200..299) {
                connection.disconnect()
                return null
            }
            connection.inputStream.use { stream ->
                val decoded = BitmapFactory.decodeStream(stream) ?: return null
                scaleDown(decoded, MAX_EDGE_PX)
            }
        }.onFailure { e ->
            SmileLog.w(TAG, "Gagal unduh ikon notifikasi: ${e.message}")
        }.getOrNull()
    }

    private fun scaleDown(source: Bitmap, maxEdge: Int): Bitmap {
        val w = source.width
        val h = source.height
        if (w <= maxEdge && h <= maxEdge) return source
        val scale = min(maxEdge.toFloat() / w, maxEdge.toFloat() / h)
        val nw = (w * scale).toInt().coerceAtLeast(1)
        val nh = (h * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(source, nw, nh, true)
    }

    /**
     * @param standardLogoOnly true = pengingat absensi / tes 15s (abaikan image_url ERP).
     */
    fun apply(
        builder: NotificationCompat.Builder,
        context: Context,
        contentTitle: String? = null,
        contentText: String? = null,
        attachmentUrl: String? = null,
        standardLogoOnly: Boolean = false,
        @Suppress("UNUSED_PARAMETER") useColorLogoTray: Boolean = true,
    ) {
        val attachment = if (standardLogoOnly) null else loadRemoteBitmap(attachmentUrl)
        val body = contentText?.trim().orEmpty()

        NotificationIcons.applyStandardTray(builder, context)

        val brand = NotificationIcons.colorTrayLargeIcon(context)

        if (attachment != null) {
            builder.setStyle(
                NotificationCompat.BigPictureStyle()
                    .bigPicture(attachment)
                    .bigLargeIcon(brand),
            )
        } else if (body.isNotEmpty()) {
            builder.setStyle(NotificationCompat.BigTextStyle().bigText(body))
        }
    }
}