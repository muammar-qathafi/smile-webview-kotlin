/**
 * Status: AKTIF
 * Flow: Jalur tracking yang sedang dipakai: tick alarm, tangkap satu titik, antrian upload, dan worker kirim batch. Bukan stream GPS terus-menerus.
 * Takeout: jangan. Berisi LocationTickWorker, LocationUploadWorker, LocationTickScheduler, LocationTickReceiver, LocationTickCapture.
 */
package co.id.multifab.smile.location

import co.id.multifab.smile.util.SmileLog

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import co.id.multifab.smile.SmileApplication
import co.id.multifab.smile.nativeconfig.NativeConfigStore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Tick tracking berbasis AlarmClock + WorkManager backup.
 * Satu tick = satu lokasi + flush batch (bukan stream GPS).
 */
class LocationTickWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (LocationTrackingKillSwitch.isOff()) {
            LocationTrackingKillSwitch.enforceOff(applicationContext)
            return Result.success()
        }
        if (!LocationSessionStore.isEnabled()) return Result.success()
        LocationShiftSync.applyCachedOrNormal()
        if (!LocationShiftSync.currentWindow().inWindow) {
            return Result.success()
        }
        LocationTickCapture.captureOnce(applicationContext)
        LocationBatchSender.trySendIfDue()
        LocationTickScheduler.scheduleNext(applicationContext)
        return Result.success()
    }
}

class LocationUploadWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        if (LocationTrackingKillSwitch.isOff()) {
            LocationTrackingKillSwitch.enforceOff(applicationContext)
            return Result.success()
        }
        if (!LocationSessionStore.isEnabled() &&
            !co.id.multifab.smile.session.LoginSessionStore.isLoggedIn() &&
            NativeLocationStore.pendingCount() <= 0
        ) {
            return Result.success()
        }
        // Cadangan mid-shift: pulihkan tracking jika rantai alarm putus (app lama tidak dibuka).
        LocationShiftSync.apply()
        if (LocationSessionStore.inWorkWindow()) {
            NativeLocationController.startTracking()
        }
        // Hormati queueFlushSize + jeda retry; tidak force kirim di bawah batas.
        LocationBatchSender.trySendIfDue()
        return Result.success()
    }
}

object LocationTrackingWork {
    const val UNIQUE_UPLOAD = "smile-feature-location-upload"
    private const val UNIQUE_FLUSH_LEGACY = "smile-feature-location-flush"
    const val UNIQUE_TICK = "smile-feature-location-tick"

    fun enqueuePeriodicUpload(context: Context = SmileApplication.instance) {
        // Backup 15 menit (batas minimum WM) — tick akurat lewat AlarmClock.
        val request = PeriodicWorkRequestBuilder<LocationUploadWorker>(15, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UNIQUE_UPLOAD,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    fun cancelPeriodicUpload(context: Context = SmileApplication.instance) {
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_UPLOAD)
    }

    fun cancelAll(context: Context = SmileApplication.instance) {
        val wm = WorkManager.getInstance(context)
        wm.cancelUniqueWork(UNIQUE_UPLOAD)
        wm.cancelUniqueWork(UNIQUE_FLUSH_LEGACY)
        wm.cancelUniqueWork(UNIQUE_TICK)
        LocationTickScheduler.cancel(context)
    }
}

/** AlarmClock chain tiap intervalMs selama dalam shift. */
object LocationTickScheduler {
    private const val TAG = "LocationTickSched"
    private const val PREFS = "smile_location_tick"
    private const val KEY_NEXT = "next_trigger_at_ms"
    private const val REQ = 88_031
    const val ACTION_TICK = "co.id.multifab.smile.LOCATION_TICK"

    /** Waktu alarm berikutnya (memory + prefs agar survive process death). */
    @Volatile
    private var nextTriggerAtMs: Long = 0L

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun ensureRunning(context: Context = SmileApplication.instance) {
        if (LocationTrackingKillSwitch.isOff()) {
            cancel(context)
            return
        }
        if (!LocationSessionStore.isEnabled() || !LocationSessionStore.inWorkWindow()) {
            cancel(context)
            return
        }
        WorkManager.getInstance(context).cancelUniqueWork(LocationTrackingWork.UNIQUE_TICK)
        val now = System.currentTimeMillis()
        val stored = prefs(context).getLong(KEY_NEXT, 0L)
        if (stored > nextTriggerAtMs) nextTriggerAtMs = stored
        // Alarm masih di masa depan → biarkan. Sudah lewat / hilang → jadwalkan segera.
        if (nextTriggerAtMs > now + 1_000L) return
        scheduleNext(context, delayMs = 3_000L)
    }

