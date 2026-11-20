/**
 * Status: AKTIF
 * Flow: Stream fused lokasi saat foreground untuk deteksi/unlock Fake GPS (hemat baterai).
 * Takeout: jangan.
 */
package co.id.multifab.smile.fakegps

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import co.id.multifab.smile.SmileApplication
import co.id.multifab.smile.location.NativeLocationController
import co.id.multifab.smile.util.SmileLog
import co.id.multifab.smile.webview.WebViewNavigationState
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

object FakeGpsMonitor {
    private const val TAG = "FakeGpsMonitor"
    private const val UPDATE_INTERVAL_MS = 4_000L
    private const val MIN_UPDATE_MS = 2_000L
    private const val UPDATE_INTERVAL_LOCKED_MS = 3_000L
    private const val MIN_UPDATE_LOCKED_MS = 1_500L

    @Volatile
    private var running = false

    @Volatile
    private var streamActive = false

    @Volatile
    private var streamWasLocked: Boolean? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    private val fused by lazy {
        LocationServices.getFusedLocationProviderClient(SmileApplication.instance)
    }

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val location = result.lastLocation ?: return
            NativeLocationController.noteLocation(location)
            // Sesuaikan prioritas jika status lock berubah (post agar tidak re-entrant).
            val locked = FakeGpsLock.isLocked()
            if (streamWasLocked != null && streamWasLocked != locked) {
                mainHandler.post { if (running) startLocationStream() }
            }
        }
    }

    fun start() {
        FakeGpsLock.ensureLoaded()
        FakeGpsGuard.syncFromLock()

        if (WebViewNavigationState.isSignInPage()) {
            SmileLog.d(TAG, "start skipped — sign-in")
            return
        }

        val ctx = SmileApplication.instance
        if (!NativeLocationController.hasLocationPermission(ctx)) {
            SmileLog.w(TAG, "start skipped — no location permission")
            return
        }

        if (running) {
            if (!streamActive) startLocationStream()
            return
        }
        running = true
        SmileLog.i(TAG, "stream start locked=${FakeGpsLock.isLocked()}")
        startLocationStream()
    }

    fun stop() {
        if (!running) return
        running = false
        stopLocationStream()
        SmileLog.i(TAG, "stream stop")
    }

    fun requestFreshFixNow() {
        FakeGpsLock.ensureLoaded()
        if (!NativeLocationController.hasLocationPermission(SmileApplication.instance)) return
        @SuppressLint("MissingPermission")
        fused.lastLocation.addOnSuccessListener { loc ->
            if (loc != null) NativeLocationController.noteLocation(loc)
        }
        if (!running) start()
        else if (!streamActive) startLocationStream()
    }

    @SuppressLint("MissingPermission")
    private fun startLocationStream() {
        val ctx = SmileApplication.instance
        if (!NativeLocationController.hasLocationPermission(ctx)) return
        try {
            if (streamActive) {
                fused.removeLocationUpdates(locationCallback)
                streamActive = false
            }
            val locked = FakeGpsLock.isLocked()
            streamWasLocked = locked
            val priority = if (locked) {
                Priority.PRIORITY_HIGH_ACCURACY
            } else {
                Priority.PRIORITY_BALANCED_POWER_ACCURACY
            }
            val interval = if (locked) UPDATE_INTERVAL_LOCKED_MS else UPDATE_INTERVAL_MS
            val minUpdate = if (locked) MIN_UPDATE_LOCKED_MS else MIN_UPDATE_MS
            val request = LocationRequest.Builder(priority, interval)
                .setMinUpdateIntervalMillis(minUpdate)
                .setWaitForAccurateLocation(false)
                .build()
            fused.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
            streamActive = true
            fused.lastLocation.addOnSuccessListener { loc ->
                if (loc != null) NativeLocationController.noteLocation(loc)
            }
        } catch (e: SecurityException) {
            streamActive = false
            SmileLog.w(TAG, "stream denied: ${e.message}")
        } catch (e: Exception) {
            streamActive = false
            SmileLog.w(TAG, "stream error: ${e.message}")
        }
    }

    private fun stopLocationStream() {
        try {
            fused.removeLocationUpdates(locationCallback)
        } catch (_: Exception) {
        }
        streamActive = false
        streamWasLocked = null
    }
}
