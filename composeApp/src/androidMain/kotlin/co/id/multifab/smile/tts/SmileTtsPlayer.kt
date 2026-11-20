/**
 * Status: AKTIF
 * Flow: Satu pintu pemutaran TTS. Mencegah pengingat yang sama berbunyi dua kali, lalu mendelegasikan ke service atau engine.
 * Takeout: jangan.
 */
package co.id.multifab.smile.tts

import co.id.multifab.smile.util.SmileLog

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import co.id.multifab.smile.SmileApplication
import org.json.JSONObject

/**
 * Satu pintu masuk pemutaran TTS via [android.speech.tts.TextToSpeech].
 *
 * Prefer foreground service; jika FGS ditolak (background FCM), fallback speakBlocking.
 */
object SmileTtsPlayer {
    private const val TAG = "SmileTtsPlayer"
    private const val PREFS = "smile_tts_played"
    private const val KEY_ENTRIES = "entries_json"
    private const val RETAIN_MS = 12 * 60 * 60_000L
    /** Cegah double-fire FCM saja; jangan blok tes ulang teks yang sama. */
    private const val TEXT_BLOCK_MS = 2_500L

    fun playOnce(
        context: Context,
        reminderId: Int,
        ttsText: String?,
        @Suppress("UNUSED_PARAMETER") soundUri: String? = null,
        @Suppress("UNUSED_PARAMETER") soundPath: String? = null,
        voiceGender: String? = null,
    ): Boolean {
        val text = ttsText?.trim().orEmpty()
        if (text.isEmpty()) {
            SmileLog.w(TAG, "skip playOnce id=$reminderId (empty text)")
            return false
        }
        val app = context.applicationContext
        // Dedupe by reminderId hanya untuk window sangat pendek (double delivery FCM).
        if (reminderId != 0 && playedVeryRecently(reminderId)) {
            SmileLog.i(TAG, "skip playOnce id=$reminderId (duplicate burst)")
            return false
        }
        if (recentTextPlayed(text)) {
            SmileLog.i(TAG, "skip playOnce id=$reminderId (duplicate burst same text)")
            return false
        }

        val started = startPlayback(app, text, voiceGender)
        if (started) {
            markPlayed(reminderId, text)
        } else {
            SmileLog.w(TAG, "playOnce failed id=$reminderId (service + fallback)")
        }
        return started
    }

    fun playNow(context: Context, ttsText: String, force: Boolean = false): Boolean {
        val text = ttsText.trim()
        if (text.isEmpty()) return false
        val app = context.applicationContext
        if (!force) {
            if (recentTextPlayed(text) || hasAnyRecentPlay()) {
                SmileLog.i(TAG, "skip playNow (native already handled / late catch-up)")
                return false
            }
            SmileLog.i(TAG, "skip playNow (JS catch-up disabled)")
            return false
        }
        val started = startPlayback(app, text, null)
        if (started) markPlayed(0, text)
        return started
    }

    fun alreadyPlayed(reminderId: Int): Boolean = playedVeryRecently(reminderId)

    /** Burst window singkat — teks/ID sama boleh diputar lagi setelah ini. */
    private fun playedVeryRecently(reminderId: Int): Boolean {
        if (reminderId == 0) return false
        val ts = loadEntries()[reminderId.toString()] ?: return false
        return System.currentTimeMillis() - ts < TEXT_BLOCK_MS
    }

    private fun startPlayback(context: Context, text: String, voiceGender: String?): Boolean {
        // Langsung TextToSpeech + wake lock — andal dari FCM/receiver (FGS sering ditolak di bg).
        if (speakDirect(context, text, voiceGender)) return true

        // Cadangan FGS bila speak langsung gagal.
        return runCatching {
            val intent = Intent(context, SmileTtsPlaybackService::class.java).apply {
                action = SmileTtsPlaybackService.ACTION_PLAY
                putExtra(SmileTtsPlaybackService.EXTRA_TTS_TEXT, text.take(200))
                if (!voiceGender.isNullOrBlank()) {
                    putExtra(SmileTtsPlaybackService.EXTRA_VOICE_GENDER, voiceGender)
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            SmileLog.i(TAG, "FGS fallback started gender=$voiceGender")
            true
        }.onFailure {
            SmileLog.w(TAG, "FGS fallback failed: ${it.message}")
        }.getOrDefault(false)
    }

    private fun speakDirect(context: Context, text: String, voiceGender: String?): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        val wake = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "smile:tts_direct")
        return try {
            wake?.acquire(90_000L)
            val ok = SmileTtsEngine.speakBlocking(context, text, preferredGender = voiceGender)
            SmileLog.i(TAG, "speakDirect ok=$ok gender=$voiceGender")
            ok
        } catch (e: Exception) {
            SmileLog.w(TAG, "speakDirect failed: ${e.message}")
            false
        } finally {
            if (wake?.isHeld == true) runCatching { wake.release() }
        }
    }

    private fun recentTextPlayed(text: String): Boolean {
        val key = "txt:" + text.hashCode()
        val ts = loadEntries()[key] ?: return false
        return System.currentTimeMillis() - ts < TEXT_BLOCK_MS
    }

    private fun hasAnyRecentPlay(): Boolean {
        val now = System.currentTimeMillis()
        val last = loadEntries()["last"] ?: return false
        return now - last < TEXT_BLOCK_MS
    }

    private fun markPlayed(reminderId: Int, text: String) {
        val now = System.currentTimeMillis()
        val map = loadEntries().toMutableMap()
        if (reminderId != 0) map[reminderId.toString()] = now
        if (text.isNotBlank()) map["txt:${text.hashCode()}"] = now
        map["last"] = now
        val cutoff = now - RETAIN_MS
        map.entries.removeIf { it.value < cutoff }
        saveEntries(map)
    }

    private fun prefs() =
        SmileApplication.instance.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun loadEntries(): Map<String, Long> {
        val raw = prefs().getString(KEY_ENTRIES, "{}") ?: "{}"
        val obj = runCatching { JSONObject(raw) }.getOrDefault(JSONObject())
        val out = mutableMapOf<String, Long>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            out[k] = obj.optLong(k, 0L)
        }
        return out
    }

    private fun saveEntries(map: Map<String, Long>) {
        val obj = JSONObject()
        map.forEach { (k, v) -> obj.put(k, v) }
        prefs().edit().putString(KEY_ENTRIES, obj.toString()).apply()
    }
}