    fun scheduleNext(context: Context = SmileApplication.instance, delayMs: Long? = null) {
        if (LocationTrackingKillSwitch.isOff()) {
            cancel(context)
            return
        }
        if (!LocationSessionStore.isEnabled() || !LocationSessionStore.inWorkWindow()) {
            cancel(context)
            return
        }
        val configured = NativeConfigStore.locationIntervalMs().coerceAtLeast(60_000L)
        val interval = if (delayMs != null && delayMs < configured) delayMs else configured
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val triggerAt = System.currentTimeMillis() + interval
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
            nextTriggerAtMs = triggerAt
            prefs(context).edit().putLong(KEY_NEXT, triggerAt).commit()
            SmileLog.d(TAG, "next tick in ${interval}ms")
        } catch (e: Exception) {
            SmileLog.w(TAG, "scheduleNext failed: ${e.message}")
            am.set(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + interval,
                pi,
            )
            nextTriggerAtMs = triggerAt
            prefs(context).edit().putLong(KEY_NEXT, triggerAt).commit()
        }
    }

    fun cancel(context: Context = SmileApplication.instance) {
        nextTriggerAtMs = 0L
        prefs(context).edit().remove(KEY_NEXT).commit()
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        am.cancel(pending(context))
    }

    private fun pending(context: Context): PendingIntent {
        val intent = Intent(context, LocationTickReceiver::class.java).setAction(ACTION_TICK)
        return PendingIntent.getBroadcast(
            context,
            REQ,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}

class LocationTickReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != LocationTickScheduler.ACTION_TICK) return
        val app = context.applicationContext
        if (LocationTrackingKillSwitch.isOff()) {
            LocationTrackingKillSwitch.enforceOff(app)
            return
        }
        val pending = goAsync()
        val pm = app.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val wake = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "smile:location_tick")
        wake?.acquire(30_000L)
        Thread {
            try {
                if (!LocationSessionStore.isEnabled() || !LocationShiftSync.currentWindow().inWindow) {
                    LocationShiftSync.applyCachedOrNormal()
                    if (!LocationShiftSync.currentWindow().inWindow) return@Thread
                }
                // Pulihkan rantai alarm; capture lewat sesi FGS singkat (bukan hold sepanjang shift).
                NativeLocationController.onHoldServiceAlive(app)
                LocationTickCapture.captureOnce(app)
                LocationBatchSender.trySendIfDue()
                LocationTickScheduler.scheduleNext(app)
            } catch (e: Exception) {
                SmileLog.w("LocationTickRx", "tick failed: ${e.message}")
                LocationTickScheduler.scheduleNext(app)
            } finally {
                if (wake?.isHeld == true) runCatching { wake.release() }
                pending.finish()
            }
        }.start()
    }
}

object LocationTickCapture {
    private const val TAG = "LocationTickCap"
    private val lastCapturedAtMs = AtomicLong(0L)

    /**
     * Bagian 1: minta satu titik lewat sesi FGS singkat + Fused.
     * Hanya saat [LocationShiftSync.currentWindow].inWindow.
     */
    fun captureOnce(context: Context) {
        if (LocationTrackingKillSwitch.isOff()) {
            SmileLog.i(TAG, "skip capture — force-off")
            return
        }
        if (!LocationShiftSync.currentWindow().inWindow) {
            SmileLog.i(TAG, "skip capture — di luar jam shift")
            LocationTrackerService.refreshForegroundNotification(context)
            return
        }
        if (!isLocationServicesOn(context)) {
            SmileLog.i(TAG, "skip capture — layanan lokasi mati")
            return
        }
        val now = System.currentTimeMillis()
        val minGap = NativeConfigStore.locationIntervalMs().coerceAtLeast(60_000L)
        while (true) {
            val previous = lastCapturedAtMs.get()
            if (previous > 0L && now - previous < minGap) {
                SmileLog.d(TAG, "skip capture — ${now - previous}ms < ${minGap}ms")
                return
            }
            if (lastCapturedAtMs.compareAndSet(previous, now)) break
        }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION)
            != PackageManager.PERMISSION_GRANTED
        ) {
            SmileLog.w(TAG, "no location permission")
            return
        }
        val started = LocationTrackerService.startCaptureSession(context)
        SmileLog.d(TAG, "capture session started=$started")
    }

    private fun isLocationServicesOn(context: Context): Boolean {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { lm.isLocationEnabled }.getOrDefault(false)
        } else {
            runCatching {
                lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                    lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
            }.getOrDefault(false)
        }
    }
}
