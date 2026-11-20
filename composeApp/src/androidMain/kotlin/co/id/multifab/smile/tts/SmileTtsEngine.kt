/**
 * Status: AKTIF
 * Flow: TextToSpeech perangkat. Gender suara dari payload FCM, kalau kosong dari native-config.
 * Takeout: jangan.
 */
package co.id.multifab.smile.tts

import co.id.multifab.smile.util.SmileLog

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import co.id.multifab.smile.nativeconfig.NativeConfigStore
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * TextToSpeech native (engine default perangkat) — tanpa memaksa Google TTS.
 * Gender: FCM `tts_gender` → native-config `tts.voiceGender`.
 * Pitch/rate natural; jangan percepat suara.
 */
object SmileTtsEngine {
    private const val TAG = "SmileTtsEngine"
    private const val UTTERANCE_ID = "smile_tts_direct"
    private const val MAX_CHARS = 200

    /** Engine default OS saja (Samsung/OEM/dll) — tidak force Google. */
    fun createTts(context: Context, listener: TextToSpeech.OnInitListener): TextToSpeech {
        return TextToSpeech(context.applicationContext, listener).also {
            SmileLog.i(TAG, "TTS using default native engine")
        }
    }

    /**
     * Blokir thread pemanggil sampai selesai / timeout.
     * @return true jika speak() diterima engine
     */
    fun speakBlocking(
        context: Context,
        text: String,
        timeoutMs: Long = 60_000L,
        preferredGender: String? = null,
    ): Boolean {
        val trimmed = text.trim().take(MAX_CHARS)
        if (trimmed.isEmpty()) return false

        val app = context.applicationContext
        val ready = CountDownLatch(1)
        val done = CountDownLatch(1)
        val ok = AtomicBoolean(false)
        val main = Handler(Looper.getMainLooper())
        var engine: TextToSpeech? = null

        val initListener = TextToSpeech.OnInitListener { status ->
            if (status != TextToSpeech.SUCCESS) {
                SmileLog.w(TAG, "init failed status=$status")
                ready.countDown()
                done.countDown()
                return@OnInitListener
            }
            val tts = engine
            if (tts == null) {
                ready.countDown()
                done.countDown()
                return@OnInitListener
            }
            SmileLog.i(TAG, "TTS defaultEngine=${tts.defaultEngine}")
            if (!applyLanguage(tts)) {
                SmileLog.w(TAG, "no usable language")
                ready.countDown()
                done.countDown()
                return@OnInitListener
            }
            applyVoiceGender(tts, preferredGender)
            applyAlarmAudio(tts)
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    ok.set(true)
                }
                override fun onDone(utteranceId: String?) {
                    done.countDown()
                }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    done.countDown()
                }
                override fun onError(utteranceId: String?, errorCode: Int) {
                    SmileLog.w(TAG, "utterance error=$errorCode")
                    done.countDown()
                }
            })
            ready.countDown()
            val params = Bundle()
            val code = tts.speak(trimmed, TextToSpeech.QUEUE_FLUSH, params, UTTERANCE_ID)
            if (code != TextToSpeech.SUCCESS) {
                SmileLog.w(TAG, "speak failed code=$code")
                done.countDown()
            } else {
                ok.set(true)
            }
        }

        val created = CountDownLatch(1)
        main.post {
            runCatching {
                engine = createTts(app, initListener)
            }.onFailure {
                SmileLog.w(TAG, "create failed: ${it.message}")
                ready.countDown()
                done.countDown()
            }
            created.countDown()
        }
        created.await(5, TimeUnit.SECONDS)
        ready.await(15, TimeUnit.SECONDS)
        done.await(timeoutMs, TimeUnit.MILLISECONDS)

        main.post {
            runCatching { engine?.stop() }
            runCatching { engine?.shutdown() }
        }
        return ok.get()
    }

    fun applyLanguage(tts: TextToSpeech): Boolean {
        val candidates = listOf(
            Locale.forLanguageTag("id-ID"),
            Locale.forLanguageTag("id"),
            Locale.getDefault(),
            Locale.US,
        )
        for (locale in candidates) {
            val r = tts.setLanguage(locale)
            if (r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED) {
                SmileLog.i(TAG, "TTS language ok locale=$locale result=$r")
                return true
            }
        }
        return false
    }

    /**
     * Pilih voice native sesuai gender — natural, tanpa pitch palsu.
     *
     * Pitch tinggi pada voice pria terdengar tidak natural ("chipmunk"/falsetto).
     * Solusi yang logis: pilih voice yang memang female/male, biarkan pitch=1.0 & rate=1.0.
     * Jika device tidak punya voice gender itu, tetap natural (jangan dipaksa pitch).
     */
    fun applyVoiceGender(tts: TextToSpeech, preferredGender: String? = null) {
        val gender = resolveGender(
            preferredGender ?: NativeConfigStore.get().tts.resolvedVoiceGender(),
        )
        runCatching { tts.setPitch(1.0f) }
        runCatching { tts.setSpeechRate(1.0f) }

        val voices = runCatching { tts.voices }.getOrNull().orEmpty()
        logAvailableVoices(voices)
        SmileLog.i(TAG, "TTS applyVoiceGender want=$gender preferredArg=$preferredGender")

        if (gender == "default") {
            SmileLog.i(TAG, "TTS voiceGender=default — biarkan engine")
            return
        }

        val pick = pickVoiceForGender(voices, gender)
        if (pick != null) {
            val result = runCatching { tts.setVoice(pick) }.getOrDefault(TextToSpeech.ERROR)
            if (result == TextToSpeech.SUCCESS) {
                // Voice asli gendered → natural murni.
                runCatching { tts.setPitch(1.0f) }
                runCatching { tts.setSpeechRate(1.0f) }
                SmileLog.i(
                    TAG,
                    "TTS natural voice name=${pick.name} inferred=${inferGender(pick)} " +
                        "want=$gender pitch=1.0 rate=1.0",
                )
                return
            }
            SmileLog.w(TAG, "TTS setVoice failed code=$result name=${pick.name}")
        } else {
            SmileLog.w(
                TAG,
                "no native $gender voice on device — keep natural default " +
                    "(tidak memalsukan pitch agar tidak terdengar aneh)",
            )
        }
        // Jangan pitch-shift: lebih baik natural daripada falsetto.
        // Hindari hanya voice lawan gender bila ada alternatif id.
        pickNeutralIdVoice(tts, voices, gender)
        runCatching { tts.setPitch(1.0f) }
        runCatching { tts.setSpeechRate(1.0f) }
    }

    fun applyAlarmAudio(tts: TextToSpeech) {
        tts.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build(),
        )
    }

    fun logAvailableVoices(voices: Set<Voice>) {
        val lines = voices
            .sortedWith(compareBy({ it.locale.toLanguageTag() }, { it.name }))
            .map { v ->
                "name=${v.name} locale=${v.locale} gender=${inferGender(v)} " +
                    "quality=${v.quality} network=${v.isNetworkConnectionRequired}"
            }
        SmileLog.i(TAG, "TTS available voices count=${lines.size}")
        lines.forEach { SmileLog.i(TAG, "TTS voice: $it") }
    }

    fun inferGender(voice: Voice): String {
        val name = voice.name.lowercase()
        val features = voice.features.map { it.lowercase() }

        when {
            name.contains("-idf-") || name.contains("_idf_") || name.contains("#idf") ->
                return "female"
            name.contains("-idm-") || name.contains("_idm_") || name.contains("#idm") ->
                return "male"
        }

        if (features.any { it == "female" || it == "woman" } ||
            nameContainsWord(name, "female") ||
            nameContainsWord(name, "woman") ||
            nameContainsWord(name, "wanita") ||
            nameContainsWord(name, "perempuan") ||
            name.contains("#female")
        ) {
            return "female"
        }

        if (features.any { it == "male" || it == "man" } ||
            nameContainsWord(name, "male") ||
            nameContainsWord(name, "man") ||
            nameContainsWord(name, "pria") ||
            name.contains("#male")
        ) {
            return "male"
        }

        return "unknown"
    }

    private fun pickVoiceForGender(voices: Set<Voice>, gender: String): Voice? {
        fun score(voice: Voice, allowNetwork: Boolean): Int? {
            if (!allowNetwork && voice.isNetworkConnectionRequired) return null
            if (inferGender(voice) != gender) return null
            if (!voice.locale.language.equals("id", ignoreCase = true)) return null
            val localeScore =
                if (voice.locale.country.equals("ID", ignoreCase = true)) 40 else 30
            val qualityScore = when (voice.quality) {
                Voice.QUALITY_VERY_HIGH -> 3
                Voice.QUALITY_HIGH -> 2
                Voice.QUALITY_NORMAL -> 1
                else -> 0
            }
            val localBonus = if (!voice.isNetworkConnectionRequired) 5 else 0
            return localeScore + qualityScore + localBonus
        }

        voices.mapNotNull { v -> score(v, allowNetwork = false)?.let { v to it } }
            .maxByOrNull { it.second }
            ?.first
            ?.also { SmileLog.i(TAG, "TTS pick local-id name=${it.name}") }
            ?.let { return it }

        return voices.mapNotNull { v -> score(v, allowNetwork = true)?.let { v to it } }
            .maxByOrNull { it.second }
            ?.first
            ?.also { SmileLog.i(TAG, "TTS pick network-id name=${it.name}") }
    }

    private fun pickNeutralIdVoice(tts: TextToSpeech, voices: Set<Voice>, gender: String) {
        val opposite = when (gender) {
            "male" -> "female"
            "female" -> "male"
            else -> ""
        }
        val base = voices
            .asSequence()
            .filter { !it.isNetworkConnectionRequired }
            .filter { it.locale.language.equals("id", ignoreCase = true) }
            .filter { opposite.isEmpty() || inferGender(it) != opposite }
            .sortedByDescending { it.quality }
            .firstOrNull()
        if (base != null) {
            runCatching { tts.setVoice(base) }
            SmileLog.i(TAG, "TTS baseVoice=${base.name} inferred=${inferGender(base)}")
        }
    }

    private fun resolveGender(raw: String): String = when (raw.trim().lowercase()) {
        "female", "f", "wanita", "perempuan" -> "female"
        "male", "m", "pria", "laki", "laki-laki" -> "male"
        else -> "default"
    }

    private fun nameContainsWord(haystack: String, word: String): Boolean {
        if (word.isEmpty()) return false
        var from = 0
        while (true) {
            val i = haystack.indexOf(word, from)
            if (i < 0) return false
            val beforeOk = i == 0 || !haystack[i - 1].isLetter()
            val after = i + word.length
            val afterOk = after >= haystack.length || !haystack[after].isLetter()
            if (beforeOk && afterOk) return true
            from = i + 1
        }
    }
}
