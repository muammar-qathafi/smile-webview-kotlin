/**
 * Status: AKTIF
 * Flow: Penerima FCM. Token baru didaftarkan ke backend; pesan masuk diarahkan ke SmileNotifyHandler atau notifikasi biasa.
 * Takeout: jangan.
 */
package co.id.multifab.smile.fcm

import co.id.multifab.smile.util.SmileLog

import co.id.multifab.smile.bridge.SmileJsBridge
import co.id.multifab.smile.notify.SmileNotifyHandler
import co.id.multifab.smile.notify.SmileNotifyPayload
import co.id.multifab.smile.platform.PushNotificationHandler
import co.id.multifab.smile.platform.PushPayload
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import org.json.JSONObject

class SmileFirebaseMessagingService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        SmileLog.i(TAG, "FCM token refreshed")
        FcmTokenStore.set(this, token)
        FcmBackendSync.clearSyncMarkerIfTokenChanged(token)
        PushNotificationHandler.notifyTokenRefresh(token)
        SmileJsBridge.dispatchTokenToWeb(token)
        FcmBackendSync.ensureRegisteredAsync("onNewToken")
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)

        val data = message.data
        val title = message.notification?.title
            ?: data["title"]
            ?: data["gcm.notification.title"]
            ?: "Smile Multifab"
        val body = message.notification?.body
            ?: data["body"]
            ?: data["gcm.notification.body"]
            ?: ""

        val type = data["type"] ?: data["action"] ?: ""
        val payload = PushPayload(
            title = title,
            body = body,
            data = data,
            type = type,
        )

        val isAttendance = type == "attendance_check_in" || type == "attendance_check_out"
        val displayBody = body.ifBlank { title }

        if (type == TYPE_REMINDER_RESYNC) {
            val syncVersion = data["sync_version"].orEmpty()
            val json = JSONObject().apply {
                put("title", title)
                put("body", body)
                put("data", JSONObject().apply { data.forEach { (k, v) -> put(k, v) } })
                put("type", type)
                if (syncVersion.isNotBlank()) put("sync_version", syncVersion)
            }
            SmileJsBridge.dispatchReminderResyncToWeb(json)
            return
        }

        // FCM data-only + TTS (dari send_fcm data['tts']) — tray + audio di handler.
        val hasTts = !data["tts_text"].isNullOrBlank() || !data["tts"].isNullOrBlank()
        if (type == SmileNotifyPayload.TYPE || (hasTts && message.notification == null)) {
            val handleData = if (type != SmileNotifyPayload.TYPE && hasTts) {
                data.toMutableMap().apply {
                    putIfAbsent("type", SmileNotifyPayload.TYPE)
                    putIfAbsent("sound_mode", "tts")
                    if (this["tts_text"].isNullOrBlank()) this["tts_text"] = this["tts"].orEmpty()
                    putIfAbsent("title", title)
                    putIfAbsent("body", body)
                    putIfAbsent("show_tray", "1")
                }
            } else {
                data
            }
            SmileNotifyHandler.handle(applicationContext, handleData)
            val safeData = handleData.filterKeys {
                it !in setOf("tts", "tts_text", "tts_url", "ttsText", "soundUri", "soundPath")
            }
            val json = JSONObject().apply {
                put("title", handleData["title"] ?: title)
                put("body", handleData["body"] ?: body)
                put("data", JSONObject().apply { safeData.forEach { (k, v) -> put(k, v) } })
                put("type", handleData["type"] ?: type)
            }
            SmileJsBridge.dispatchPushToWeb(json)
            return
        }

        if (data["silent"] == "1" || data["show_tray"] == "0") {
            return
        }

        if (message.notification != null || displayBody.isNotBlank() || title.isNotBlank()) {
            NotificationHelper.showNotification(
                context = this,
                title = title,
                body = displayBody,
                data = data,
            )
        } else {
            NotificationWorker.enqueue(
                context = this,
                title = title,
                body = body,
                data = data,
            )
        }

        PushNotificationHandler.notifyMessageReceived(payload)
        if (!isAttendance) {
            val json = JSONObject().apply {
                put("title", title)
                put("body", body)
                put("data", JSONObject().apply { data.forEach { (k, v) -> put(k, v) } })
                put("type", type)
            }
            SmileJsBridge.dispatchPushToWeb(json)
        }
    }

    companion object {
        private const val TAG = "SmileFCM"
        const val TYPE_REMINDER_RESYNC = "smile_reminder_resync"
    }
}
