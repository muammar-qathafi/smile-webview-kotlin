/**
 * Status: PENDUKUNG
 * Flow: Resource ikon monokrom status bar dan logo warna brand untuk notifikasi.
 * Takeout: jangan.
 */
package co.id.multifab.smile.notify

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import co.id.multifab.R
import kotlin.math.min

object NotificationIcons {
    /** Monokrom — status bar & tint merah brand (bukan placeholder kosong). */
    val smallIconRes: Int = R.drawable.ic_stat_smile
    private val colorLogoRes: Int = R.drawable.ic_notification_smile_logo

    fun brandColor(context: Context): Int =
        ContextCompat.getColor(context, R.color.smile_brand_red)

    private fun decodeColorLogoSquare(context: Context): Bitmap? {
        BitmapFactory.decodeResource(context.resources, colorLogoRes)?.let { bmp ->
            if (bmp.width == bmp.height) return bmp
            val edge = min(bmp.width, bmp.height)
            val x = (bmp.width - edge) / 2
            val y = (bmp.height - edge) / 2
            return Bitmap.createBitmap(bmp, x, y, edge, edge)
        }
        val drawable = ContextCompat.getDrawable(context, colorLogoRes) ?: return null
        val w = drawable.intrinsicWidth.coerceAtLeast(1)
        val h = drawable.intrinsicHeight.coerceAtLeast(1)
        val edge = min(w, h)
        val bitmap = Bitmap.createBitmap(edge, edge, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, edge, edge)
        drawable.draw(canvas)
        return bitmap
    }

    fun colorTrayThumbnail(context: Context, sizeDp: Int): Bitmap? {
        val source = decodeColorLogoSquare(context) ?: return null
        val density = context.resources.displayMetrics.density
        val size = (sizeDp * density).toInt().coerceAtLeast(48)
        return Bitmap.createScaledBitmap(source, size, size, true)
    }

    fun colorTrayLargeIcon(context: Context): Bitmap? =
        colorTrayThumbnail(context, sizeDp = 64)

    /** Small icon = monokrom (status bar). Large icon = logo berwarna persegi (tray / heads-up). */
    fun applyStandardTray(builder: NotificationCompat.Builder, context: Context) {
        builder.setSmallIcon(smallIconRes)
        colorTrayLargeIcon(context)?.let { builder.setLargeIcon(it) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            builder.color = brandColor(context)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder.setColorized(false)
        }
    }

    fun applyForegroundService(builder: NotificationCompat.Builder, context: Context) {
        builder.setSmallIcon(smallIconRes)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            builder.color = brandColor(context)
        }
    }
}
