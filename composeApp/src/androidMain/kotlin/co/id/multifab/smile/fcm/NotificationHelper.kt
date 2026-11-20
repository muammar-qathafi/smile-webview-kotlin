/**
 * Status: AKTIF
 * Flow: Channel dan tampilan notifikasi push umum (bukan jalur TTS smile_notify).
 * Takeout: jangan.
 */
package co.id.multifab.smile.fcm

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import co.id.multifab.MainActivity
import co.id.multifab.smile.config.AppConfig

object NotificationHelper {
    private const val NOTIFICATION_ID_BASE = 10_001

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            AppConfig.FCM_CHANNEL_ID,
            AppConfig.FCM_CHANNEL_NAME,
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Push absensi, approval, dan informasi Smile"
            enableVibration(true)
            enableLights(true)
        }
        manager.createNotificationChannel(channel)
    }

    fun showNotification(
        context: Context,
        title: String,
        body: String,
        data: Map<String, String> = emptyMap(),
        notificationId: Int = NOTIFICATION_ID_BASE,
    ) {
        ensureChannel(context)

        val type = data["type"] ?: data["action"] ?: "push"
        val standardLogo = co.id.multifab.smile.notify.SmileNotificationStyle.useStandardLogoOnly(type, data)
        val imageUrl = if (standardLogo) {
            ""
        } else {
            data["image_url"] ?: data["imageUrl"] ?: ""
        }
        val resolvedId = if (notificationId != NOTIFICATION_ID_BASE) {
            notificationId
        } else {
            (type.hashCode() and 0x7FFF_FFFF) + NOTIFICATION_ID_BASE
        }

        val launchIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            data.forEach { (key, value) -> putExtra(key, value) }
            putExtra("smile_push_title", title)
            putExtra("smile_push_body", body)
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            resolvedId,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, AppConfig.FCM_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(
                when {
                    standardLogo -> NotificationCompat.CATEGORY_REMINDER
                    type == "attendance_check_in" || type == "attendance_check_out" ->
                        NotificationCompat.CATEGORY_STATUS
                    else -> NotificationCompat.CATEGORY_MESSAGE
                },
            )
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setVibrate(longArrayOf(0, 250, 120, 250))
            .also { builder ->
                co.id.multifab.smile.notify.NotificationIconLoader.apply(
                    builder = builder,
                    context = context,
                    contentTitle = title,
                    contentText = body,
                    attachmentUrl = imageUrl,
                    standardLogoOnly = standardLogo,
                )
            }
            .build()

        NotificationManagerCompat.from(context).notify(resolvedId, notification)
    }
}
