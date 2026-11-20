/**
 * Status: AKTIF
 * Flow: Cek GPS perangkat (sama dengan diagnosis GPS_PROVIDER) dan notifikasi alert selama GPS mati di dalam shift.
 * Takeout: jangan.
 */
package co.id.multifab.smile.location

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.LocationManager
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationCompat
import co.id.multifab.smile.SmileApplication

/** Status GPS sama dengan cek diagnosis `GPS_PROVIDER`. */
object LocationGps {
    fun isEnabled(context: Context = SmileApplication.instance): Boolean {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
        return runCatching { lm.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false)
    }
}

/**
 * Notifikasi terpisah saat GPS mati di dalam shift.
 * Tetap tampil sampai GPS dinyalakan. Klik membuka pengaturan lokasi
 * (Settings.ACTION_LOCATION_SOURCE_SETTINGS), sama dengan aksi diagnosis GPS.
 */
object LocationGpsAlert {
    private const val CHANNEL_ID = "smile_gps_alert"
    private const val NOTIFICATION_ID = 88002
    private const val PENDING_REQUEST = 88021

    const val TITLE = "Smile Multifab"
    const val BODY = "GPS Anda mati, silakan aktifkan"

    fun sync(context: Context) {
        if (LocationTrackingKillSwitch.isOff()) {
            cancel(context)
            return
        }
        val inShift = LocationShiftSync.isInCurrentWindow()
        if (inShift && !LocationGps.isEnabled(context)) {
            show(context)
        } else {
            cancel(context)
        }
    }

    private fun show(context: Context) {
        ensureChannel(context)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val settings = Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val pi = PendingIntent.getActivity(
            context,
            PENDING_REQUEST,
            settings,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID).also { builder ->
            co.id.multifab.smile.notify.NotificationIcons.applyForegroundService(builder, context)
        }
            .setContentTitle(TITLE)
            .setContentText(BODY)
            .setStyle(NotificationCompat.BigTextStyle().bigText(BODY))
            .setOngoing(true)
            .setAutoCancel(false)
            .setOnlyAlertOnce(true)
            .setContentIntent(pi)
            .build()
        nm.notify(NOTIFICATION_ID, notification)
    }

    fun cancel(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.cancel(NOTIFICATION_ID)
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Smile GPS",
            NotificationManager.IMPORTANCE_HIGH,
        )
        context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
    }
}

/** Saat pengguna menyalakan atau mematikan GPS, sesuaikan tracking dan notifikasi. */
object LocationGpsMonitor {
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            val action = intent?.action ?: return
            if (
                action != LocationManager.PROVIDERS_CHANGED_ACTION &&
                action != LocationManager.MODE_CHANGED_ACTION
            ) {
                return
            }
            LocationShiftSync.apply()
        }
    }

    fun register(context: Context) {
        val filter = IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION).apply {
            addAction(LocationManager.MODE_CHANGED_ACTION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
    }
}
