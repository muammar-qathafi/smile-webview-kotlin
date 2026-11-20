/**
 * Status: AKTIF
 * Flow: Foreground service agar TTS tetap berbunyi saat layar mati atau app di background.
 * Takeout: jangan.
 */
package co.id.multifab.smile.tts

import co.id.multifab.smile.util.SmileLog

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.NotificationCompat
import java.util.concurrent.atomic.AtomicBoolean

/** Foreground service — TextToSpeech saat layar mati / app di background. */
class SmileTtsPlaybackService : Service(), TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var pendingText: String = ""
    private var pendingGender: String? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val finished = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val text = intent?.getStringExtra(EXTRA_TTS_TEXT)?.trim().orEmpty()
        if (text.isEmpty()) {
            stopSelf()
            return START_NOT_STICKY
        }

        // Reset state jika service di-reuse.
        finished.set(false)
        pendingText = text.take(MAX_CHARS)
        pendingGender = intent?.getStringExtra(EXTRA_VOICE_GENDER)?.trim()?.takeIf { it.isNotEmpty() }
        runCatching { tts?.stop() }
        runCatching { tts?.shutdown() }
        tts = null

        ensureChannel()
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Pengingat Smile")
            .setContentText("Memutar pengingat suara…")
            .also { co.id.multifab.smile.notify.NotificationIcons.applyForegroundService(it, this) }
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

        try {
            startForeground(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            SmileLog.w(TAG, "startForeground failed: ${e.message} — direct fallback")
            // FGS gagal: speak langsung lalu stop.
            val gender = pendingGender
            Thread {
                val ok = SmileTtsEngine.speakBlocking(
                    applicationContext,
                    pendingText,
                    preferredGender = gender,
                )
                SmileLog.i(TAG, "direct fallback after FGS fail ok=$ok")
                stopSelf()
            }.start()
            return START_NOT_STICKY
        }

        wakeLock = (getSystemService(POWER_SERVICE) as? PowerManager)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "smile:tts_service")
            ?.also { it.acquire(90_000L) }

        mainHandler.postDelayed({ finishPlayback("timeout") }, 75_000L)

        mainHandler.post {
            runCatching {
                tts = SmileTtsEngine.createTts(applicationContext, this)
            }.onFailure {
                SmileLog.w(TAG, "TextToSpeech create failed: ${it.message}")
                finishPlayback("create_failed")
            }
        }

        return START_NOT_STICKY
    }

    override fun onInit(status: Int) {
        mainHandler.post {
            if (finished.get()) return@post
            if (status != TextToSpeech.SUCCESS) {
                SmileLog.w(TAG, "TextToSpeech init failed status=$status")
                finishPlayback("init_failed")
                return@post
            }
            val engine = tts
            if (engine == null) {
                finishPlayback("engine_null")
                return@post
            }
            if (!SmileTtsEngine.applyLanguage(engine)) {
                SmileLog.w(TAG, "no usable TTS language")
                finishPlayback("lang_unavailable")
                return@post
            }
            SmileTtsEngine.applyVoiceGender(engine, pendingGender)
            SmileTtsEngine.applyAlarmAudio(engine)

            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) {
                    mainHandler.post { finishPlayback("done") }
                }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    mainHandler.post { finishPlayback("error") }
                }
                override fun onError(utteranceId: String?, errorCode: Int) {
                    SmileLog.w(TAG, "utterance error code=$errorCode")
                    mainHandler.post { finishPlayback("error_$errorCode") }
                }
            })

            val uttered = engine.speak(pendingText, TextToSpeech.QUEUE_FLUSH, Bundle(), UTTERANCE_ID)
            if (uttered != TextToSpeech.SUCCESS) {
                SmileLog.w(TAG, "speak() failed code=$uttered")
                finishPlayback("speak_failed")
            } else {
                SmileLog.i(TAG, "speak started len=${pendingText.length}")
            }
        }
    }

    private fun finishPlayback(reason: String) {
        if (!finished.compareAndSet(false, true)) return
        SmileLog.i(TAG, "finishPlayback reason=$reason")
        mainHandler.removeCallbacksAndMessages(null)
        runCatching { tts?.stop() }
        runCatching { tts?.shutdown() }
        tts = null
        releaseWake()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    private fun releaseWake() {
        val lock = wakeLock
        wakeLock = null
        if (lock?.isHeld == true) {
            runCatching { lock.release() }
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Pemutaran TTS Smile",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            setSound(null, null)
        }
        manager.createNotificationChannel(channel)
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        runCatching { tts?.shutdown() }
        tts = null
        releaseWake()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "SmileTtsPlaybackService"
        private const val CHANNEL_ID = "smile_tts_playback"
        private const val NOTIFICATION_ID = 8_801_001
        private const val UTTERANCE_ID = "smile_tts_utterance"
        private const val MAX_CHARS = 200

        const val ACTION_PLAY = "co.id.multifab.action.PLAY_TTS"
        const val EXTRA_TTS_TEXT = "smile_tts_text"
        const val EXTRA_VOICE_GENDER = "smile_tts_voice_gender"

        @Deprecated("MP3 path removed")
        const val EXTRA_SOUND_URI = "smile_tts_sound_uri"
    }
}
