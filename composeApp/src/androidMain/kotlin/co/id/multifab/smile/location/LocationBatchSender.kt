/**
 * Status: AKTIF
 * Flow: Bagian 3 — akumulasi lokal; kirim 1 POST berisi semua pending (maks batchMaxPoints).
 * Gagal/offline: data tetap. Akhir shift: alarm retry berkala (flushMaxAgeMs) sampai antrian kosong.
 * Takeout: jangan.
 */
package co.id.multifab.smile.location

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import co.id.multifab.smile.SmileApplication
import co.id.multifab.smile.config.AppConfig
import co.id.multifab.smile.nativeconfig.NativeConfigStore
import co.id.multifab.smile.util.SmileLog
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Pengirim batch:
 * - Ambang mulai kirim (selama shift): [queueFlushSize].
 * - Isi 1 POST: seluruh antrian sampai [batchMaxPoints].
 * - Akhir shift: kirim sisa berapa pun; jika server/offline gagal → alarm retry [flushMaxAgeMs] sampai kosong.
 */
object LocationBatchSender {
    private const val TAG = "LocationBatchSender"
    private const val REMAINDER_REQ = 88_042
    const val ACTION_REMAINDER_RETRY = "co.id.multifab.smile.LOCATION_REMAINDER_RETRY"

    private val sending = AtomicBoolean(false)

    /**
     * @return true jika tidak perlu kirim, atau kirim sukses (antrian di bawah ambang).
     */
    fun trySendIfDue(): Boolean {
        val pending = NativeLocationStore.pendingCount()
        val threshold = NativeConfigStore.locationQueueFlushSize()
        if (pending < threshold) {
            SmileLog.d(TAG, "skip send — pending=$pending < threshold=$threshold")
            return true
        }
        if (!isNetworkAvailable()) {
            SmileLog.i(TAG, "offline — keep pending=$pending (akumulasi)")
            LocationSessionStore.recordBatchAttempt(success = false, error = "offline")
            return false
        }
        return sendAccumulated(minToSend = threshold)
    }

    /**
     * Akhir shift: kirim seluruh sisa antrian berapa pun (10, 20, 25, …).
     * Gagal (server mati / offline): data tetap + alarm retry berkala sampai kosong.
     */
    fun flushAllPending(): Boolean {
        val pending = NativeLocationStore.pendingCount()
        if (pending <= 0) {
            cancelRemainderRetry()
            return true
        }
        if (!isNetworkAvailable()) {
            SmileLog.i(TAG, "shift-end flush offline — keep pending=$pending")
            LocationSessionStore.recordBatchAttempt(success = false, error = "offline")
            scheduleRemainderRetry()
            return false
        }
        SmileLog.i(TAG, "shift-end flush all pending=$pending")
        val ok = sendAccumulated(minToSend = 1)
        if (NativeLocationStore.pendingCount() <= 0) {
            cancelRemainderRetry()
        } else {
            scheduleRemainderRetry()
        }
        return ok
    }

    /** Dipanggil alarm: coba kirim sisa lagi; jika masih ada, jadwalkan ulang. */
    fun onRemainderRetryAlarm() {
        SmileLog.i(TAG, "remainder retry alarm pending=${NativeLocationStore.pendingCount()}")
        flushAllPending()
    }

    /**
     * POST berisi min(pending, batchMaxPoints) selama pending >= [minToSend].
     */
    private fun sendAccumulated(minToSend: Int): Boolean {
        if (!sending.compareAndSet(false, true)) {
            return false
        }
        try {
            if (!LocationSessionStore.isEnabled() && LocationSessionStore.getSession() == null) {
                return true
            }
            val session = LocationSessionStore.ensureUploadSessionForBatch()
            if (session == null) {
                LocationSessionStore.recordBatchAttempt(success = false, error = "no_upload_session")
                return false
            }
            val fullName = session.optString("fullName").trim()
            if (fullName.isBlank()) {
                LocationSessionStore.recordBatchAttempt(success = false, error = "missing_name")
                return false
            }

            val floor = minToSend.coerceAtLeast(1)
            val maxPerPost = NativeConfigStore.locationBatchMaxPoints().coerceAtLeast(floor)
            var allOk = true

            while (true) {
                val pending = NativeLocationStore.pendingCount()
                if (pending < floor) break
                if (!isNetworkAvailable()) {
                    SmileLog.i(TAG, "offline mid-send — left=$pending")
                    LocationSessionStore.recordBatchAttempt(success = false, error = "offline")
                    allOk = false
                    break
                }
                val take = pending.coerceAtMost(maxPerPost)
                val points = NativeLocationStore.peekPending(limit = take)
                if (points.isEmpty()) break

                val ok = postBatch(fullName, points, session)
                val at = System.currentTimeMillis()
                if (ok) {
                    NativeLocationStore.removeFirst(points.size)
                    LocationSessionStore.recordBatchAttempt(success = true, at = at)
                    SmileLog.i(
                        TAG,
                        "batch OK count=${points.size} left=${NativeLocationStore.pendingCount()}",
                    )
                    continue
                }
                LocationSessionStore.recordBatchAttempt(success = false, at = at, error = "batch_upload_failed")
                SmileLog.w(TAG, "batch failed — keep pending=${NativeLocationStore.pendingCount()}")
                allOk = false
                break
            }
            return allOk
        } finally {
            sending.set(false)
        }
    }

