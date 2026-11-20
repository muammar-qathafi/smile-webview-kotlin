/**
 * Status: AKTIF
 * Flow: Saklar tracking shift: jalankan tick Alarm/WorkManager, teruskan titik ke FakeGpsGuard.
 * Takeout: jangan.
 */
package co.id.multifab.smile.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import co.id.multifab.smile.SmileApplication
import co.id.multifab.smile.fakegps.FakeGpsGuard
import co.id.multifab.smile.nativeconfig.NativeConfigStore
import co.id.multifab.smile.util.SmileLog

/**
 * Kontrol tracking: tick Alarm/WorkManager dalam jam shift (bukan stream GPS FGS).
 * Harus bisa start dari alarm START saat app tertutup (tanpa defer ke foreground).
 */
object NativeLocationController {

    private const val TAG = "NativeLocation"

    @Volatile
    private var tracking = false

    @Volatile
    private var lastFix: Location? = null

    @Volatile
    private var lastIntervalMs: Long = 0L

    fun startTracking(intervalMs: Long = NativeConfigStore.locationIntervalMs()) {
        if (LocationTrackingKillSwitch.isOff()) {
            SmileLog.i(TAG, "startTracking skipped — force-off")
            LocationTrackingKillSwitch.enforceOff()
            return
        }
        val resolved = intervalMs.coerceAtLeast(
            NativeConfigStore.get().locationTracking.minIntervalMs.coerceAtLeast(60_000L),
        )
        val ctx = SmileApplication.instance
        if (!hasLocationPermission(ctx)) {
            SmileLog.w(TAG, "startTracking skipped — no location permission")
            return
        }
        if (!LocationSessionStore.inWorkWindow()) {
            SmileLog.i(TAG, "startTracking skipped — outside shift")
            haltCapture()
            return
        }
        startTrackingInternal(ctx, resolved)
    }

    fun applyConfigIntervalIfTracking() {
        if (LocationTrackingKillSwitch.isOff()) return
        if (!tracking) return
        if (!LocationSessionStore.inWorkWindow()) return
        val interval = NativeConfigStore.locationIntervalMs()
        if (lastIntervalMs == interval) return
        startTrackingInternal(SmileApplication.instance, interval)
    }

    private fun startTrackingInternal(ctx: Context, intervalMs: Long) {
        if (LocationTrackingKillSwitch.isOff()) {
            SmileLog.i(TAG, "startTrackingInternal skipped — force-off")
            return
        }
        if (!LocationSessionStore.inWorkWindow()) {
            SmileLog.i(TAG, "startTrackingInternal skipped — outside shift")
            return
        }
        LocationSessionStore.updateIntervalMs(intervalMs)
        tracking = true
        lastIntervalMs = intervalMs
        LocationTickScheduler.ensureRunning(ctx)
        LocationShiftWatchdog.schedule(ctx)
        LocationTrackingWork.enqueuePeriodicUpload(ctx)
        val held = LocationTrackerService.holdDuringShift(ctx)
        if (!held) {
            SmileLog.w(TAG, "shift hold not started — tick/watchdog alarm remains")
        }
        SmileLog.i(TAG, "tick tracking started intervalMs=$intervalMs held=$held")
    }

    /**
     * FGS hidup lagi (START_STICKY / tick) setelah proses di-kill — pulihkan rantai alarm
     * tanpa memanggil holdDuringShift lagi (hindari loop).
     */
    fun onHoldServiceAlive(context: Context = SmileApplication.instance) {
        if (LocationTrackingKillSwitch.isOff()) {
            LocationTrackingKillSwitch.enforceOff(context)
            return
        }
        if (!LocationSessionStore.inWorkWindow()) return
        LocationSessionStore.setEnabled(true)
        if (lastIntervalMs <= 0L) {
            lastIntervalMs = NativeConfigStore.locationIntervalMs()
                .coerceAtLeast(NativeConfigStore.get().locationTracking.minIntervalMs.coerceAtLeast(60_000L))
        }
        tracking = true
        LocationTickScheduler.ensureRunning(context)
        LocationShiftWatchdog.schedule(context)
        LocationTrackingWork.enqueuePeriodicUpload(context)
        SmileLog.i(TAG, "hold service alive — tick/watchdog restored")
    }

    /**
     * Hentikan tick, antrian, dan service lokasi.
     * Alarm jendela shift (START/STOP) tidak dibatalkan.
     */
    fun haltCapture() {
        LocationTickScheduler.cancel()
        LocationShiftWatchdog.cancel()
        LocationTrackerService.stop(SmileApplication.instance)
        LocationTrackingWork.cancelAll()
        tracking = false
        lastIntervalMs = 0L
        SmileLog.i(TAG, "capture halted")
    }

    fun stopTracking() {
        haltCapture()
        LocationWindowAlarm.cancel()
        LocationSessionStore.clearShiftArm()
        SmileLog.i(TAG, "tracking stopped")
    }

    fun isTracking(): Boolean = tracking

    fun hasLocationPermission(context: Context = SmileApplication.instance): Boolean {
        val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    fun hasBackgroundLocationPermission(context: Context = SmileApplication.instance): Boolean {
        if (!hasLocationPermission(context)) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun hasTrackingLocationPermission(context: Context = SmileApplication.instance): Boolean =
        hasLocationPermission(context)

    fun getLastKnownLocation(): Location? {
        lastFix?.let { return it }
        val ctx = SmileApplication.instance
        if (!hasLocationPermission(ctx)) return null
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        return try {
            lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        } catch (_: SecurityException) {
            null
        }
    }

    fun noteLocation(location: Location) {
        lastFix = location
        FakeGpsGuard.onLocation(location)
    }

    /**
     * True jika sticky lock aktif, atau last fix bertanda mock.
     * Tidak fail-open saat lokasi null jika lock masih aktif.
     */
    fun isMockLocation(): Boolean {
        if (co.id.multifab.smile.fakegps.FakeGpsLock.isLocked()) return true
        val loc = lastFix ?: getLastKnownLocation() ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            loc.isMock
        } else {
            @Suppress("DEPRECATION")
            loc.isFromMockProvider
        }
    }
}
