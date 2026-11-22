package dev.yomiage.tts

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import dev.yomiage.data.Prefs
import dev.yomiage.data.Voice
import dev.yomiage.data.VoiceEngine
import dev.yomiage.data.Voices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Why an online voice isn't reading. [setup] problems (a missing or refused key, Groq's one-time
 * terms) only you can fix, so they're shown in Voice settings rather than popping up each time.
 */
data class VoiceIssue(
    val message: String,
    val setup: Boolean,
    val limit: Boolean,
    val link: String?,
    /** When it happened (wall clock), and a fingerprint of the key it happened with. */
    val at: Long,
    val keyPrint: Int,
)

/**
 * Makes speech with whichever engine the voice belongs to. If an online voice can't be used, reading
 * carries on with Kokoro's Heart. Setup problems are remembered quietly and listed in Voice
 * settings (no pop-up); a usage limit or lost connection is mentioned at most once an hour.
 */
object VoiceRouter {
    private const val SR = 24000
    /** A setup problem is quietly checked again after this long, in case it was fixed elsewhere. */
    private const val RECHECK_SETUP_MS = 6 * 60 * 60_000L
    private const val NOTICE_EVERY_MS = 60 * 60_000L
    private val pausedUntil = ConcurrentHashMap<VoiceEngine, Long>()
    private val lastNoticeAt = ConcurrentHashMap<VoiceEngine, Long>()
    private val main = Handler(Looper.getMainLooper())

    private val _issues = MutableStateFlow<Map<VoiceEngine, VoiceIssue>>(emptyMap())
    val issues: StateFlow<Map<VoiceEngine, VoiceIssue>> = _issues

    @Volatile private var store: SharedPreferences? = null
    @Volatile private var lastSupertonicNotice = 0L

    /** Loads the remembered setup problems (cheap after the first time). */
    fun load(context: Context) {
        if (store != null) return
        synchronized(this) {
            if (store != null) return
            val sp = context.applicationContext.getSharedPreferences("voice_issues", Context.MODE_PRIVATE)
            val map = HashMap<VoiceEngine, VoiceIssue>()
            for (engine in VoiceEngine.entries) {
                val raw = sp.getString(engine.name, null) ?: continue
                try {
                    val o = JSONObject(raw)
                    map[engine] = VoiceIssue(
                        message = o.getString("message"),
                        setup = true,
                        limit = false,
                        link = o.optString("link").ifBlank { null },
                        at = o.getLong("at"),
                        keyPrint = o.getInt("key"),
                    )
                } catch (e: Exception) {
                    // Unreadable: forget it.
                }
            }
            _issues.value = map
            store = sp
        }
    }

    /** Samples in [-1, 1] at 24 kHz, or null if no voice pack is installed to read with. */
    fun synth(context: Context, text: String, voice: Voice, speed: Float): FloatArray? {
        return when (voice.engine) {
            VoiceEngine.KOKORO -> KokoroEngine.synth(context, text, voice, speed)
            VoiceEngine.SUPERTONIC -> {
                val r = SupertonicEngine.synth(context, text, voice.remote.toIntOrNull() ?: 0, speed)
                if (r == null) {
                    val now = SystemClock.elapsedRealtime()
                    if (lastSupertonicNotice == 0L || now - lastSupertonicNotice > NOTICE_EVERY_MS) {
                        lastSupertonicNotice = now
                        toast(context, "Download the Supertonic voices first (Voice settings). Reading with Heart for now.")
                    }
                    fallback(context, text, speed)
                } else {
                    Resample.to(r.first, r.second, SR)
                }
            }
            VoiceEngine.GEMINI, VoiceEngine.ORPHEUS -> {
                load(context)
                val engine = voice.engine
                val known = currentIssue(engine)
                if (known != null && known.setup && System.currentTimeMillis() - known.at < RECHECK_SETUP_MS) {
                    return fallback(context, text, speed)
                }
                if (SystemClock.elapsedRealtime() < (pausedUntil[engine] ?: 0L)) return fallback(context, text, speed)
                try {
                    val out = remote(text, voice)
                    clear(engine)
                    out
                } catch (e: VoiceUnavailable) {
                    record(engine, e)
                    if (!e.setup) {
                        pausedUntil[engine] = SystemClock.elapsedRealtime() + if (e.limit) 10 * 60_000L else 90_000L
                        val now = SystemClock.elapsedRealtime()
                        val last = lastNoticeAt[engine]
                        if (last == null || now - last > NOTICE_EVERY_MS) {
                            lastNoticeAt[engine] = now
                            toast(
                                context,
                                "${engine.label} voice: ${e.message} Reading with Heart for now." +
                                    if (e.limit) " Billing options are in Yomiage's Voice settings." else "",
                            )
                        }
                    }
                    fallback(context, text, speed)
                }
            }
        }
    }

    /** The problem for [engine], unless the key has changed since (then it's worth trying again). */
    fun currentIssue(engine: VoiceEngine): VoiceIssue? {
        val issue = _issues.value[engine] ?: return null
        return if (issue.setup && issue.keyPrint != keyPrint(engine)) null else issue
    }

    /** Lets an online voice be tried again right away (a new key, or picking the voice again). */
    fun retry(engine: VoiceEngine) {
        pausedUntil.remove(engine)
        lastNoticeAt.remove(engine)
        clear(engine)
    }

    /** "Try again" in Voice settings: one short test. Returns null if the voice works now. */
    suspend fun check(context: Context, voice: Voice): VoiceIssue? = withContext(Dispatchers.IO) {
        load(context)
        retry(voice.engine)
        try {
            remote("Ready.", voice)
            null
        } catch (e: VoiceUnavailable) {
            record(voice.engine, e)
            if (!e.setup) pausedUntil[voice.engine] = SystemClock.elapsedRealtime() + 90_000L
            currentIssue(voice.engine)
        }
    }

    private fun remote(text: String, voice: Voice): FloatArray =
        if (voice.engine == VoiceEngine.GEMINI) CloudVoices.gemini(text, voice) else CloudVoices.orpheus(text, voice)

    private fun keyPrint(engine: VoiceEngine): Int = when (engine) {
        VoiceEngine.GEMINI -> Prefs.current.geminiKey.trim().hashCode()
        VoiceEngine.ORPHEUS -> Prefs.current.groqKey.trim().hashCode()
        else -> 0
    }

    private fun record(engine: VoiceEngine, e: VoiceUnavailable) {
        val issue = VoiceIssue(
            message = e.message ?: "Not available right now.",
            setup = e.setup,
            limit = e.limit,
            link = e.link,
            at = System.currentTimeMillis(),
            keyPrint = keyPrint(engine),
        )
        _issues.update { it + (engine to issue) }
        val sp = store ?: return
        if (issue.setup) {
            val o = JSONObject().put("message", issue.message).put("at", issue.at).put("key", issue.keyPrint)
            issue.link?.let { o.put("link", it) }
            sp.edit().putString(engine.name, o.toString()).apply()
        } else {
            sp.edit().remove(engine.name).apply()
        }
    }

    private fun clear(engine: VoiceEngine) {
        if (_issues.value.containsKey(engine)) _issues.update { it - engine }
        store?.let { sp -> if (sp.contains(engine.name)) sp.edit().remove(engine.name).apply() }
    }

    private fun fallback(context: Context, text: String, speed: Float): FloatArray? =
        KokoroEngine.synth(context, text, Voices.fallback, speed)

    private fun toast(context: Context, message: String) {
        val app = context.applicationContext
        main.post { Toast.makeText(app, message, Toast.LENGTH_LONG).show() }
    }
}
