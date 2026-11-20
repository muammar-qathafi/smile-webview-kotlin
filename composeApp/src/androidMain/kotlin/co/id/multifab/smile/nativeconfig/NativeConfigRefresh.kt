/**
 * Status: AKTIF
 * Flow: Refresh native-config dari ERP. Absensi masuk memakai workShift baru: tracking langsung atau alarm jam mulai.
 */
package co.id.multifab.smile.nativeconfig

import android.os.Handler
import android.os.Looper
import co.id.multifab.smile.location.LocationSessionStore
import co.id.multifab.smile.location.LocationShiftSync
import co.id.multifab.smile.util.SmileLog
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

object NativeConfigRefresh {
    private const val TAG = "NativeConfigRefresh"
    private const val SOURCE_ATTENDANCE = "attendance-context"
    private val hm = Regex("""\d{2}:\d{2}""")
    private val inFlight = AtomicBoolean(false)
    private val generation = AtomicInteger(0)
    private val main = Handler(Looper.getMainLooper())

    private data class AttendanceShift(
        val timeIn: String,
        val timeOut: String,
        val label: String,
    )

    @Volatile
    private var pendingShift: AttendanceShift? = null

    @Volatile
    private var checkInRequested = false

    /**
     * Jam masuk/pulang dari shift_label halaman absen, misalnya "Normal [08:00 - 17:00]".
     * Native-config ERP tetap di-refresh; jam karyawan ini yang dipakai jendela tracking.
     */
    fun onAttendanceShift(timeIn: String, timeOut: String, label: String) {
        val start = timeIn.trim().take(5)
        val end = timeOut.trim().take(5)
        if (!start.matches(hm) || !end.matches(hm)) {
            SmileLog.w(TAG, "ignore attendance shift $timeIn-$timeOut")
            return
        }
        pendingShift = AttendanceShift(start, end, label.trim())
        SmileLog.i(TAG, "attendance shift $start-$end label=${label.trim()}")
        refreshThenApply("attendance-context")
    }

    /**
     * Absensi masuk: GET native-config, baca workShift lagi.
     * Di dalam jam shift → tracking langsung. Sebelum jam masuk → alarm di awal shift.
     */
    fun afterAttendanceCheckIn() {
        checkInRequested = true
        refreshThenApply("check-in")
    }

    private fun refreshThenApply(reason: String) {
        val gen = generation.incrementAndGet()
        if (!inFlight.compareAndSet(false, true)) {
            SmileLog.d(TAG, "refresh already in flight ($reason)")
            return
        }
        NativeConfigStore.refreshAsync(force = true) { success ->
            main.post {
                val followUp = generation.get() != gen
                applyShiftAfterRefresh(success, reason, checkInRequested)
                if (!followUp) checkInRequested = false
                inFlight.set(false)
                if (followUp) {
                    refreshThenApply("coalesced")
                }
            }
        }
    }

    private fun applyShiftAfterRefresh(success: Boolean, reason: String, fromCheckIn: Boolean) {
        if (fromCheckIn) {
            val shift = NativeConfigStore.workShift()
            LocationSessionStore.applyNativeWorkShift(
                shift.timeIn,
                shift.timeOut,
                shift.label,
                shift.source.ifBlank { "native-config" },
            )
            LocationShiftSync.applyAfterCheckIn()
        } else {
            val shift = pendingShift
            when {
                shift != null ->
                    LocationSessionStore.applyAttendanceShift(shift.timeIn, shift.timeOut, shift.label)
                LocationSessionStore.shiftSource() == SOURCE_ATTENDANCE -> Unit
                success -> LocationSessionStore.clearShiftOverridesFromSession()
            }
            LocationShiftSync.applyCachedOrNormal()
        }
        if (success) {
            SmileLog.i(TAG, "native-config refreshed ($reason)")
        } else {
            SmileLog.w(TAG, "native-config refresh failed ($reason)")
        }
    }
}
