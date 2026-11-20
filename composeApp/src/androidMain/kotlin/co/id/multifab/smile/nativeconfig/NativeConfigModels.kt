/**
 * Status: AKTIF
 * Flow: Model JSON native-config (tracking, diagnosis, Fake GPS, TTS). Beberapa field Fake GPS (blockOnJump, jumpThreshold, blockOnAttendanceRoutes) di-parse tetapi tidak dibaca detector.
 * Takeout: jangan hapus file. Field jump/rute itu kandidat dibuang dari model, bukan dari file.
 */
package co.id.multifab.smile.nativeconfig

import org.json.JSONArray
import org.json.JSONObject

data class LocationNotificationConfig(
    val title: String,
    val readyText: String,
    val incompleteText: String,
    /** Muncul di notifikasi tracking setelah jam shift habis. Diedit di native-config BE. */
    val shiftEndedText: String,
) {
    fun resolvedShiftEndedText(): String =
        shiftEndedText.trim().ifBlank { DEFAULT_SHIFT_ENDED_TEXT }

    companion object {
        const val DEFAULT_SHIFT_ENDED_TEXT = "Terima kasih atas kinerja Anda hari ini."
    }
}

data class WorkShiftConfig(
    val timeIn: String,
    val timeOut: String,
    val timezone: String,
    val source: String = "normal",
    val label: String = "Normal",
)

data class LocationTrackingConfig(
    val intervalMs: Long,
    val minIntervalMs: Long,
    /** Jumlah titik lokal sebelum POST batch. */
    val queueFlushSize: Int,
    /** Batas referensi antrian (status/UI). Antrian tidak di-trim; titik dihapus hanya setelah kirim sukses. */
    val maxLocalQueue: Int,
    /** Jeda alarm retry sisa antrian setelah gagal di akhir shift / server mati. */
    val flushMaxAgeMs: Long,
    /** Maks titik per 1 HTTP batch. */
    val batchMaxPoints: Int,
    /** Selalu "batch" — tidak ada loop POST per titik. */
    val uploadMode: String,
    val workShift: WorkShiftConfig,
) {
    fun resolvedIntervalMs(): Long = intervalMs.coerceAtLeast(minIntervalMs.coerceAtLeast(60_000L))

    fun resolvedMaxLocalQueue(): Int = maxLocalQueue.coerceIn(100, 10_000)

    fun resolvedQueueFlushSize(): Int {
        val max = resolvedMaxLocalQueue()
        return queueFlushSize.coerceIn(1, max)
    }

    fun resolvedFlushMaxAgeMs(): Long = flushMaxAgeMs.coerceIn(60_000L, 60 * 60_000L)

    fun resolvedBatchMaxPoints(): Int = batchMaxPoints.coerceIn(1, 2_000)
}

data class DiagnosisItemConfig(
    val id: String,
    val title: String,
    val description: String,
    val check: String,
    val action: String?,
)

data class DiagnosisConfig(
    val title: String,
    val subtitle: String,
    val items: List<DiagnosisItemConfig>,
)

data class FakeGpsConfig(
    val enabled: Boolean,
    val blockOnMock: Boolean,
    val blockOnJump: Boolean,
    val jumpThresholdMeters: Double,
    val jumpWindowMs: Long,
    val blockOnAttendanceRoutes: Boolean,
    val title: String,
    val subtitle: String,
    val body: String,
    val warning: String,
    val steps: List<String>,
    val retryLabel: String,
    val homeLabel: String,
)

/**
 * Preferensi suara TextToSpeech native.
 * [voiceGender]: `"female"` | `"male"` | `"default"` (engine default setelah setLanguage).
 * Daftar voice aktual bergantung engine di perangkat (Google/Samsung/dll).
 */
data class TtsConfig(
    val voiceGender: String,
) {
    fun resolvedVoiceGender(): String = when (voiceGender.trim().lowercase()) {
        "female", "f", "wanita" -> "female"
        "male", "m", "pria" -> "male"
        else -> "default"
    }
}

