/**
 * Status: AKTIF
 * Flow: Menerima FCM type smile_notify: tampilkan tray dan putar TTS sekali saat pesan tiba. Alarm lokal tidak dipasang.
 * Takeout: jangan.
 */
package co.id.multifab.smile.notify

import co.id.multifab.smile.util.SmileLog

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
import co.id.multifab.smile.tts.SmileTtsPlayer
import org.json.JSONObject

/**
 * Handler FCM `smile_notify`: tray + TextToSpeech sekali (tanpa unduh MP3).
 */
object SmileNotifyHandler {
    private const val TAG = "SmileNotifyHandler"
    private const val PREFS = "smile_notify_dedupe"
    private const val KEY_IDS = "ids_json"
    /** Hanya cegah double-delivery FCM, bukan blok kirim ulang teks sama. */
    private const val RETAIN_MS = 8_000L

    fun handle(context: Context, data: Map<String, String>): Boolean {
        val payload = SmileNotifyPayload.fromData(data) ?: return false
        val app = context.applicationContext
        if (alreadyHandled(payload.dedupeId)) {
            SmileLog.i(TAG, "skip dedupe_id=${payload.dedupeId}")
            return true
        }

        val playId = SmileNotifyPayload.playId(payload.dedupeId)

        if (payload.showTray) {
            showTray(app, payload, playId)
        }

        if (payload.wantsTts && payload.ttsText.isNotBlank()) {
            // Jangan block FCM thread terlalu lama — speak di background + wake.
            Thread {
                val played = SmileTtsPlayer.playOnce(
                    context = app,
                    reminderId = playId,
                    ttsText = payload.ttsText,
                    voiceGender = payload.ttsGender,
                )
                SmileLog.i(
                    TAG,
                    "TTS playOnce played=$played dedupe=${payload.dedupeId} gender=${payload.ttsGender}",
                )
            }.start()
        }

        markHandled(payload.dedupeId)
        return true
    }

    private fun showTray(context: Context, payload: SmileNotifyPayload, notificationId: Int) {
        ensureChannel(context, payload.channelId, payload.wantsTts)

        val launch = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            payload.raw.forEach { (k, v) ->
                if (k in SKIP_INTENT_KEYS) return@forEach
                putExtra(k, v)
            }
            putExtra("type", SmileNotifyPayload.TYPE)
            putExtra("dedupe_id", payload.dedupeId)
            putExtra("smile_push_title", payload.title)
            putExtra("smile_push_body", payload.body)
            if (payload.deepLink.isNotBlank()) putExtra("deep_link", payload.deepLink)
        }
        val pi = PendingIntent.getActivity(
            context,
            notificationId,
            launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = NotificationCompat.Builder(context, payload.channelId)
            .setContentTitle(payload.title)
            .setContentText(payload.body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setVibrate(longArrayOf(0, 250, 120, 250))
            .also { b ->
                co.id.multifab.smile.notify.NotificationIconLoader.apply(
                    builder = b,
                    context = context,
                    contentTitle = payload.title,
                    contentText = payload.body,
                    attachmentUrl = null,
                    standardLogoOnly = true,
                )
            }

        if (payload.wantsTts) {
            builder.setSilent(true)
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
                builder.setSound(null)
                builder.setDefaults(NotificationCompat.DEFAULT_VIBRATE)
            }
        } else {
            builder.setDefaults(NotificationCompat.DEFAULT_ALL)
        }

        NotificationManagerCompat.from(context).notify(notificationId, builder.build())
    }

    private fun ensureChannel(context: Context, channelId: String, silent: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            channelId,
            if (silent) "Smile Notify + TTS" else AppConfig.FCM_CHANNEL_NAME,
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Notifikasi Smile generik"
            enableVibration(true)
            if (silent) setSound(null, null)
        }
        manager.createNotificationChannel(channel)
    }

    private fun alreadyHandled(dedupeId: String): Boolean {
        val ts = loadIds()[dedupeId] ?: return false
        return System.currentTimeMillis() - ts < RETAIN_MS
    }

    private fun markHandled(dedupeId: String) {
        val now = System.currentTimeMillis()
        val map = loadIds().toMutableMap()
        map[dedupeId] = now
        val cutoff = now - RETAIN_MS
        map.entries.removeIf { it.value < cutoff }
        val obj = JSONObject()
        map.forEach { (k, v) -> obj.put(k, v) }
        prefs().edit().putString(KEY_IDS, obj.toString()).apply()
    }

    private fun loadIds(): Map<String, Long> {
        val raw = prefs().getString(KEY_IDS, "{}") ?: "{}"
        val obj = runCatching { JSONObject(raw) }.getOrDefault(JSONObject())
        val out = mutableMapOf<String, Long>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            out[k] = obj.optLong(k, 0L)
        }
        return out
    }

    private fun prefs() =
        co.id.multifab.smile.SmileApplication.instance.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val SKIP_INTENT_KEYS = setOf(
        "tts", "tts_text", "tts_url", "ttsText", "soundUri", "soundPath",
    )
}
