/**
 * Status: COMPAT
 * Flow: Izin notifikasi dan channel untuk plugin LocalNotifications milik web. schedule/cancel tidak lagi memasang AlarmManager.
 * Takeout: jangan selama web masih memanggil Capacitor LocalNotifications.
 */
package co.id.multifab.smile.capacitor

import co.id.multifab.smile.util.SmileLog

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import co.id.multifab.smile.SmileApplication
import co.id.multifab.smile.platform.PushNotificationHandler
import org.json.JSONArray
import org.json.JSONObject

object LocalNotificationManager {

    const val REMINDER_CHANNEL_NAME = "Pengingat Absensi Smile"
    /** Channel tanpa suara sistem — TTS diputar terpisah saat FCM tiba. */
    const val REMINDER_CHANNEL_ID = "smile_attendance_reminders_v5"
    private const val TAG = "LocalNotifications"
    private const val PREFS_PENDING = "smile_pending_notifications"
    private const val LEGACY_RECEIVER = "co.id.multifab.smile.capacitor.AttendanceReminderReceiver"
    private val LEGACY_WORK_NAMES = listOf(
        "smile-feature-attendance-reminders-sync",
        "smile-feature-attendance-reminders-bootstrap",
        "smile_attendance_reminder_sync",
        "smile_attendance_reminder_bootstrap",
    )

    fun requestPermissions(): JSONObject {
        // Sudah granted → jangan runBlocking + dialog path.
        val current = PushNotificationHandler.getPermission()
        if (current == "granted") {
            return JSONObject().apply { put("display", "granted") }
        }
        // runBlocking di main thread + dialog izin = deadlock (Honor: app “hang” / restart).
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            return JSONObject().apply {
                put("display", if (current == "denied") "denied" else "prompt")
            }
        }
        val receive = kotlinx.coroutines.runBlocking(kotlinx.coroutines.Dispatchers.Default) {
            PushNotificationHandler.requestPermission()
        }
        val display = when (receive) {
            "granted" -> "granted"
            "denied" -> "denied"
            "prompt-with-rationale" -> "prompt-with-rationale"
            else -> "prompt"
        }
        return JSONObject().apply { put("display", display) }
    }

    fun checkPermissions(): JSONObject {
        val receive = PushNotificationHandler.getPermission()
        val display = when (receive) {
            "granted" -> "granted"
            "denied" -> "denied"
            "prompt-with-rationale" -> "prompt-with-rationale"
            else -> "prompt"
        }
        return JSONObject().apply { put("display", display) }
    }

    fun checkExactNotificationSetting(): JSONObject {
        val ctx = SmileApplication.instance
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            am.canScheduleExactAlarms()
        } else {
            true
        }
        return JSONObject().apply {
            put("exact_alarm", if (granted) "granted" else "denied")
        }
    }

    /**
     * Jangan buka Settings otomatis (Honor sering meminimalkan app / reset task).
     * User buka lewat [openExactAlarmSettings] dari panel Settings.
     */
    fun changeExactNotificationSetting(): JSONObject {
        return checkExactNotificationSetting()
    }

    fun openExactAlarmSettings(activity: android.app.Activity?) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val ctx = activity ?: SmileApplication.instance
        val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
        if (activity == null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { ctx.startActivity(intent) }
    }

    fun createChannel(options: JSONObject) {
        val id = options.optString("id")
        val name = options.optString("name", REMINDER_CHANNEL_NAME)
        ensureChannel(SmileApplication.instance, id, name, options)
    }

    fun ensureChannel(
        context: Context,
        id: String,
        name: String,
        options: JSONObject? = null,
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val importance = options?.optInt("importance", NotificationManager.IMPORTANCE_HIGH)
            ?: NotificationManager.IMPORTANCE_HIGH
        val silent = options?.optBoolean("silent", false) == true ||
            id == REMINDER_CHANNEL_ID ||
            id.startsWith("smile_attendance_reminders")
        val channel = NotificationChannel(id, name, importance).apply {
            description = options?.optString("description", "Pengingat absensi Smile")
            enableVibration(options?.optBoolean("vibration", true) ?: true)
            if (silent) {
                setSound(null, null)
            }
        }
        manager.createNotificationChannel(channel)
    }

    /** Tidak memasang alarm. Pengingat absen datang dari FCM saat jamnya tiba. */
    fun schedule(options: JSONObject) {
        val count = options.optJSONArray("notifications")?.length() ?: 0
        SmileLog.i(TAG, "schedule ignored count=$count")
    }

    /** Batalkan alarm lokal yang masih tersimpan dari APK lama. */
    fun cancelInstalledAlarms() {
        cancelAllPending()
        runCatching {
            val wm = androidx.work.WorkManager.getInstance(SmileApplication.instance)
            LEGACY_WORK_NAMES.forEach { wm.cancelUniqueWork(it) }
        }
    }

    /** Batalkan semua alarm yang tercatat di prefs pending. */
    fun cancelAllPending() {
        val pending = loadPendingIds()
        if (pending.isEmpty()) return
        val arr = JSONArray()
        pending.forEach { arr.put(JSONObject().put("id", it)) }
        cancel(JSONObject().put("notifications", arr))
    }

    fun cancel(options: JSONObject) {
        val notifications = options.optJSONArray("notifications") ?: return
        val ctx = SmileApplication.instance
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pending = loadPendingIds().toMutableSet()

        for (i in 0 until notifications.length()) {
            val id = notifications.getJSONObject(i).getInt("id")
            val intent = Intent().apply {
                setClassName(ctx, LEGACY_RECEIVER)
            }
            val pi = PendingIntent.getBroadcast(
                ctx,
                id,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            am.cancel(pi)
            pi.cancel()
            pending.remove(id)
        }
        savePendingIds(pending)
    }

    fun getPending(): JSONObject {
        val ids = loadPendingIds()
        val arr = JSONArray()
        ids.forEach { id ->
            arr.put(JSONObject().put("id", id))
        }
        return JSONObject().apply {
            put("notifications", arr)
        }
    }

    private fun loadPendingIds(): Set<Int> {
        val raw = SmileApplication.instance
            .getSharedPreferences(PREFS_PENDING, Context.MODE_PRIVATE)
            .getString("ids", "[]")
        val arr = JSONArray(raw)
        return buildSet {
            for (i in 0 until arr.length()) add(arr.getInt(i))
        }
    }

    private fun savePendingIds(ids: Set<Int>) {
        val arr = JSONArray()
        ids.forEach { arr.put(it) }
        SmileApplication.instance
            .getSharedPreferences(PREFS_PENDING, Context.MODE_PRIVATE)
            .edit()
            .putString("ids", arr.toString())
            .apply()
    }
}