data class NativeAppConfig(
    val version: Int,
    val locationNotification: LocationNotificationConfig,
    val locationTracking: LocationTrackingConfig,
    val diagnosis: DiagnosisConfig,
    val fakeGps: FakeGpsConfig,
    val tts: TtsConfig,
) {
    companion object {
        /**
         * Fallback cold-start/offline saja (belum pernah sukses fetch BE).
         * Kebijakan interval production: ubah HANYA di BE
         * `mfg-datacore-v2-be/resources/smile/native-config.json` lalu deploy API.
         * Jangan ubah konstanta ini untuk ganti kebijakan bisnis setelah Play Store.
         */
        const val BOOTSTRAP_INTERVAL_MS = 60_000L
        const val BOOTSTRAP_MIN_INTERVAL_MS = 60_000L
        const val BOOTSTRAP_QUEUE_FLUSH_SIZE = 30
        const val BOOTSTRAP_MAX_LOCAL_QUEUE = 200
        const val BOOTSTRAP_FLUSH_MAX_AGE_MS = 10 * 60_000L
        const val BOOTSTRAP_BATCH_MAX_POINTS = 50
        const val BOOTSTRAP_TTS_VOICE_GENDER = "female"

        private val droppedDiagnosisChecks = setOf(
            "IGNORE_BATTERY_OPTIMIZATIONS",
            "FIREBASE_CONFIG",
            "NOTIFICATION_CHANNEL",
            "READ_MEDIA_IMAGES",
            "READ_MEDIA",
            "READ_EXTERNAL_STORAGE",
        )
        private val droppedDiagnosisIds = setOf(
            "battery_optimization",
            "fcm_configured",
            "notification_channel",
            "media_permission",
        )

        fun fromJson(json: JSONObject): NativeAppConfig {
            val loc = json.optJSONObject("locationNotification") ?: JSONObject()
            val tracking = json.optJSONObject("locationTracking") ?: JSONObject()
            val diag = json.optJSONObject("diagnosis") ?: JSONObject()
            val fake = json.optJSONObject("fakeGps") ?: JSONObject()
            val tts = json.optJSONObject("tts") ?: JSONObject()
            val items = mutableListOf<DiagnosisItemConfig>()
            diag.optJSONArray("items")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    items.add(
                        DiagnosisItemConfig(
                            id = o.optString("id"),
                            title = o.optString("title"),
                            description = o.optString("description"),
                            check = o.optString("check"),
                            action = o.optString("action").takeIf { it.isNotBlank() },
                        ),
                    )
                }
            }
            sanitizeDiagnosisItems(items)
            if (items.none { it.check == "LOGIN" || it.id == "login" }) {
                items.add(
                    0,
                    DiagnosisItemConfig(
                        id = "login",
                        title = "Login",
                        description = "Profil user di cache lokal setelah login — dipakai registrasi FCM dan cek native lain.",
                        check = "LOGIN",
                        action = "retry",
                    ),
                )
            }
            val steps = mutableListOf<String>()
            fake.optJSONArray("steps")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val s = arr.optString(i).trim()
                    if (s.isNotEmpty()) steps.add(s)
                }
            }
            return NativeAppConfig(
                version = json.optInt("version", 1),
                locationNotification = LocationNotificationConfig(
                    title = loc.optString("title", "Smile Multifab"),
                    readyText = loc.optString("readyText", "PT Multi Fabrindo Gemilang"),
                    incompleteText = loc.optString(
                        "incompleteText",
                        "Persiapan Ruang Lingkup Kerja",
                    ),
                    shiftEndedText = loc.optString(
                        "shiftEndedText",
                        LocationNotificationConfig.DEFAULT_SHIFT_ENDED_TEXT,
                    ),
                ),
                locationTracking = LocationTrackingConfig(
                    // Default hanya bootstrap; nilai production datang dari BE.
                    intervalMs = tracking.optLong("intervalMs", BOOTSTRAP_INTERVAL_MS),
                    minIntervalMs = tracking.optLong("minIntervalMs", BOOTSTRAP_MIN_INTERVAL_MS),
                    queueFlushSize = tracking.optInt("queueFlushSize", BOOTSTRAP_QUEUE_FLUSH_SIZE),
                    maxLocalQueue = tracking.optInt("maxLocalQueue", BOOTSTRAP_MAX_LOCAL_QUEUE),
                    flushMaxAgeMs = tracking.optLong("flushMaxAgeMs", BOOTSTRAP_FLUSH_MAX_AGE_MS),
                    batchMaxPoints = tracking.optInt("batchMaxPoints", BOOTSTRAP_BATCH_MAX_POINTS),
                    uploadMode = tracking.optString("uploadMode", "batch").ifBlank { "batch" },
                    workShift = parseWorkShift(tracking.optJSONObject("workShift")),
                ),
                diagnosis = DiagnosisConfig(
                    title = diag.optString("title", "Diagnosis Push Notification"),
                    subtitle = sanitizeDiagnosisSubtitle(diag.optString("subtitle", "")),
                    items = items,
                ),
                fakeGps = FakeGpsConfig(
                    enabled = fake.optBoolean("enabled", true),
                    blockOnMock = fake.optBoolean("blockOnMock", true),
                    blockOnJump = fake.optBoolean("blockOnJump", true),
                    jumpThresholdMeters = fake.optDouble("jumpThresholdMeters", 80.0),
                    jumpWindowMs = fake.optLong("jumpWindowMs", 4_000L),
                    blockOnAttendanceRoutes = fake.optBoolean("blockOnAttendanceRoutes", false),
                    title = fake.optString("title", "Fake GPS terdeteksi"),
                    subtitle = fake.optString("subtitle", ""),
                    body = fake.optString("body", ""),
                    warning = fake.optString("warning", ""),
                    steps = steps,
                    retryLabel = fake.optString("retryLabel", "Periksa ulang"),
                    homeLabel = fake.optString("homeLabel", "Kembali ke beranda"),
                ),
                tts = TtsConfig(
                    voiceGender = tts.optString("voiceGender", BOOTSTRAP_TTS_VOICE_GENDER),
                ),
            )
        }

        fun defaultConfig(): NativeAppConfig = fromJson(
            JSONObject().apply {
                put("version", 4)
                put(
                    "locationNotification",
                    JSONObject().apply {
                        put("title", "Smile Multifab")
                        put("readyText", "PT Multi Fabrindo Gemilang")
                        put("incompleteText", "Persiapan Ruang Lingkup Kerja")
                        put("shiftEndedText", LocationNotificationConfig.DEFAULT_SHIFT_ENDED_TEXT)
                    },
                )
                put(
                    "locationTracking",
                    JSONObject().apply {
                        // Bootstrap offline saja — kebijakan interval di BE.
                        put("intervalMs", BOOTSTRAP_INTERVAL_MS)
                        put("minIntervalMs", BOOTSTRAP_MIN_INTERVAL_MS)
                        put("queueFlushSize", BOOTSTRAP_QUEUE_FLUSH_SIZE)
                        put("maxLocalQueue", BOOTSTRAP_MAX_LOCAL_QUEUE)
                        put("flushMaxAgeMs", BOOTSTRAP_FLUSH_MAX_AGE_MS)
                        put("batchMaxPoints", BOOTSTRAP_BATCH_MAX_POINTS)
                        put("uploadMode", "batch")
                        put(
                            "workShift",
                            JSONObject().apply {
                                put("timeIn", "08:00")
                                put("timeOut", "17:00")
                                put("timezone", "Asia/Jakarta")
                            },
                        )
                    },
                )
                put(
                    "diagnosis",
                    JSONObject().apply {
                        put("title", "Diagnosis Push Notification")
                        put("subtitle", "Cek izin, jaringan, dan kesiapan push sebelum absensi.")
                        put("items", JSONArray())
                    },
                )
                put(
                    "fakeGps",
                    JSONObject().apply {
                        put("enabled", true)
                        put("blockOnMock", true)
                        put("blockOnJump", true)
                        put("jumpThresholdMeters", 80)
                        put("jumpWindowMs", 4000)
                        put("blockOnAttendanceRoutes", false)
                        put("title", "Fake GPS terdeteksi")
                    },
                )
                put(
                    "tts",
                    JSONObject().apply {
                        put("voiceGender", BOOTSTRAP_TTS_VOICE_GENDER)
                    },
                )
            },
        )

        private fun parseWorkShift(raw: JSONObject?): WorkShiftConfig {
            val o = raw ?: JSONObject()
            return WorkShiftConfig(
                timeIn = o.optString("timeIn", o.optString("time_in", "08:00")).ifBlank { "08:00" },
                timeOut = o.optString("timeOut", o.optString("time_out", "17:00")).ifBlank { "17:00" },
                timezone = o.optString("timezone", "Asia/Jakarta").ifBlank { "Asia/Jakarta" },
                source = o.optString("source", "normal").ifBlank { "normal" },
                label = o.optString("label", "Normal").ifBlank { "Normal" },
            )
        }

        private fun sanitizeDiagnosisSubtitle(raw: String): String {
            val trimmed = raw.trim()
            if (trimmed.isBlank()) {
                return "Cek izin, jaringan, dan kesiapan push."
            }
            return trimmed
                .replace(" Lokasi wajib Selalu izinkan agar tracking shift berjalan saat app di latar.", "")
                .replace(" Lokasi wajib Selalu izinkan agar App bisa berjalan.", "")
                .replace("Lokasi wajib Selalu izinkan agar tracking shift berjalan saat app di latar.", "")
                .replace("Lokasi wajib Selalu izinkan agar App bisa berjalan.", "")
                .replace("Cek izin (lokasi & file selalu izinkan), jaringan, dan kesiapan push.", "Cek izin, jaringan, dan kesiapan push.")
                .trim()
        }

        private fun sanitizeDiagnosisItems(items: MutableList<DiagnosisItemConfig>) {
            val mapped = items.map { item ->
                when {
                    item.check == "ACCESS_BACKGROUND_LOCATION" || item.id == "location_always" ->
                        DiagnosisItemConfig(
                            id = "location_permission",
                            title = "Izin lokasi",
                            description = "Izinkan saat aplikasi digunakan. Titik disimpan lokal, lalu dikirim sesuai antrian native config.",
                            check = "ACCESS_FINE_LOCATION",
                            action = "request_location",
                        )
                    item.check == "ACCESS_FINE_LOCATION" || item.id == "location_permission" ->
                        DiagnosisItemConfig(
                            id = "location_permission",
                            title = item.title.ifBlank { "Izin lokasi" },
                            description = "Izinkan saat aplikasi digunakan. Titik disimpan lokal, lalu dikirim sesuai antrian native config.",
                            check = "ACCESS_FINE_LOCATION",
                            action = "request_location",
                        )
                    item.check == "READ_MEDIA_IMAGES" ||
                        item.check == "READ_MEDIA" ||
                        item.check == "READ_EXTERNAL_STORAGE" ||
                        item.id == "media_permission" ->
                        DiagnosisItemConfig(
                            id = "media_permission",
                            title = item.title.ifBlank { "Izin foto & media" },
                            description = item.description.ifBlank {
                                "Wajib izinkan semua foto/media (bukan hanya yang dipilih) untuk upload lampiran."
                            }.let { desc ->
                                if (desc.contains("semua", ignoreCase = true)) {
                                    desc
                                } else {
                                    "Wajib izinkan semua foto/media (bukan hanya yang dipilih) untuk upload lampiran."
                                }
                            },
                            check = "READ_MEDIA_IMAGES",
                            action = "request_media",
                        )
                    else -> item
                }
            }
            items.clear()
            items.addAll(
                mapped.filterNot {
                    it.check in droppedDiagnosisChecks || it.id in droppedDiagnosisIds
                },
            )
            if (items.none { it.check == "ACCESS_FINE_LOCATION" || it.id == "location_permission" }) {
                val afterNotif = items.indexOfFirst {
                    it.check == "POST_NOTIFICATIONS" || it.id == "notification_permission"
                }
                val locationItem = DiagnosisItemConfig(
                    id = "location_permission",
                    title = "Izin lokasi",
                    description = "Izinkan saat aplikasi digunakan. Titik disimpan lokal, lalu dikirim sesuai antrian native config.",
                    check = "ACCESS_FINE_LOCATION",
                    action = "request_location",
                )
                if (afterNotif >= 0) items.add(afterNotif + 1, locationItem) else items.add(locationItem)
            }
            // Jangan inject media_permission — Play policy: upload lewat system picker.
        }
    }
}
