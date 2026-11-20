/**
 * Status: AKTIF
 * Flow: Hitung jendela kerja WIB dari native-config + session, lalu nyalakan/matikan tracking.
 * Takeout: jangan.
 */
package co.id.multifab.smile.location

import co.id.multifab.smile.util.SmileLog

import co.id.multifab.smile.SmileApplication
import co.id.multifab.smile.config.AppConfig
import co.id.multifab.smile.nativeconfig.NativeConfigStore
import co.id.multifab.smile.session.LoginSessionStore
import org.json.JSONObject
import java.util.Calendar
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Jam shift: session attendance-context bila ada, selain itu [NativeConfigStore.workShift].
 * Dihitung ulang setiap apply dari HH:mm agar cache kemarin tidak mematikan tracking hari ini.
 */
object LocationShiftSync {
    private const val TAG = "LocationShift"
    private const val DAY_MS = 24 * 60 * 60 * 1000L
    /** AlarmClock kadang fire sedikit sebelum jam tepat — tetap anggap saatnya mulai. */
    private const val START_EARLY_GRACE_MS = 120_000L

    private val jakarta = TimeZone.getTimeZone("Asia/Jakarta")
    private val applying = AtomicBoolean(false)

    /** apply() sedang jalan — jalankan ulang setelah selesai (alarm START tidak boleh hilang). */
    @Volatile
    private var reapplyPending = false

    /** Satu status untuk tick lokasi dan teks notifikasi. */
    enum class CapturePhase {
        Idle,
        Waiting,
        Tracking,
        Ended,
    }

    data class WorkWindow(
        val source: String,
        val label: String,
        val timeIn: String,
        val timeOut: String,
        val overnight: Boolean,
        val inWindow: Boolean,
        val workStartAt: Long,
        val workEndAt: Long,
    )

    /**
     * Pastikan tracking sesuai jendela WIB saat ini (dari native-config lokal).
     * Tidak fetch BE berulang — config di-refresh sekali di cold start.
     */
    fun ensureTrackingForCurrentWindow() {
        apply()
    }

    fun applyCachedOrNormal() {
        apply()
    }

    /**
     * Satu-satunya yang menyalakan atau mematikan tick dan notifikasi tracking.
     * Waiting: sebelum jam masuk, tick mati, notifikasi persiapan.
     * Tracking: tick jalan hanya jika GPS nyala, deskripsi notifikasi = readyText.
     * GPS mati di dalam shift: titik tidak disimpan, deskripsi = ucapan terima kasih, plus alert GPS.
     * Ended: tick mati, ucapan terima kasih; sisa antrian berapa pun dikirim sekali (flushAllPending).
     */
    fun apply(): CapturePhase {
        if (!applying.compareAndSet(false, true)) {
            reapplyPending = true
            return currentPhase()
        }
        var phase = CapturePhase.Idle
        try {
            do {
                reapplyPending = false
                phase = applyOnce()
            } while (reapplyPending)
        } finally {
            applying.set(false)
        }
        if (reapplyPending) return apply()
        return phase
    }

