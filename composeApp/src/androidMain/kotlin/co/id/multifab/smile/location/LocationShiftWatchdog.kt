/**
 * Status: AKTIF
 * Flow: Alarm cadangan tiap ~10 menit selama shift. Jika OEM memutus rantai tick / FGS,
 * watchdog memanggil apply + startTracking lagi tanpa perlu buka app.
 * Takeout: jangan. Tidak mengubah gate antrian / batch send.
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
 * Cadangan mid-shift: AlarmClock berkala agar tracking tidak mati saat app lama tidak dibuka.
 */
object LocationShiftWatchdog {
    private const val TAG = "LocationWatchdog"
    private const val REQ = 88_041
    const val ACTION = "co.id.multifab.smile.LOCATION_SHIFT_WATCHDOG"
    /** Cukup sering untuk pulih dari OEM kill; tidak menggantikan tick interval. */
    private const val INTERVAL_MS = 10 * 60 * 1000L

    fun schedule(context: Context = SmileApplication.instance) {
        if (LocationTrackingKillSwitch.isOff()) {
            cancel(context)
            return
        }
        if (!LocationSessionStore.inWorkWindow() && !LocationSessionStore.isShiftArmed()) {
            cancel(context)
            return
        }
        if (!LocationSessionStore.inWorkWindow()) {
            // Sebelum jam masuk: window START yang bertugas; watchdog tidak perlu.
            cancel(context)
            return
        }
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val triggerAt = System.currentTimeMillis() + INTERVAL_MS
        val pi = pending(context)
        val show = PendingIntent.getActivity(
            context,
            REQ + 50,
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
            SmileLog.d(TAG, "next watchdog in ${INTERVAL_MS}ms")
        } catch (e: Exception) {
            SmileLog.w(TAG, "schedule failed: ${e.message}")
        }
    }

    fun cancel(context: Context = SmileApplication.instance) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        am.cancel(pending(context))
    }

    private fun pending(context: Context): PendingIntent {
        val intent = Intent(context, LocationShiftWatchdogReceiver::class.java).setAction(ACTION)
        return PendingIntent.getBroadcast(
            context,
            REQ,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}

class LocationShiftWatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != LocationShiftWatchdog.ACTION) return
        val app = context.applicationContext
        if (LocationTrackingKillSwitch.isOff()) {
            LocationTrackingKillSwitch.enforceOff(app)
            return
        }
        val pending = goAsync()
        val pm = app.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val wake = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "smile:location_watchdog")
        wake?.acquire(45_000L)
        Thread {
            try {
                SmileLog.i(TAG, "watchdog fire — restore tracking if in shift")
                LocationShiftSync.apply()
                if (LocationSessionStore.inWorkWindow()) {
                    NativeLocationController.startTracking()
                    // Paksa pasang ulang tick (jika OEM membatalkan AlarmClock lama).
                    LocationTickScheduler.scheduleNext(app, delayMs = 5_000L)
                    LocationShiftWatchdog.schedule(app)
                } else {
                    LocationShiftWatchdog.cancel(app)
                }
            } catch (e: Exception) {
                SmileLog.w(TAG, "watchdog failed: ${e.message}")
                runCatching { LocationShiftWatchdog.schedule(app) }
            } finally {
                if (wake?.isHeld == true) runCatching { wake.release() }
                pending.finish()
            }
        }.start()
    }

    companion object {
        private const val TAG = "LocationWatchdogRx"
    }
}
