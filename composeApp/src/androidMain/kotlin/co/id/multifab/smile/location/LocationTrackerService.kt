/**
 * Status: AKTIF
 * Flow: Sesi FGS singkat per tick — satu titik Fused, lalu stopForeground + stopSelf.
 * Takeout: jangan.
 */
package co.id.multifab.smile.location

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import co.id.multifab.MainActivity
import co.id.multifab.smile.util.SmileLog
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Satu sesi = satu pembacaan lokasi di dalam jam shift, lalu service berhenti.
 * Tidak menahan proses sepanjang shift.
 */
class LocationTrackerService : Service() {

    private val finished = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var cancellation: CancellationTokenSource? = null

    private val timeoutRunnable = Runnable {
        SmileLog.w(TAG, "capture session timeout")
        finishSession()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannelStatic(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                finishSession()
                return START_NOT_STICKY
            }
            ACTION_CAPTURE -> return runCaptureSession()
            ACTION_HOLD -> {
                // Nama lama dari NativeLocationController — tidak menahan FGS sepanjang shift.
                NativeLocationController.onHoldServiceAlive(this)
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (LocationTrackingKillSwitch.isOff()) {
            LocationTrackingKillSwitch.enforceOff(this)
            super.onTaskRemoved(rootIntent)
            return
        }
        if (LocationShiftSync.currentWindow().inWindow) {
            SmileLog.i(TAG, "onTaskRemoved — restore tick chain")
            LocationSessionStore.setEnabled(true)
            NativeLocationController.onHoldServiceAlive(this)
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(timeoutRunnable)
        runCatching { cancellation?.cancel() }
        cancellation = null
        super.onDestroy()
    }

    private fun runCaptureSession(): Int {
        if (LocationTrackingKillSwitch.isOff()) {
            cancelLegacyLocationNotifications(this)
            stopSelf()
            return START_NOT_STICKY
        }
        if (!LocationShiftSync.currentWindow().inWindow) {
            SmileLog.i(TAG, "capture refused — outside shift window")
            cancelLegacyLocationNotifications(this)
            stopSelf()
            return START_NOT_STICKY
        }
        if (!isLocationServicesOn(this)) {
            SmileLog.i(TAG, "capture refused — location services off")
            stopSelf()
            return START_NOT_STICKY
        }
        if (!hasLocationPermission(this)) {
            SmileLog.w(TAG, "capture refused — no permission")
            stopSelf()
            return START_NOT_STICKY
        }

        if (!ensureForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }

        finished.set(false)
        mainHandler.postDelayed(timeoutRunnable, SESSION_TIMEOUT_MS)
        requestOneFix()
        return START_NOT_STICKY
    }

    private fun requestOneFix() {
        val tokenSource = CancellationTokenSource()
        cancellation = tokenSource
        try {
            val client = LocationServices.getFusedLocationProviderClient(this)
            client.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, tokenSource.token)
                .addOnSuccessListener { location ->
                    if (location != null) {
                        acceptFix(location)
                    } else {
                        SmileLog.d(TAG, "fused null — try last known")
                        acceptFix(lastKnownOrNull())
                    }
                    finishSession()
                }
                .addOnFailureListener { e ->
                    SmileLog.w(TAG, "fused failed: ${e.message} — try last known")
                    acceptFix(lastKnownOrNull())
                    finishSession()
                }
        } catch (e: Exception) {
            SmileLog.w(TAG, "fused unavailable: ${e.message} — try last known")
            acceptFix(lastKnownOrNull())
            finishSession()
        }
    }

    private fun acceptFix(location: Location?) {
        if (location == null) {
            SmileLog.d(TAG, "no fix to accept")
            return
        }
        if (!LocationShiftSync.currentWindow().inWindow) {
            SmileLog.i(TAG, "drop fix — left shift window")
            return
        }
        val mocked = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            location.isMock
        } else {
            @Suppress("DEPRECATION")
            location.isFromMockProvider
        }
        val point = JSONObject().apply {
            put("lat", location.latitude)
            put("lng", location.longitude)
            put("accuracy", location.accuracy)
            put("mocked", mocked)
            put("tracked_at", System.currentTimeMillis())
            if (location.hasSpeed()) put("speed", location.speed.toDouble())
            if (location.hasBearing()) put("heading", location.bearing.toDouble())
            if (location.hasAltitude()) put("altitude", location.altitude)
        }
        NativeLocationController.noteLocation(location)
        if (LocationQueueGate.offer(point)) {
            LocationBatchSender.trySendIfDue()
        }
        SmileLog.d(TAG, "captured lat=${location.latitude} lng=${location.longitude}")
    }