    private fun applyOnce(): CapturePhase {
        if (LocationTrackingKillSwitch.isOff()) {
            LocationTrackingKillSwitch.enforceOff()
            SmileLog.i(TAG, "phase=Idle — location tracking force-off")
            return CapturePhase.Idle
        }
        if (!LoginSessionStore.isLoggedIn()) {
            NativeLocationController.haltCapture()
            LocationSessionStore.clearShiftArm()
            LocationTrackerService.refreshForegroundNotification(SmileApplication.instance)
            return CapturePhase.Idle
        }
        val window = currentWindow()
        persistWindow(window)
        val phase = phaseOf(window)
        // Alarm START/STOP harus tetap terpasang saat Waiting (setelah absen masuk).
        LocationWindowAlarm.schedule(window)
        when (phase) {
            CapturePhase.Tracking -> beginTracking(window)
            CapturePhase.Waiting -> {
                // Jangan batalkan alarm jendela — hanya hentikan tick sampai jam masuk.
                NativeLocationController.haltCapture()
                LocationSessionStore.setEnabled(false)
            }
            CapturePhase.Ended -> {
                // Akhir shift: sisa antrian berapa pun (di bawah queueFlushSize) ikut dikirim.
                LocationBatchSender.flushAllPending()
                NativeLocationController.haltCapture()
                LocationSessionStore.setEnabled(false)
                LocationSessionStore.clearShiftArm()
            }
            CapturePhase.Idle -> Unit
        }
        LocationTrackerService.refreshForegroundNotification(SmileApplication.instance)
        SmileLog.i(
            TAG,
            "phase=$phase source=${window.source} ${window.label} ${window.timeIn}-${window.timeOut} " +
                "inWindow=${window.inWindow} tracking=${NativeLocationController.isTracking()} tz=Asia/Jakarta " +
                "startAt=${window.workStartAt} endAt=${window.workEndAt} armed=${LocationSessionStore.isShiftArmed()}",
        )
        return phase
    }

    /**
     * Alarm START jam shift: nyalakan tracking tanpa membuka UI.
     * Toleransi sedikit sebelum/sesudah workStartAt agar AlarmClock yang fire lebih awal tetap jalan.
     */
    fun onWindowStartAlarm() {
        if (LocationTrackingKillSwitch.isOff()) {
            LocationTrackingKillSwitch.enforceOff()
            SmileLog.i(TAG, "START alarm ignored — force-off")
            return
        }
        if (!LoginSessionStore.isLoggedIn()) {
            SmileLog.w(TAG, "START alarm ignored — not logged in")
            return
        }
        val window = currentWindow()
        val now = System.currentTimeMillis()
        when {
            now > window.workEndAt -> {
                SmileLog.i(TAG, "START alarm after end — apply Ended")
                apply()
            }
            now + START_EARLY_GRACE_MS < window.workStartAt -> {
                SmileLog.i(TAG, "START alarm too early — reschedule")
                LocationWindowAlarm.schedule(window)
            }
            else -> {
                // Sudah waktunya (atau ≤2 menit sebelum jam masuk).
                LocationSessionStore.armShiftUntil(window.workEndAt)
                beginTracking(window)
                LocationWindowAlarm.schedule(window)
                LocationTrackerService.refreshForegroundNotification(SmileApplication.instance)
                SmileLog.i(
                    TAG,
                    "START alarm → tracking=${NativeLocationController.isTracking()} " +
                        "${window.timeIn}-${window.timeOut}",
                )
            }
        }
    }

    fun onWindowStopAlarm() {
        apply()
    }

    private fun beginTracking(window: WorkWindow) {
        if (LocationTrackingKillSwitch.isOff()) {
            LocationTrackingKillSwitch.enforceOff()
            return
        }
        persistWindow(window)
        LocationSessionStore.setEnabled(true)
        LocationSessionStore.armShiftUntil(window.workEndAt)
        NativeLocationController.startTracking(NativeConfigStore.locationIntervalMs())
        if (NativeLocationController.isTracking()) {
            LocationTrackingWork.enqueuePeriodicUpload()
        }
    }

    fun currentPhase(now: Long = System.currentTimeMillis()): CapturePhase {
        if (!LoginSessionStore.isLoggedIn()) return CapturePhase.Idle
        return phaseOf(currentWindow(now), now)
    }

    /**
     * Teks perusahaan hanya saat tick benar-benar jalan dan GPS nyala.
     * Shift selesai, atau GPS mati di dalam shift: ucapan terima kasih (shiftEndedText).
     */
    fun notificationPhase(now: Long = System.currentTimeMillis()): CapturePhase {
        val phase = currentPhase(now)
        if (phase == CapturePhase.Tracking && !LocationGps.isEnabled()) {
            return CapturePhase.Ended
        }
        return when {
            phase == CapturePhase.Ended -> CapturePhase.Ended
            phase == CapturePhase.Tracking && NativeLocationController.isTracking() -> CapturePhase.Tracking
            phase == CapturePhase.Idle -> CapturePhase.Idle
            else -> CapturePhase.Waiting
        }
    }

