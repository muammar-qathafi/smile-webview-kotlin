/**
 * Status: AKTIF
 * Flow: Bentuk data FCM smile_notify (judul, teks TTS, gender suara, jam tayang, deep link).
 * Takeout: jangan.
 */
package co.id.multifab.smile.notify

/**
 * Kontrak FCM data-only `type=smile_notify` — teks untuk TextToSpeech native.
 */
data class SmileNotifyPayload(
    val dedupeId: String,
    val title: String,
    val body: String,
    val soundMode: String,
    val ttsText: String,
    /** Override gender suara untuk push ini: female|male|default, atau null = ikut native-config. */
    val ttsGender: String?,
    val showTray: Boolean,
    val atMs: Long,
    val channelId: String,
    val deepLink: String,
    val raw: Map<String, String>,
) {
    val wantsTts: Boolean
        get() = soundMode == "tts" && ttsText.isNotBlank()

    companion object {
        const val TYPE = "smile_notify"
        const val CHANNEL_TTS = "smile_notify_tts_v1"

        fun fromData(data: Map<String, String>): SmileNotifyPayload? {
            val type = data["type"] ?: data["action"] ?: return null
            if (type != TYPE) return null
            val title = data["title"].orEmpty().ifBlank { "Smile Multifab" }
            val body = data["body"].orEmpty()
            val dedupe = data["dedupe_id"].orEmpty().ifBlank {
                "auto-${title.hashCode()}-${body.hashCode()}-${System.currentTimeMillis() / 60_000L}"
            }
            val ttsText = (data["tts_text"] ?: data["tts"]).orEmpty().trim()
            val soundMode = data["sound_mode"].orEmpty().lowercase().ifBlank {
                if (ttsText.isNotBlank()) "tts" else "default"
            }
            val ttsGender = resolveGender(
                data["tts_gender"] ?: data["gender"] ?: data["voiceGender"],
            )
            val atMs = data["at_ms"]?.toLongOrNull() ?: 0L
            val showTray = data["show_tray"] != "0"
            return SmileNotifyPayload(
                dedupeId = dedupe,
                title = title,
                body = body,
                soundMode = soundMode,
                ttsText = ttsText,
                ttsGender = ttsGender,
                showTray = showTray,
                atMs = atMs,
                channelId = data["channel_id"].orEmpty().ifBlank {
                    if (soundMode == "tts") CHANNEL_TTS else co.id.multifab.smile.config.AppConfig.FCM_CHANNEL_ID
                },
                deepLink = data["deep_link"].orEmpty().ifBlank { data["route"].orEmpty() },
                raw = data,
            )
        }

        fun playId(dedupeId: String): Int {
            var h = dedupeId.hashCode()
            if (h == Int.MIN_VALUE) h = 0
            return (kotlin.math.abs(h) % 2_000_000_000) + 20_000
        }

        private fun resolveGender(raw: String?): String? {
            val v = raw?.trim()?.lowercase().orEmpty()
            if (v.isEmpty()) return null
            return when (v) {
                "female", "f", "wanita", "perempuan" -> "female"
                "male", "m", "pria", "laki", "laki-laki" -> "male"
                "default", "auto", "engine" -> "default"
                else -> "default"
            }
        }
    }
}
