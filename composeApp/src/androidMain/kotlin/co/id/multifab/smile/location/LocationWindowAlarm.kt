/**
 * Status: AKTIF
 * Flow: Alarm tepat waktu untuk mulai dan berhenti tracking di batas jam shift, termasuk saat app belum dibuka.
 * Takeout: jangan.
 */
package co.id.multifab.smile.location

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import co.id.multifab.smile.SmileApplication
import co.id.multifab.smile.util.SmileLog

/**
 * Alarm tepat waktu (setAlarmClock) untuk buka/tutup jendela tracking.
 * Bekerja tanpa Activity — agar tracking jalan meski app belum dibuka.
 *
 * Dipasang setelah absen masuk ([LocationShiftSync.applyAfterCheckIn]) dan
 * di-restore saat boot ([SmileBootReceiver]).
 */
object LocationWindowAlarm {
    private const val TAG = "LocationWindowAlarm"
    private const val REQ_START = 88_021
    private const val REQ_STOP = 88_022

    fun schedule(window: LocationShiftSync.WorkWindow, context: Context = SmileApplication.instance) {
        if (LocationTrackingKillSwitch.isOff()) {
            cancel(context)
            return
        }
        cancel(context)
        val now = System.currentTimeMillis()
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return

        // START: sebelum jam masuk (absen masuk → alarm ini yang menyalakan tracking).
        if (window.workStartAt > now) {
            setExact(am, context, REQ_START, LocationWindowReceiver.ACTION_START, window.workStartAt)
            SmileLog.i(TAG, "scheduled START at ${window.workStartAt} (${window.timeIn})")
        }
        // STOP: selalu jika jam pulang masih di depan (baik Waiting maupun Tracking).
        if (window.workEndAt > now) {
            setExact(am, context, REQ_STOP, LocationWindowReceiver.ACTION_STOP, window.workEndAt)
            SmileLog.i(TAG, "scheduled STOP at ${window.workEndAt} (${window.timeOut})")
        }
    }

    fun cancel(context: Context = SmileApplication.instance) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        am.cancel(pending(context, REQ_START, LocationWindowReceiver.ACTION_START))
        am.cancel(pending(context, REQ_STOP, LocationWindowReceiver.ACTION_STOP))
    }

    private fun setExact(
        am: AlarmManager,
        context: Context,
        requestCode: Int,
        action: String,
        triggerAt: Long,
    ) {
        val pi = pending(context, requestCode, action)
        val show = PendingIntent.getActivity(
            context,
            requestCode + 100,
            Intent(context, co.id.multifab.MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                am.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAt, show), pi)
            } else {
                @Suppress("DEPRECATION")
                am.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            }
        } catch (e: Exception) {
            SmileLog.w(TAG, "setExact failed: ${e.message}")
        }
    }

    private fun pending(context: Context, requestCode: Int, action: String): PendingIntent {
        val intent = Intent(context, LocationWindowReceiver::class.java).setAction(action)
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}

class LocationWindowReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val app = context.applicationContext
        if (LocationTrackingKillSwitch.isOff()) {
            LocationTrackingKillSwitch.enforceOff(app)
            return
        }
        val pending = goAsync()
        val pm = app.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val wake = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "smile:location_window")
        wake?.acquire(60_000L)
        Thread {
            try {
                when (intent?.action) {
                    ACTION_START -> {
                        SmileLog.i(TAG, "alarm START — begin tracking without UI")
                        LocationShiftSync.onWindowStartAlarm()
                        SmileLog.i(TAG, "START done tracking=${NativeLocationController.isTracking()}")
                    }
                    ACTION_STOP -> {
                        SmileLog.i(TAG, "alarm STOP window")
                        LocationShiftSync.onWindowStopAlarm()
                    }
                }
            } catch (e: Exception) {
                SmileLog.w(TAG, "window alarm failed: ${e.message}")
            } finally {
                if (wake?.isHeld == true) runCatching { wake.release() }
                pending.finish()
            }
        }.start()
    }

    companion object {
        private const val TAG = "LocationWindowRx"
        const val ACTION_START = "co.id.multifab.smile.LOCATION_WINDOW_START"
        const val ACTION_STOP = "co.id.multifab.smile.LOCATION_WINDOW_STOP"
    }
}
