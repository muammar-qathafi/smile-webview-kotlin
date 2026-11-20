/**
 * Status: AKTIF
 * Flow: WorkManager menampilkan notifikasi dari payload FCM data-only saat app di background.
 * Takeout: jangan.
 */
package co.id.multifab.smile.fcm

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import org.json.JSONObject

/**
 * WorkManager menampilkan notifikasi dari data-only FCM payload.
 * Membantu delivery saat app di background / Doze (high-priority data message).
 */
class NotificationWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val title = inputData.getString(KEY_TITLE).orEmpty().ifBlank { "Smile Multifab" }
        val body = inputData.getString(KEY_BODY).orEmpty()
        val dataJson = inputData.getString(KEY_DATA_JSON).orEmpty()

        val data = mutableMapOf<String, String>()
        if (dataJson.isNotBlank()) {
            runCatching {
                val json = JSONObject(dataJson)
                json.keys().forEach { key ->
                    data[key] = json.optString(key, "")
                }
            }
        }

        NotificationHelper.showNotification(
            context = applicationContext,
            title = title,
            body = body,
            data = data,
            notificationId = (System.currentTimeMillis() % Int.MAX_VALUE).toInt(),
        )
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "smile_fcm_notification"
        const val KEY_TITLE = "title"
        const val KEY_BODY = "body"
        const val KEY_DATA_JSON = "data_json"

        fun enqueue(context: Context, title: String, body: String, data: Map<String, String>) {
            val dataJson = JSONObject()
            data.forEach { (key, value) -> dataJson.put(key, value) }
            val input: Data = workDataOf(
                KEY_TITLE to title,
                KEY_BODY to body,
                KEY_DATA_JSON to dataJson.toString(),
            )
            val request = OneTimeWorkRequestBuilder<NotificationWorker>()
                .setInputData(input)
                .addTag(WORK_NAME)
                .build()
            WorkManager.getInstance(context).enqueue(request)
        }
    }
}
