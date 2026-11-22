package dev.yomiage.tts

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.k2fsa.sherpa.onnx.GenerationConfig
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsSupertonicModelConfig
import dev.yomiage.data.Prefs
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/**
 * Supertonic 3 on the phone through sherpa-onnx: 10 voices (F1–F5, M1–M5), quick and natural.
 * Loaded on first use, released after a few idle minutes, like Kokoro.
 */
object SupertonicEngine {
    private const val TAG = "SupertonicEngine"
    private val IDLE_UNLOAD_MS = TimeUnit.MINUTES.toMillis(5)

    private val lock = ReentrantLock()
    private var tts: OfflineTts? = null
    @Volatile private var lastUse = 0L
    private val main = Handler(Looper.getMainLooper())
    private val unloadTask = Runnable { unloadIfIdle() }

    /** Samples in [-1, 1] and their sample rate, or null if the pack isn't installed. */
    fun synth(context: Context, text: String, sid: Int, speed: Float = 1f): Pair<FloatArray, Int>? {
        lock.lock()
        try {
            val engine = ensure(context) ?: return null
            touch()
            val audio = engine.generateWithConfig(
                text,
                GenerationConfig(sid = sid, speed = speed, numSteps = 8, extra = mapOf("lang" to "en")),
            )
            touch()
            return audio.samples to audio.sampleRate
        } finally {
            lock.unlock()
        }
    }

    fun release() {
        lock.lock()
        try {
            tts?.release()
            tts = null
        } finally {
            lock.unlock()
        }
    }

    private fun ensure(context: Context): OfflineTts? {
        tts?.let { return it }
        if (!SupertonicPack.isReady(context)) return null
        val dir = SupertonicPack.dir(context).absolutePath
        val started = SystemClock.elapsedRealtime()
        val config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                supertonic = OfflineTtsSupertonicModelConfig(
                    durationPredictor = "$dir/duration_predictor.int8.onnx",
                    textEncoder = "$dir/text_encoder.int8.onnx",
                    vectorEstimator = "$dir/vector_estimator.int8.onnx",
                    vocoder = "$dir/vocoder.int8.onnx",
                    ttsJson = "$dir/tts.json",
                    unicodeIndexer = "$dir/unicode_indexer.bin",
                    voiceStyle = "$dir/voice.bin",
                ),
                numThreads = Prefs.current.threads.coerceIn(1, 8),
                debug = false,
                provider = "cpu",
            ),
            maxNumSentences = 1,
            silenceScale = 0.2f,
        )
        val engine = OfflineTts(assetManager = null, config = config)
        Log.i(TAG, "Supertonic loaded in ${SystemClock.elapsedRealtime() - started} ms")
        tts = engine
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
        if (lock.tryLock()) {
            try {
                tts?.release()
                tts = null
            } finally {
                lock.unlock()
            }
        }
    }
}