    /**
     * Setelah absensi masuk (klik record check-in) dan native-config terbaca ulang.
     * Di dalam shift → tracking langsung.
     * Sebelum jam masuk → arm + alarm START di workStartAt (jalan meski app ditutup).
     */
    fun applyAfterCheckIn() {
        if (LocationTrackingKillSwitch.isOff()) {
            LocationTrackingKillSwitch.enforceOff()
            SmileLog.i(TAG, "check-in ignored for tracking — force-off")
            return
        }
        val window = currentWindow()
        persistWindow(window)
        LocationSessionStore.armShiftUntil(window.workEndAt)
        LocationWindowAlarm.schedule(window)
        val phase = phaseOf(window)
        when (phase) {
            CapturePhase.Tracking -> {
                beginTracking(window)
                LocationTrackerService.refreshForegroundNotification(SmileApplication.instance)
                SmileLog.i(TAG, "check-in inside shift — start tracking ${window.timeIn}-${window.timeOut}")
            }
            CapturePhase.Waiting -> {
                NativeLocationController.haltCapture()
                LocationSessionStore.setEnabled(false)
                LocationTrackerService.refreshForegroundNotification(SmileApplication.instance)
                SmileLog.i(
                    TAG,
                    "check-in before shift — START alarm armed at ${window.timeIn} (${window.workStartAt})",
                )
            }
            CapturePhase.Ended -> {
                SmileLog.i(
                    TAG,
                    "check-in after shift end — NEXT START at ${window.timeIn} (${window.workStartAt})",
                )
                LocationTrackerService.refreshForegroundNotification(SmileApplication.instance)
            }
            CapturePhase.Idle -> Unit
        }
    }

    fun currentWindow(now: Long = System.currentTimeMillis()): WorkWindow {
        val shift = NativeConfigStore.workShift()
        val session = LocationSessionStore.getSession()
        val timeIn = firstHm(
            session?.optString("shiftTimeIn"),
            shift.timeIn,
            "08:00",
        )
        val timeOut = firstHm(
            session?.optString("shiftTimeOut"),
            shift.timeOut,
            "17:00",
        )
        val source = session?.optString("shiftSource")?.takeIf { it.isNotBlank() }
            ?: shift.source.takeIf { it.isNotBlank() }
            ?: "normal"
        val label = session?.optString("shiftLabel")?.takeIf { it.isNotBlank() }
            ?: shift.label.takeIf { it.isNotBlank() }
            ?: if (source == "schedule") "Shift terjadwal" else "Normal"
        return computeWorkWindow(timeIn, timeOut, source, label, now)
    }

    fun isInCurrentWindow(now: Long = System.currentTimeMillis()): Boolean =
        currentWindow(now).inWindow

    /**
     * Alarm START sering fire sedikit lebih awal. Anggap sudah masuk jendela
     * jika sudah di-arm (absen masuk) dan dalam [START_EARLY_GRACE_MS] sebelum jam masuk.
     */
    fun isNearOrInWindow(now: Long = System.currentTimeMillis()): Boolean {
        val window = currentWindow(now)
        if (window.inWindow) return true
        if (!LocationSessionStore.isShiftArmed(now)) return false
        return now >= window.workStartAt - START_EARLY_GRACE_MS && now <= window.workEndAt
    }

    /**
     * Shift hari ini sudah lewat jam pulang dari session / native-config.
     * Sebelum jam masuk, ini false — ucapan terima kasih hanya setelah jam kerja habis.
     */
    fun hasShiftEndedToday(now: Long = System.currentTimeMillis()): Boolean {
        val window = currentWindow(now)
        if (window.inWindow) return false
        val (inH, inM) = splitHm(window.timeIn, 8, 0)
        val (outH, outM) = splitHm(window.timeOut, 17, 0)
        val startToday = clockOnDay(now, inH, inM, 0)
        val endToday = clockOnDay(now, outH, outM, 59) + 999L
        return if (window.overnight) {
            now >= endToday && now < startToday
        } else {
            now > endToday
        }
    }

