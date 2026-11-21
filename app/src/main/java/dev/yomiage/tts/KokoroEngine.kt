package dev.yomiage.tts

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import dev.yomiage.data.Accent
import dev.yomiage.data.Prefs
import dev.yomiage.data.Voice
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/**
 * Kokoro-82M running on the phone through sherpa-onnx.
 * The model takes a few seconds to load and ~400 MB of memory, so it is loaded on first use
 * and released again after a few idle minutes.
 */
object KokoroEngine {
    private const val TAG = "KokoroEngine"
    const val SAMPLE_RATE = 24000
    private val IDLE_UNLOAD_MS = TimeUnit.MINUTES.toMillis(5)

    private val lock = ReentrantLock()
    private var tts: OfflineTts? = null
    private var loadedThreads = 0
    private var loadedZh = false
    @Volatile private var lastUse = 0L
    @Volatile var isLoaded = false
        private set

    private val main = Handler(Looper.getMainLooper())
    private val unloadTask = Runnable { unloadIfIdle() }

    /** Returns audio samples in [-1, 1] at 24 kHz, or null if the voice pack isn't installed. */
    fun synth(context: Context, text: String, voice: Voice, speed: Float = 1f): FloatArray? {
        lock.lock()
        try {
            val engine = ensure(context, voice) ?: return null
            touch()
            val lang = voice.lang
            val audio = if (lang == null) {
                engine.generate(text, voice.id, speed)
            } else {
                engine.generateWithConfig(
                    text,
                    GenerationConfig(sid = voice.id, speed = speed, extra = mapOf("lang" to lang)),
                )
            }
            touch()
            return audio.samples
        } finally {
            lock.unlock()
        }
    }

    /** Loads the model in the background so the first sentence starts faster. */
    fun preload(context: Context, voice: Voice) {
        if (isLoaded) {
            touch()
            return
        }
        Thread({
            lock.lock()
            try {
                ensure(context, voice)
                touch()
            } catch (e: Throwable) {
                Log.w(TAG, "preload failed", e)
            } finally {
                lock.unlock()
            }
        }, "kokoro-preload").start()
    }

    fun numSpeakers(): Int {
        if (!lock.tryLock()) return 0
        try {
            return tts?.numSpeakers() ?: 0
        } finally {
            lock.unlock()
        }
    }

    fun release() {
        lock.lock()
        try {
            tts?.release()
            tts = null
            isLoaded = false
        } finally {
            lock.unlock()
        }
    }

    private fun ensure(context: Context, voice: Voice): OfflineTts? {
        val threads = Prefs.current.threads.coerceIn(1, 8)
        val needZh = voice.accent == Accent.ZH
        val current = tts
        // An engine loaded with the Chinese lexicon works for every voice.
        if (current != null && loadedThreads == threads && (loadedZh || !needZh)) return current
        current?.release()
        tts = null
        isLoaded = false
        if (!ModelManager.isReady(context)) return null

        val dir = ModelManager.dir(context).absolutePath
        val started = SystemClock.elapsedRealtime()
        val config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                kokoro = OfflineTtsKokoroModelConfig(
                    model = "$dir/model.onnx",
                    voices = "$dir/voices.bin",
                    tokens = "$dir/tokens.txt",
                    dataDir = "$dir/espeak-ng-data",
                    lexicon = if (needZh) "$dir/lexicon-zh.txt" else "",
                    lang = "en-us",
                ),
                numThreads = threads,
                debug = false,
                provider = "cpu",
            ),
            maxNumSentences = 1,
            silenceScale = 0.2f,
        )
        val engine = OfflineTts(assetManager = null, config = config)
        Log.i(TAG, "Kokoro loaded in ${SystemClock.elapsedRealtime() - started} ms, speakers=${engine.numSpeakers()}")
        tts = engine
        loadedThreads = threads
        loadedZh = needZh
        isLoaded = true
        return engine
    }

    private fun touch() {
        lastUse = SystemClock.elapsedRealtime()
        main.removeCallbacks(unloadTask)
        main.postDelayed(unloadTask, IDLE_UNLOAD_MS)
    }

    private fun unloadIfIdle() {
        if (SystemClock.elapsedRealtime() - lastUse < IDLE_UNLOAD_MS) {
            main.postDelayed(unloadTask, IDLE_UNLOAD_MS)
            return
        }
        if (dev.yomiage.reader.Reader.isBusy) {
            main.postDelayed(unloadTask, IDLE_UNLOAD_MS)
            return
        }
        // Never block the main thread waiting for a sentence to finish.
        if (!lock.tryLock()) {
            main.postDelayed(unloadTask, 60_000)
            return
        }
        try {
            tts?.release()
            tts = null
            isLoaded = false
            Log.i(TAG, "Kokoro unloaded after being idle")
        } finally {
            lock.unlock()
        }
    }
}