    private fun lastKnownOrNull(): Location? {
        if (!hasLocationPermission(this)) return null
        val lm = getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val providers = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(LocationManager.FUSED_PROVIDER)
            add(LocationManager.NETWORK_PROVIDER)
            add(LocationManager.GPS_PROVIDER)
        }
        var best: Location? = null
        for (p in providers) {
            val loc = try {
                lm.getLastKnownLocation(p)
            } catch (_: SecurityException) {
                null
            } catch (_: Exception) {
                null
            } ?: continue
            if (best == null || loc.time > best.time) best = loc
        }
        return best
    }

    private fun finishSession() {
        if (!finished.compareAndSet(false, true)) return
        mainHandler.removeCallbacks(timeoutRunnable)
        runCatching { cancellation?.cancel() }
        cancellation = null
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (_: Exception) {
        }
        cancelLegacyLocationNotifications(this)
        stopSelf()
        SmileLog.d(TAG, "capture session finished")
    }

    private fun ensureForeground(): Boolean {
        return try {
            val notification = buildSessionNotification()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            true
        } catch (e: Exception) {
            SmileLog.e(TAG, "startForeground failed: ${e.message}")
            false
        }
    }

    private fun buildSessionNotification(): Notification {
        val launch = Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pi = PendingIntent.getActivity(
            this,
            PENDING_SESSION_REQUEST,
            launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID).also { builder ->
            co.id.multifab.smile.notify.NotificationIcons.applyForegroundService(builder, this)
        }
            .setContentTitle("Smile Multifab")
            .setContentText("Memperbarui lokasi")
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        private const val TAG = "LocationTracker"
        const val CHANNEL_ID = "smile_location_session"
        const val EXTRA_NATIVE_SCREEN = "smile_native_screen"
        const val SCREEN_DIAGNOSIS = "diagnosis"
        const val ACTION_OPEN_DIAGNOSIS = "co.id.multifab.smile.OPEN_DIAGNOSIS"
        const val ACTION_STOP = "co.id.multifab.smile.LOCATION_STOP"
        const val ACTION_HOLD = "co.id.multifab.smile.LOCATION_HOLD"
        const val ACTION_CAPTURE = "co.id.multifab.smile.LOCATION_CAPTURE"
        private const val PENDING_SESSION_REQUEST = 88011
        private const val NOTIFICATION_ID = 88001
        private const val GPS_ALERT_NOTIFICATION_ID = 88002
        private const val SESSION_TIMEOUT_MS = 20_000L

        fun diagnosisLaunchIntent(context: Context, openDiagnosis: Boolean): Intent {
            return Intent(context, MainActivity::class.java).apply {
                action = if (openDiagnosis) ACTION_OPEN_DIAGNOSIS else Intent.ACTION_MAIN
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
                if (openDiagnosis) {
                    putExtra(EXTRA_NATIVE_SCREEN, SCREEN_DIAGNOSIS)
                }
            }
        }

        /** Tidak menahan FGS sepanjang shift — hanya pulihkan rantai alarm lewat onHoldServiceAlive. */
        fun holdDuringShift(context: Context): Boolean {
            if (LocationTrackingKillSwitch.isOff()) return false
            if (!LocationShiftSync.currentWindow().inWindow) return false
            NativeLocationController.onHoldServiceAlive(context)
            return false
        }

        /** Mulai sesi FGS singkat untuk satu titik Fused (hanya di dalam jendela shift). */
        fun startCaptureSession(context: Context): Boolean {
            if (LocationTrackingKillSwitch.isOff()) return false
            if (!LocationShiftSync.currentWindow().inWindow) return false
            if (!isLocationServicesOn(context)) return false
            if (!hasLocationPermission(context)) return false
            val intent = Intent(context, LocationTrackerService::class.java).apply {
                action = ACTION_CAPTURE
            }
            return startIntent(context, intent)
        }

        private fun startIntent(context: Context, intent: Intent): Boolean {
            return try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                true
            } catch (e: Exception) {
                SmileLog.w(TAG, "Could not start LocationTrackerService: ${e.message}")
                false
            }
        }

        fun stop(context: Context) {
            val stopIntent = Intent(context, LocationTrackerService::class.java).apply {
                action = ACTION_STOP
            }
            try {
                context.startService(stopIntent)
            } catch (_: Exception) {
            }
            context.stopService(Intent(context, LocationTrackerService::class.java))
            cancelLegacyLocationNotifications(context)
        }

        fun trackingNotificationTexts(): Pair<String, String> {
            val loc = co.id.multifab.smile.nativeconfig.NativeConfigStore.get().locationNotification
            return loc.title to loc.readyText
        }

        /** Nama lama: hanya batalkan notifikasi lokasi 88001/88002. */
        fun refreshForegroundNotification(context: Context) {
            cancelLegacyLocationNotifications(context)
        }

        /** Nama lama: hanya batalkan notifikasi lokasi 88001/88002. */
        fun showPreparationNotification(context: Context) {
            cancelLegacyLocationNotifications(context)
        }

        fun cancelTrackingNotification(context: Context) {
            cancelLegacyLocationNotifications(context)
        }

        fun cancelLegacyLocationNotifications(context: Context) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(NOTIFICATION_ID)
            nm.cancel(GPS_ALERT_NOTIFICATION_ID)
            LocationGpsAlert.cancel(context)
        }

        private fun ensureChannelStatic(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Smile Location Session",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Sesi singkat pembacaan lokasi saat jam kerja"
                setSound(null, null)
                enableVibration(false)
            }
            context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }

        private fun hasLocationPermission(context: Context): Boolean {
            val fine = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED
            val coarse = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_COARSE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED
            return fine || coarse
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
}
