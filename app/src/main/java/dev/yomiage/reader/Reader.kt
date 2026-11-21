package dev.yomiage.reader

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Rect
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.PlaybackParams
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import dev.yomiage.data.Prefs
import dev.yomiage.data.Voice
import dev.yomiage.data.Voices
import dev.yomiage.tts.KokoroEngine
import dev.yomiage.tts.ModelManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.Condition
import java.util.concurrent.locks.ReentrantLock
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

enum class Status { Idle, Preparing, Playing, Paused, Buffering, Finished, Error }

data class ReaderState(
    val status: Status = Status.Idle,
    val caption: String = "",
    val index: Int = 0,
    val total: Int = 0,
    val speed: Float = 1f,
    /** Screen bounds of the paragraph being read, when reading from the screen. */
    val bounds: Rect? = null,
    val fromScreen: Boolean = false,
    val message: String? = null,
    val session: Long = 0,
)

/**
 * Plays text with Kokoro. Sentences are generated on one thread a little ahead of playback and
 * played on another through an AudioTrack, so the reader can jump back or forward by seconds
 * (like a podcast player) and change speed instantly without re-generating speech.
 */
object Reader {
    private const val TAG = "Reader"
    private const val SR = KokoroEngine.SAMPLE_RATE
    private const val LOOKAHEAD_FRAMES = SR * 60L     // generate up to a minute ahead
    private const val KEEP_BEHIND_FRAMES = SR * 180L  // keep three minutes behind for rewinding
    private const val WRITE_FRAMES = 2048

    private lateinit var app: Context
    private val main = Handler(Looper.getMainLooper())
    private var session: Session? = null
    private var nextId = 1L

    private val _state = MutableStateFlow(ReaderState())
    val state: StateFlow<ReaderState> = _state

    /** True while a session exists (playing, paused, finished or showing a message). */
    val isBusy: Boolean get() = _state.value.status != Status.Idle

    fun init(context: Context) {
        app = context.applicationContext
    }

    // ---------------------------------------------------------------- public controls

    fun read(
        blocks: List<TextBlock>,
        startBlock: Int = 0,
        startChar: Int = 0,
        fromScreen: Boolean = false,
        voice: Voice? = null,
    ) {
        val settings = Prefs.current
        stopSession(keepFocus = true)
        val id = nextId++
        if (!ModelManager.isReady(app)) {
            _state.value = ReaderState(
                status = Status.Error, session = id,
                message = "Download the Kokoro voices in Yomiage first.",
            )
            return
        }
        val chunks = TextChunker.build(blocks, startBlock, startChar, settings.readUrls)
        if (chunks.isEmpty()) {
            _state.value = ReaderState(status = Status.Error, session = id, message = "There's no text to read here.")
            return
        }
        val s = Session(id, blocks, chunks, voice ?: Voices.byId(settings.voiceId), fromScreen, settings.speed)
        session = s
        requestFocus()
        registerNoisy()
        s.start()
    }

    /** Shows the player bar with a status line while the screen is being looked at. */
    fun showPreparing(text: String) {
        stopSession(keepFocus = true)
        _state.value = ReaderState(status = Status.Preparing, caption = text, speed = Prefs.current.speed, session = nextId++)
    }

    fun showMessage(text: String) {
        stopSession()
        _state.value = ReaderState(status = Status.Error, message = text, session = nextId++)
    }

    fun preview(voice: Voice) {
        read(listOf(TextBlock("Hi, I'm ${voice.name}. This is how I sound when I read your screen.")), voice = voice)
    }

    fun togglePlay() {
        val s = session ?: return
        s.withLock {
            when {
                s.finished -> {
                    s.seekAbsChunk = 0
                    s.paused = false
                }
                s.paused -> s.paused = false
                else -> s.paused = true
            }
            s.pausedByFocus = false
            if (!s.paused) requestFocus()
        }
    }

    fun pause(byFocus: Boolean = false) {
        val s = session ?: return
        s.withLock {
            if (!s.paused && !s.finished) {
                s.paused = true
                s.pausedByFocus = byFocus
            }
        }
    }