    private fun phaseOf(window: WorkWindow, now: Long = System.currentTimeMillis()): CapturePhase {
        if (window.inWindow) return CapturePhase.Tracking
        // Setelah absen masuk (armed): izinkan Tracking sedikit sebelum jam masuk
        // agar apply() tidak mematikan hasil alarm START yang fire lebih awal.
        if (LocationSessionStore.isShiftArmed(now) &&
            now >= window.workStartAt - START_EARLY_GRACE_MS &&
            now <= window.workEndAt
        ) {
            return CapturePhase.Tracking
        }
        if (hasShiftEndedToday(now)) return CapturePhase.Ended
        return CapturePhase.Waiting
    }

    private fun persistWindow(window: WorkWindow) {
        val session = LocationSessionStore.getSession() ?: JSONObject()
        if (session.optString("fullName").isBlank()) {
            val name = LoginSessionStore.displayName().trim()
            if (name.isNotBlank()) session.put("fullName", name)
        }
        if (session.optString("employeeId").isBlank()) {
            session.put("employeeId", LoginSessionStore.badge())
        }
        if (session.optString("authToken").isBlank()) {
            session.put("authToken", LoginSessionStore.authToken())
        }
        if (session.optString("endpointBase").isBlank()) {
            session.put("endpointBase", AppConfig.LOG_API_BASE)
        }
        session.put("workStartAt", window.workStartAt)
        session.put("workEndAt", window.workEndAt)
        session.put("shiftLabel", window.label)
        session.put("shiftSource", window.source)
        session.put("shiftTimeIn", window.timeIn)
        session.put("shiftTimeOut", window.timeOut)
        session.put("intervalMs", NativeConfigStore.locationIntervalMs())
        LocationSessionStore.saveSession(session)
    }

    fun computeWorkWindow(
        timeIn: String,
        timeOut: String,
        source: String,
        label: String,
        now: Long = System.currentTimeMillis(),
    ): WorkWindow {
        val (inH, inM) = splitHm(timeIn, 8, 0)
        val (outH, outM) = splitHm(timeOut, 17, 0)
        val overnight = (outH * 60 + outM) < (inH * 60 + inM)
        val start: Long
        val end: Long
        if (overnight) {
            val endToday = clockOnDay(now, outH, outM, 0)
            if (now < endToday) {
                start = clockOnDay(now, inH, inM, 0) - DAY_MS
                end = endToday
            } else {
                start = clockOnDay(now, inH, inM, 0)
                end = clockOnDay(now, outH, outM, 0) + DAY_MS
            }
        } else {
            val startToday = clockOnDay(now, inH, inM, 0)
            val endToday = clockOnDay(now, outH, outM, 59) + 999L
            if (now > endToday) {
                start = startToday + DAY_MS
                end = endToday + DAY_MS
            } else {
                start = startToday
                end = endToday
            }
        }
        return WorkWindow(
            source = source,
            label = label,
            timeIn = "%02d:%02d".format(inH, inM),
            timeOut = "%02d:%02d".format(outH, outM),
            overnight = overnight,
            inWindow = now in start..end,
            workStartAt = start,
            workEndAt = end,
        )
    }

    private fun firstHm(vararg values: String?): String {
        for (value in values) {
            val hm = value?.trim()?.take(5).orEmpty()
            if (hm.matches(Regex("\\d{2}:\\d{2}"))) return hm
        }
        return "08:00"
    }

    private fun splitHm(hm: String, defaultH: Int, defaultM: Int): Pair<Int, Int> {
        val match = Regex("(\\d{2}):(\\d{2})").find(hm) ?: return defaultH to defaultM
        return match.groupValues[1].toInt() to match.groupValues[2].toInt()
    }

    private fun clockOnDay(now: Long, hour: Int, minute: Int, second: Int): Long {
        return Calendar.getInstance(jakarta).apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, second)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
    }
}
