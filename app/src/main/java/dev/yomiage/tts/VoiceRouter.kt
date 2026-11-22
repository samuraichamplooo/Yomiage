package dev.yomiage.tts

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import dev.yomiage.data.Voice
import dev.yomiage.data.VoiceEngine
import dev.yomiage.data.Voices
import java.util.concurrent.ConcurrentHashMap

/**
 * Makes speech with whichever engine the voice belongs to. If an online voice can't be used (no
 * key, no connection, a usage limit reached), reading carries on with Kokoro's Heart, says why
 * once, and tries the online voice again a little later.
 */
object VoiceRouter {
    private const val SR = 24000
    private val pausedUntil = ConcurrentHashMap<VoiceEngine, Long>()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var lastNotice = ""
    @Volatile private var lastNoticeAt = 0L

    /** Samples in [-1, 1] at 24 kHz, or null if no voice pack is installed to read with. */
    fun synth(context: Context, text: String, voice: Voice, speed: Float): FloatArray? {
        return when (voice.engine) {
            VoiceEngine.KOKORO -> KokoroEngine.synth(context, text, voice, speed)
            VoiceEngine.SUPERTONIC -> {
                val r = SupertonicEngine.synth(context, text, voice.remote.toIntOrNull() ?: 0, speed)
                if (r == null) {
                    tell(context, "Download the Supertonic voices first (Voice settings). Reading with Heart for now.")
                    fallback(context, text, speed)
                } else {
                    Resample.to(r.first, r.second, SR)
                }
            }
            VoiceEngine.GEMINI, VoiceEngine.ORPHEUS -> {
                val now = SystemClock.elapsedRealtime()
                if (now < (pausedUntil[voice.engine] ?: 0L)) return fallback(context, text, speed)
                try {
                    if (voice.engine == VoiceEngine.GEMINI) CloudVoices.gemini(text, voice) else CloudVoices.orpheus(text, voice)
                } catch (e: VoiceUnavailable) {
                    pausedUntil[voice.engine] = now + if (e.limit) 10 * 60_000L else 90_000L
                    tell(
                        context,
                        "${voice.engine.label} voice: ${e.message} Reading with Heart for now." +
                            if (e.limit) " To keep going, set up billing in Yomiage's Voice settings." else "",
                    )
                    fallback(context, text, speed)
                }
            }
        }
    }

    /** Lets an online voice be tried again right away (a new key, or picking the voice again). */
    fun retry(engine: VoiceEngine) {
        pausedUntil.remove(engine)
    }

    private fun fallback(context: Context, text: String, speed: Float): FloatArray? =
        KokoroEngine.synth(context, text, Voices.fallback, speed)

    private fun tell(context: Context, message: String) {
        val now = SystemClock.elapsedRealtime()
        if (message == lastNotice && now - lastNoticeAt < 5 * 60_000L) return
        lastNotice = message
        lastNoticeAt = now
        val app = context.applicationContext
        main.post { Toast.makeText(app, message, Toast.LENGTH_LONG).show() }
    }
}