    fun resume() {
        val s = session ?: return
        s.withLock {
            if (s.finished) s.seekAbsChunk = 0
            s.paused = false
            s.pausedByFocus = false
        }
        requestFocus()
    }

    /** Jump by [seconds] of speech (negative = back). */
    fun seekBy(seconds: Float) {
        val s = session ?: return
        s.withLock {
            s.seekRel += (seconds * SR).toLong()
            s.seekRelPending = true
            if (s.finished) s.paused = false
        }
    }

    fun setSpeed(speed: Float) {
        val v = (Math.round(speed * 20f) / 20f).coerceIn(0.5f, 3f)
        Prefs.update { it.copy(speed = v) }
        val s = session ?: run {
            _state.value = _state.value.copy(speed = v)
            return
        }
        s.withLock {
            s.speed = v
            s.speedDirty = true
            s.publishLocked()
        }
    }

    /** Switches voice mid-reading: the current sentence starts again in the new voice. */
    fun setVoice(voice: Voice) {
        Prefs.update { it.copy(voiceId = voice.id) }
        val s = session ?: return
        if (s.voice.id == voice.id) return
        s.withLock {
            s.voice = voice
            s.invalidateAllLocked()
            s.seekAbsChunk = s.heardChunk.coerceIn(0, s.n - 1)
        }
    }

    fun stop() {
        stopSession()
        _state.value = ReaderState(speed = Prefs.current.speed, session = nextId++)
    }

    private fun stopSession(keepFocus: Boolean = false) {
        session?.stop()
        session = null
        if (!keepFocus) {
            abandonFocus()
            unregisterNoisy()
        }
    }

    // ---------------------------------------------------------------- audio focus & headphones