    fun scheduleRemainderRetry(context: Context = SmileApplication.instance) {
        val pending = NativeLocationStore.pendingCount()
        if (pending <= 0) {
            cancelRemainderRetry(context)
            return
        }
        val delay = NativeConfigStore.locationFlushMaxAgeMs()
        val triggerAt = System.currentTimeMillis() + delay
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pi = remainderPending(context)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            } else {
                @Suppress("DEPRECATION")
                am.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            }
            SmileLog.i(TAG, "remainder retry in ${delay}ms pending=$pending")
        } catch (e: Exception) {
            SmileLog.w(TAG, "scheduleRemainderRetry failed: ${e.message}")
            am.set(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + delay,
                pi,
            )
        }
    }

    fun cancelRemainderRetry(context: Context = SmileApplication.instance) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        am.cancel(remainderPending(context))
        SmileLog.d(TAG, "remainder retry cancelled")
    }

    private fun remainderPending(context: Context): PendingIntent {
        val intent = Intent(context, LocationRemainderRetryReceiver::class.java)
            .setAction(ACTION_REMAINDER_RETRY)
        return PendingIntent.getBroadcast(
            context,
            REMAINDER_REQ,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    fun isNetworkAvailable(context: Context = SmileApplication.instance): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun postBatch(
        fullName: String,
        points: List<JSONObject>,
        session: JSONObject,
    ): Boolean {
        val arr = JSONArray()
        for (point in points) {
            arr.put(
                JSONObject().apply {
                    put("lat", point.optDouble("lat"))
                    put("lon", point.optDouble("lng", point.optDouble("lon")))
                    put("accuracy", point.optDouble("accuracy"))
                    putOptionalFinite(point, "speed")
                    putOptionalFinite(point, "heading")
                    putOptionalFinite(point, "altitude")
                    put("tracked_at", formatIsoUtc(point.optLong("tracked_at", System.currentTimeMillis())))
                    put("is_mock_location", if (point.optBoolean("mocked")) 1 else 0)
                },
            )
        }
        val body = JSONObject().apply {
            put("full_name", fullName)
            put("points", arr)
            put(
                "device_info",
                JSONObject().apply {
                    put("platform", "android")
                    put("provider", "smile_native_location")
                    put("employee_id", session.optString("employeeId"))
                },
            )
        }

        val url = URL(AppConfig.LOG_API_BASE.trimEnd('/') + AppConfig.LOCATION_BATCH_PATH)
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 30_000
            doOutput = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Content-Type", "application/json")
        }
        return try {
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val ok = code in 200..299
            if (!ok) {
                val err = runCatching {
                    (if (code >= 400) conn.errorStream else conn.inputStream)
                        ?.bufferedReader()?.use { it.readText() }
                }.getOrNull()
                SmileLog.w(TAG, "batch HTTP $code ${err?.take(200)}")
            }
            ok
        } catch (e: Exception) {
            SmileLog.w(TAG, "batch error: ${e.message}")
            false
        } finally {
            conn.disconnect()
        }
    }

    private fun JSONObject.putOptionalFinite(source: JSONObject, key: String) {
        if (!source.has(key) || source.isNull(key)) return
        val value = source.optDouble(key, Double.NaN)
        if (!value.isFinite()) return
        put(key, value)
    }

    private fun formatIsoUtc(ms: Long): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(Date(ms))
    }
}

/** Alarm khusus sisa antrian setelah gagal di akhir shift / server mati. */
class LocationRemainderRetryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != LocationBatchSender.ACTION_REMAINDER_RETRY) return
        val app = context.applicationContext
        val pending = goAsync()
        val pm = app.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val wake = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "smile:location_remainder")
        wake?.acquire(60_000L)
        Thread {
            try {
                LocationBatchSender.onRemainderRetryAlarm()
            } catch (e: Exception) {
                SmileLog.w("LocationRemainder", "retry failed: ${e.message}")
                if (NativeLocationStore.pendingCount() > 0) {
                    LocationBatchSender.scheduleRemainderRetry(app)
                }
            } finally {
                if (wake?.isHeld == true) runCatching { wake.release() }
                pending.finish()
            }
        }.start()
    }
}

/** Saat jaringan kembali: kirim antrian penuh / sisa akhir shift. */
object LocationNetworkMonitor {
    private val registered = AtomicBoolean(false)

    fun register(context: Context) {
        if (!registered.compareAndSet(false, true)) return
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                SmileLog.i("LocationNet", "network available — send accumulated queue")
                Thread {
                    runCatching {
                        LocationBatchSender.trySendIfDue()
                        if (
                            LocationShiftSync.currentPhase() == LocationShiftSync.CapturePhase.Ended ||
                            NativeLocationStore.pendingCount() > 0 &&
                            !LocationShiftSync.isInCurrentWindow()
                        ) {
                            LocationBatchSender.flushAllPending()
                        }
                    }
                }.start()
            }
        }
        runCatching {
            cm.registerNetworkCallback(request, callback)
        }.onFailure {
            registered.set(false)
            SmileLog.w("LocationNet", "register failed: ${it.message}")
        }
    }
}