    private val attributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private val focusRequest: AudioFocusRequest by lazy {
        AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attributes)
            .setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener({ change ->
                when (change) {
                    AudioManager.AUDIOFOCUS_LOSS -> pause(byFocus = false)
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT,
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> pause(byFocus = true)
                    AudioManager.AUDIOFOCUS_GAIN -> {
                        val s = session
                        if (s != null && s.pausedByFocus) resume()
                    }
                }
            }, main)
            .build()
    }

    private fun audioManager() = app.getSystemService(AudioManager::class.java)

    private fun requestFocus() {
        try {
            audioManager().requestAudioFocus(focusRequest)
        } catch (e: Exception) {
            Log.w(TAG, "focus", e)
        }
    }

    private fun abandonFocus() {
        try {
            audioManager().abandonAudioFocusRequest(focusRequest)
        } catch (e: Exception) {
            Log.w(TAG, "focus", e)
        }
    }

    private var noisyRegistered = false
    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) pause()
        }
    }

    private fun registerNoisy() {
        if (noisyRegistered) return
        ContextCompat.registerReceiver(
            app, noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        noisyRegistered = true
    }

    private fun unregisterNoisy() {
        if (!noisyRegistered) return
        try {
            app.unregisterReceiver(noisyReceiver)
        } catch (_: Exception) {
        }
        noisyRegistered = false
    }

    // ---------------------------------------------------------------- session

    private class Session(
        val id: Long,
        val blocks: List<TextBlock>,
        val chunks: List<Chunk>,
        var voice: Voice,
        val fromScreen: Boolean,
        var speed: Float,
    ) {
        val n = chunks.size
        val audio = arrayOfNulls<ShortArray>(n)
        val lengths = IntArray(n) { -1 }
        val lock = ReentrantLock()
        val cond: Condition = lock.newCondition()

        var epoch = 0
        var speedDirty = true
        var nativeSpeed = 1f          // used only if this phone can't time-stretch an AudioTrack
        @Volatile var stopped = false
        var paused = false
        var pausedByFocus = false
        var finished = false
        var failed: String? = null
        var loadingModel = !KokoroEngine.isLoaded

        // Where the next samples will be written from.
        var wChunk = 0
        var wOff = 0
        // The position the current AudioTrack started from, and how much was written to it.
        var bChunk = 0
        var bOff = 0
        var written = 0L
        // What the listener is hearing now.
        var heardChunk = 0
        var heardOff = 0

        var seekRel = 0L
        var seekRelPending = false
        var seekAbsChunk = -1
        var draining = false
        var drainDeadline = 0L

        var status = Status.Preparing
        private var lastPublished: ReaderState? = null

        inline fun <T> withLock(block: () -> T): T {
            lock.lock()
            try {
                val r = block()
                cond.signalAll()
                return r
            } finally {
                lock.unlock()
            }
        }

        fun start() {
            publishLocked()
            Thread({ generateLoop() }, "yomiage-gen").start()
            Thread({ playLoop() }, "yomiage-play").start()
        }

        fun stop() {
            lock.lock()
            try {
                stopped = true
                cond.signalAll()
            } finally {
                lock.unlock()
            }
        }

        fun invalidateAllLocked() {
            epoch++
            for (i in 0 until n) {
                audio[i] = null
                lengths[i] = -1
            }
        }

        // ------------------------------------------------------------ generation thread

        private fun generateLoop() {
            val ctx = app
            while (true) {
                var index = -1
                var text = ""
                var v: Voice = Voices.byId(3)
                var ep = 0
                var sp = 1f
                lock.lock()
                try {
                    while (true) {
                        if (stopped) return
                        index = nextToGenerateLocked()
                        if (index >= 0) break
                        cond.await(300, TimeUnit.MILLISECONDS)
                    }
                    text = chunks[index].text
                    v = voice
                    ep = epoch
                    sp = nativeSpeed
                } finally {
                    lock.unlock()
                }

                var pcm: ShortArray? = null
                var error: String? = null
                try {
                    val samples = KokoroEngine.synth(ctx, text, v, sp)
                    if (samples == null) {
                        error = "The voice pack isn't installed."
                    } else {
                        pcm = toPcm(samples, chunks[index].gapMs)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "synth failed for: $text", e)
                    // Skip a sentence the engine can't handle instead of stopping.
                    pcm = ShortArray(SR / 4)
                } catch (e: Throwable) {
                    // Native library or out-of-memory problems: stop with a message.
                    Log.e(TAG, "voice engine failed", e)
                    error = "The voice engine couldn't start (${e.javaClass.simpleName})."
                }

                lock.lock()
                try {
                    if (stopped) return
                    loadingModel = false
                    if (ep == epoch) {
                        if (error != null) {
                            failed = error
                        } else if (pcm != null) {
                            audio[index] = pcm
                            lengths[index] = pcm.size
                            evictLocked()
                        }
                    }
                    cond.signalAll()
                } finally {
                    lock.unlock()
                }
            }
        }

        private fun nextToGenerateLocked(): Int {
            if (failed != null) return -1
            var i = min(heardChunk, wChunk).coerceAtLeast(0)
            var ahead = 0L
            while (i < n) {
                val a = audio[i] ?: return i
                if (i >= heardChunk) {
                    ahead += a.size
                    if (ahead > LOOKAHEAD_FRAMES) return -1
                }
                i++
            }
            return -1
        }

        private fun evictLocked() {
            var behind = 0L
            for (i in heardChunk - 1 downTo 0) {
                val len = lengths[i]
                if (len > 0) behind += len
                if (behind > KEEP_BEHIND_FRAMES) audio[i] = null
            }
        }

        // ------------------------------------------------------------ playback thread

        private fun newTrack(): AudioTrack {
            val min = AudioTrack.getMinBufferSize(SR, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val bytes = max(min, SR / 5 * 2) // ~200 ms
            val t = AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(SR)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build()
                )
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(bytes)
                .build()
            if (Build.VERSION.SDK_INT >= 31) {
                try {
                    t.setStartThresholdInFrames(512)
                } catch (_: Exception) {
                }
            }
            speedDirty = true
            draining = false
            return t
        }

        private fun applySpeedLocked(t: AudioTrack) {
            try {
                t.playbackParams = PlaybackParams().setSpeed(speed).setPitch(1f)
            } catch (e: Exception) {
                // Fall back to letting Kokoro speak faster or slower itself.
                Log.w(TAG, "AudioTrack speed not supported, using Kokoro speed", e)
                if (abs(nativeSpeed - speed) > 0.01f) {
                    nativeSpeed = speed
                    invalidateAllLocked()
                    seekAbsChunk = heardChunk.coerceIn(0, n - 1)
                }
            }
        }

        private fun headFrames(t: AudioTrack): Long =
            (t.playbackHeadPosition.toLong() and 0xFFFFFFFFL).coerceAtMost(written)

        /** Walks forward [frames] from a position. Stops at chunks whose length isn't known yet. */
        private fun forward(c0: Int, o0: Int, frames: Long): Pair<Int, Int> {
            var c = c0
            var o = o0.toLong() + frames
            while (c < n) {
                val len = lengths[c]
                if (len < 0) return c to 0
                if (o < len) return c to o.toInt()
                o -= len
                c++
            }
            return n to 0
        }

        private fun backward(c0: Int, o0: Int, frames: Long): Pair<Int, Int> {
            var c = c0.coerceAtMost(n)
            var o = o0.toLong() - frames
            if (c == n) {
                // From the very end: step into the last chunk.
                if (n == 0) return 0 to 0
                c = n - 1
                val len = lengths[c]
                if (len < 0) return c to 0
                o += len
            }
            while (o < 0) {
                if (c == 0) return 0 to 0
                c--
                val len = lengths[c]
                if (len < 0) return c to 0
                o += len
            }
            return c to o.toInt()
        }

        private fun updateHeardLocked(t: AudioTrack) {
            val (c, o) = forward(bChunk, bOff, headFrames(t))
            heardChunk = c
            heardOff = o
        }

        private fun playLoop() {
            var t: AudioTrack? = null
            try {
                t = newTrack()
                while (true) {
                    var data: ShortArray? = null
                    var off = 0
                    var count = 0
                    lock.lock()
                    try {
                        if (stopped) return
                        val track = t!!

                        if (seekAbsChunk >= 0 || seekRelPending) {
                            updateHeardLocked(track)
                            val target: Pair<Int, Int> = when {
                                seekAbsChunk >= 0 -> seekAbsChunk.coerceIn(0, n - 1) to 0
                                seekRel >= 0 -> forward(heardChunk, heardOff, seekRel)
                                else -> backward(heardChunk, heardOff, -seekRel)
                            }
                            seekAbsChunk = -1
                            seekRelPending = false
                            seekRel = 0
                            try {
                                track.pause()
                                track.flush()
                            } catch (_: Exception) {
                            }
                            track.release()
                            t = newTrack()
                            bChunk = target.first
                            bOff = target.second
                            wChunk = target.first
                            wOff = target.second
                            written = 0
                            heardChunk = target.first
                            heardOff = target.second
                            if (finished && target.first < n) {
                                finished = false
                                paused = false
                            }
                            cond.signalAll()
                            publishLocked()
                            continue
                        }

                        if (speedDirty) {
                            speedDirty = false
                            applySpeedLocked(track)
                        }

                        if (failed != null) {
                            status = Status.Error
                            publishLocked()
                            cond.await(500, TimeUnit.MILLISECONDS)
                            continue
                        }

                        if (paused) {
                            if (track.playState == AudioTrack.PLAYSTATE_PLAYING) track.pause()
                            if (!finished) updateHeardLocked(track)
                            status = if (finished) Status.Finished else Status.Paused
                            publishLocked()
                            cond.await(500, TimeUnit.MILLISECONDS)
                            continue
                        }

                        if (wChunk >= n) {
                            // Everything is written: let the track play out, then finish.
                            val now = SystemClock.elapsedRealtime()
                            if (!draining) {
                                draining = true
                                val left = (written - headFrames(track)).coerceAtLeast(0)
                                drainDeadline = now + (left * 1000 / SR / speed).toLong() + 800
                                try {
                                    track.stop() // plays what's buffered, then stops
                                } catch (_: Exception) {
                                }
                            }
                            updateHeardLocked(track)
                            if (headFrames(track) >= written - SR / 50 || now > drainDeadline) {
                                finished = true
                                paused = true
                                heardChunk = n
                                status = Status.Finished
                            } else {
                                status = Status.Playing
                            }
                            publishLocked()
                            cond.await(60, TimeUnit.MILLISECONDS)
                            continue
                        }

                        val a = audio[wChunk]
                        if (a == null) {
                            updateHeardLocked(track)
                            if (headFrames(track) >= written) {
                                status = if (written == 0L && wChunk == bChunk) Status.Preparing else Status.Buffering
                                publishLocked()
                            }
                            cond.await(80, TimeUnit.MILLISECONDS)
                            continue
                        }
                        if (wOff >= a.size) {
                            wChunk++
                            wOff = 0
                            continue
                        }
                        data = a
                        off = wOff
                        count = min(WRITE_FRAMES, a.size - wOff)
                        wOff += count
                        written += count
                        if (wOff >= a.size) {
                            wChunk++
                            wOff = 0
                        }
                        if (track.playState != AudioTrack.PLAYSTATE_PLAYING) track.play()
                        status = Status.Playing
                    } finally {
                        lock.unlock()
                    }

                    // Write outside the lock: this blocks while the track's buffer is full.
                    val track = t!!
                    var done = 0
                    while (done < count && !stopped) {
                        val r = track.write(data!!, off + done, count - done, AudioTrack.WRITE_BLOCKING)
                        if (r <= 0) break
                        done += r
                    }
                    lock.lock()
                    try {
                        updateHeardLocked(track)
                        publishLocked()
                    } finally {
                        lock.unlock()
                    }
                }
            } catch (e: Throwable) {
                Log.e(TAG, "playback failed", e)
                lock.lock()
                try {
                    failed = "Playback stopped: ${e.message ?: e.javaClass.simpleName}"
                    status = Status.Error
                    publishLocked()
                } finally {
                    lock.unlock()
                }
            } finally {
                try {
                    t?.pause()
                    t?.flush()
                    t?.release()
                } catch (_: Exception) {
                }
            }
        }

        // ------------------------------------------------------------ state

        fun publishLocked() {
            if (stopped) return
            val idx = heardChunk.coerceIn(0, max(0, n - 1))
            val chunk = chunks.getOrNull(idx)
            val caption = when {
                status == Status.Preparing && loadingModel -> "Warming up the voice…"
                status == Status.Preparing -> chunk?.text ?: ""
                else -> chunk?.text ?: ""
            }
            val st = ReaderState(
                status = status,
                caption = caption,
                index = if (heardChunk >= n) n else idx + 1,
                total = n,
                speed = speed,
                bounds = if (fromScreen && chunk != null) blocks.getOrNull(chunk.block)?.bounds else null,
                fromScreen = fromScreen,
                message = failed,
                session = id,
            )
            if (st != lastPublished) {
                lastPublished = st
                _state.value = st
            }
        }
    }

    /** Trims long silences at the ends of a sentence, adds the pause after it, and converts to 16-bit. */
    private fun toPcm(samples: FloatArray, gapMs: Int): ShortArray {
        val threshold = 0.004f
        val margin = SR * 30 / 1000
        var first = 0
        while (first < samples.size && abs(samples[first]) < threshold) first++
        var last = samples.size - 1
        while (last > first && abs(samples[last]) < threshold) last--
        if (first >= samples.size) return ShortArray(SR * gapMs / 1000)
        val from = max(0, first - margin)
        val to = min(samples.size, last + 1 + margin)
        val gap = SR * gapMs / 1000
        val out = ShortArray(to - from + gap)
        for (i in from until to) {
            val v = (samples[i] * 32767f).coerceIn(-32768f, 32767f)
            out[i - from] = v.toInt().toShort()
        }
        return out
    }
}
