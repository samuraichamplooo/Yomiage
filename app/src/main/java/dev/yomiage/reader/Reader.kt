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
    /** The sentence being spoken (null while idle or preparing). */
    val chunk: Chunk? = null,
    val chunkIndex: Int = -1,
    /** Set for text that isn't on screen: "Simplified" or "Explanation". */
    val label: String? = null,
    /** The text being read, for sessions with a [label] (shown in the player's text panel). */
    val blocks: List<TextBlock> = emptyList(),
)

/** What's being read right now, so it can be explained and returned to. */
class Snapshot(
    val session: Long,
    val blocks: List<TextBlock>,
    val chunks: List<Chunk>,
    val chunkIndex: Int,
    val fromScreen: Boolean,
    val label: String?,
)

/** The word being spoken, as [wordStart, wordEnd) in the current chunk's text. Updated ~10x a second. */
data class Progress(val session: Long = 0, val chunkIndex: Int = -1, val wordStart: Int = -1, val wordEnd: Int = -1)

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
    @Volatile
    private var session: Session? = null
    private var nextId = 1L

    private val _state = MutableStateFlow(ReaderState())
    val state: StateFlow<ReaderState> = _state

    private val _progress = MutableStateFlow(Progress())
    val progress: StateFlow<Progress> = _progress

    private val _revision = MutableStateFlow(0)
    /** Goes up whenever the text being read changes (more of the page, an explanation arrived). */
    val revision: StateFlow<Int> = _revision

    /** Reading waits this long at a figure for its explanation, then moves on without it. */
    private const val HOLD_TIMEOUT_MS = 25_000L

    /** True while a session exists (playing, paused, finished or showing a message). */
    val isBusy: Boolean get() = _state.value.status != Status.Idle

    fun init(context: Context) {
        app = context.applicationContext
    }

    // ---------------------------------------------------------------- public controls

    /** Starts reading and returns the session id. */
    fun read(
        blocks: List<TextBlock>,
        startBlock: Int = 0,
        startChar: Int = 0,
        fromScreen: Boolean = false,
        voice: Voice? = null,
        /** More text will be added with [append] (e.g. while Gemini is still writing). */
        open: Boolean = false,
        label: String? = null,
        waiting: String? = null,
    ): Long {
        val settings = Prefs.current
        stopSession(keepFocus = true)
        val id = nextId++
        if (!ModelManager.isReady(app)) {
            _state.value = ReaderState(
                status = Status.Error, session = id,
                message = "Download the Kokoro voices in Yomiage first.",
            )
            return id
        }
        val chunks = TextChunker.build(blocks, startBlock, startChar, settings.readUrls)
        if (chunks.isEmpty() && !open) {
            _state.value = ReaderState(status = Status.Error, session = id, message = "There's no text to read here.")
            return id
        }
        val s = Session(id, blocks, chunks, voice ?: Voices.byId(settings.voiceId), fromScreen, settings.speed)
        s.open = open
        s.label = label
        s.waiting = waiting
        session = s
        requestFocus()
        registerNoisy()
        s.start()
        _revision.value++
        return id
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

    /** Adds text to an open session. Returns false if that session is no longer the current one. */
    fun append(id: Long, more: List<TextBlock>, done: Boolean): Boolean {
        val s = session ?: return false
        if (s.id != id) return false
        s.withLock {
            if (more.isNotEmpty()) {
                val offset = s.blocks.size
                val built = TextChunker.build(more, 0, 0, Prefs.current.readUrls, rampFrom = s.n)
                s.blocks = s.blocks + more
                for (c in built) {
                    s.chunks.add(c.withBlock(c.block + offset))
                    s.audio.add(null)
                    s.lengths.add(-1)
                    s.speechLen.add(1)
                }
            }
            if (done) s.open = false
            s.publishLocked()
        }
        _revision.value++
        return true
    }

    /**
     * Puts the explanation of a figure or table ([block]) where reading is waiting for it, or, with
     * no [text], lets reading move on. Returns false if that session is no longer the current one.
     */
    fun resolveHold(id: Long, block: Int, text: String?): Boolean {
        val s = session ?: return false
        if (s.id != id) return false
        s.withLock {
            val i = (0 until s.n).firstOrNull { s.chunks[it].hold && s.chunks[it].block == block } ?: return@withLock
            val clean = text?.trim().orEmpty()
            val built = if (clean.isEmpty()) {
                emptyList()
            } else {
                val old = s.blocks.getOrNull(block)
                val nb = TextBlock(clean, old?.bounds, BlockKind.EXPLAINED)
                s.blocks = s.blocks.toMutableList().also { if (block < it.size) it[block] = nb }
                TextChunker.build(listOf(nb), 0, 0, Prefs.current.readUrls, rampFrom = 10).map { it.withBlock(block) }
            }
            s.replaceChunkLocked(i, built)
            s.publishLocked()
        }
        _revision.value++
        return true
    }

    /** Jumps to the start of paragraph [block] (tapping a paragraph in the reading view). */
    fun jumpToBlock(block: Int) {
        val s = session ?: return
        s.withLock {
            if (s.n == 0) return@withLock
            val i = (0 until s.n).firstOrNull { s.chunks[it].block == block }
                ?: (0 until s.n).firstOrNull { s.chunks[it].block > block }
                ?: return@withLock
            s.seekAbsChunk = i
            s.paused = false
            s.pausedByFocus = false
        }
        requestFocus()
    }

    /** Lets more text be added to a session again (a page that carries on after an explanation). */
    fun reopen(id: Long) {
        val s = session ?: return
        if (s.id != id) return
        s.withLock {
            s.open = true
            s.publishLocked()
        }
    }

    /** Updates the "waiting" line of an open session that has nothing to read yet. */
    fun setWaiting(id: Long, text: String) {
        val s = session ?: return
        if (s.id != id) return
        s.withLock {
            s.waiting = text
            s.publishLocked()
        }
    }

    fun snapshot(): Snapshot? {
        val s = session ?: return null
        return s.withLock {
            if (s.n == 0) {
                null
            } else {
                Snapshot(s.id, s.blocks, ArrayList(s.chunks), s.heardChunk.coerceIn(0, s.n - 1), s.fromScreen, s.label)
            }
        }
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

    /**
     * Where reading is and how long all of it is, in milliseconds at normal speed. Parts that
     * haven't been voiced yet are estimated from how long the voiced parts took per letter.
     */
    fun timeline(): Pair<Long, Long>? {
        val s = session ?: return null
        return s.withLock {
            if (s.n == 0) return@withLock null
            val perChar = s.framesPerCharLocked()
            val heard = s.heardChunk.coerceIn(0, s.n)
            var pos = 0.0
            var total = 0.0
            for (i in 0 until s.n) {
                val len = s.estFramesLocked(i, perChar)
                if (i < heard) pos += len
                total += len
            }
            if (heard < s.n) pos += s.heardOff
            (pos * 1000 / SR).toLong() to (total * 1000 / SR).toLong()
        }
    }

    /** Jumps to about [ms] into the text (scrubbing the lock screen player), to the start of a sentence. */
    fun seekToMs(ms: Long) {
        val s = session ?: return
        s.withLock {
            if (s.n == 0) return@withLock
            val perChar = s.framesPerCharLocked()
            val target = ms.coerceAtLeast(0) * SR / 1000.0
            var at = 0.0
            var idx = s.n - 1
            for (i in 0 until s.n) {
                val len = s.estFramesLocked(i, perChar)
                if (at + len > target) {
                    idx = i
                    break
                }
                at += len
            }
            s.seekAbsChunk = idx
            if (s.finished) s.paused = false
        }
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
            if (s.n > 0) s.seekAbsChunk = s.heardChunk.coerceIn(0, s.n - 1)
        }
    }

    fun stop() {
        stopSession()
        _state.value = ReaderState(speed = Prefs.current.speed, session = nextId++)
    }

    private fun stopSession(keepFocus: Boolean = false) {
        session?.stop()
        session = null
        _progress.value = Progress()
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
        var blocks: List<TextBlock>,
        chunkList: List<Chunk>,
        var voice: Voice,
        val fromScreen: Boolean,
        var speed: Float,
    ) {
        val chunks = ArrayList(chunkList)
        val n: Int get() = chunks.size
        val audio = ArrayList<ShortArray?>(chunkList.map { null })
        val lengths = ArrayList<Int>(chunkList.map { -1 })
        val speechLen = ArrayList<Int>(chunkList.map { 1 })   // frames of actual speech (without the pause after it)
        var open = false
        var label: String? = null
        var waiting: String? = null
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
        // When reading reached a figure that's still being explained.
        private var holdIndex = -1
        private var holdSince = 0L

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

        /** Audio frames per letter so far (about 14 letters a second before anything's been voiced). */
        fun framesPerCharLocked(): Double {
            var frames = 0L
            var chars = 0L
            for (i in 0 until n) {
                val l = lengths[i]
                if (l > 0 && !chunks[i].hold) {
                    frames += l
                    chars += chunks[i].text.length
                }
            }
            return if (chars >= 150) frames.toDouble() / chars else SR / 14.0
        }

        /** How long chunk [i] is (or will be), in frames. */
        fun estFramesLocked(i: Int, perChar: Double): Double {
            val l = lengths[i]
            if (l >= 0) return l.toDouble()
            val c = chunks[i]
            if (c.hold) return 0.0
            return c.text.length * perChar + c.gapMs * SR / 1000.0
        }

        /**
         * Swaps chunk [i] (a placeholder) for [with], keeping every position in step. With nothing
         * to put there, it becomes a moment of silence so reading just carries on.
         */
        fun replaceChunkLocked(i: Int, with: List<Chunk>) {
            if (with.isEmpty()) {
                val c = chunks[i]
                chunks[i] = Chunk(c.text, c.block, 0, c.map, hold = false)
                val quiet = ShortArray(SR / 20)
                audio[i] = quiet
                lengths[i] = quiet.size
                speechLen[i] = quiet.size
                return
            }
            chunks.removeAt(i)
            audio.removeAt(i)
            lengths.removeAt(i)
            speechLen.removeAt(i)
            chunks.addAll(i, with)
            audio.addAll(i, List(with.size) { null })
            lengths.addAll(i, List(with.size) { -1 })
            speechLen.addAll(i, List(with.size) { 1 })
            val d = with.size - 1
            if (d != 0) {
                // A sentence being made right now belongs to the old numbering: let it be redone.
                epoch++
                if (wChunk > i) wChunk += d
                if (bChunk > i) bChunk += d
                if (heardChunk > i) heardChunk += d
                if (seekAbsChunk > i) seekAbsChunk += d
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
                var gapMs = 0
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
                    gapMs = chunks[index].gapMs
                    v = voice
                    ep = epoch
                    sp = nativeSpeed
                } finally {
                    lock.unlock()
                }

                var pcm: ShortArray? = null
                var speech = 1
                var error: String? = null
                try {
                    val samples = KokoroEngine.synth(ctx, text, v, sp)
                    if (samples == null) {
                        error = "The voice pack isn't installed."
                    } else {
                        val gap = SR * gapMs / 1000
                        pcm = toPcm(samples, gap)
                        speech = (pcm.size - gap).coerceAtLeast(1)
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
                            speechLen[index] = speech
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
                if (chunks[i].hold) {
                    i++
                    continue
                }
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
                    if (n > 0) seekAbsChunk = heardChunk.coerceIn(0, n - 1)
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

                        if ((seekAbsChunk >= 0 || seekRelPending) && n == 0) {
                            seekAbsChunk = -1
                            seekRelPending = false
                            seekRel = 0
                        }
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
                            holdIndex = -1
                            if (track.playState == AudioTrack.PLAYSTATE_PLAYING) track.pause()
                            if (!finished) updateHeardLocked(track)
                            status = if (finished) Status.Finished else Status.Paused
                            publishLocked()
                            cond.await(500, TimeUnit.MILLISECONDS)
                            continue
                        }

                        if (wChunk >= n && open) {
                            // Waiting for more text (Gemini is still writing).
                            updateHeardLocked(track)
                            if (headFrames(track) >= written) {
                                status = if (written == 0L) Status.Preparing else Status.Buffering
                                publishLocked()
                            }
                            cond.await(100, TimeUnit.MILLISECONDS)
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
                        if (a == null && chunks[wChunk].hold) {
                            // A figure still being explained: wait a while, then move on without it.
                            val now = SystemClock.elapsedRealtime()
                            if (holdIndex != wChunk) {
                                holdIndex = wChunk
                                holdSince = now
                            }
                            if (now - holdSince > HOLD_TIMEOUT_MS) {
                                replaceChunkLocked(wChunk, emptyList())
                                holdIndex = -1
                                main.post { _revision.value++ }
                                continue
                            }
                        }
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
                n == 0 -> waiting ?: "Getting ready…"
                chunk != null && chunk.hold -> "Looking at ${chunk.text}…"
                status == Status.Preparing && loadingModel -> "Warming up the voice…"
                status == Status.Preparing -> chunk?.text ?: ""
                else -> chunk?.text ?: ""
            }
            val speaking = status == Status.Playing || status == Status.Paused || status == Status.Buffering
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
                chunk = if (speaking || status == Status.Finished) chunk else null,
                chunkIndex = if (speaking) idx else -1,
                label = label,
                blocks = if (label != null) blocks else emptyList(),
            )
            if (st != lastPublished) {
                lastPublished = st
                _state.value = st
            }
            // Which word is being said, estimated from how far into the sentence the audio is.
            val pr = if (speaking && chunk != null && heardChunk < n) {
                val frac = heardOff.toFloat() / speechLen[idx]
                val w = WordTiming.wordAt(chunk.text, frac)
                Progress(id, idx, w?.get(0) ?: -1, w?.get(1) ?: -1)
            } else {
                Progress(id, -1, -1, -1)
            }
            if (pr != _progress.value) _progress.value = pr
        }
    }

    /** Trims long silences at the ends of a sentence, adds [gap] frames of pause, and converts to 16-bit. */
    private fun toPcm(samples: FloatArray, gap: Int): ShortArray {
        val threshold = 0.004f
        val margin = SR * 30 / 1000
        var first = 0
        while (first < samples.size && abs(samples[first]) < threshold) first++
        var last = samples.size - 1
        while (last > first && abs(samples[last]) < threshold) last--
        if (first >= samples.size) return ShortArray(gap + 1)
        val from = max(0, first - margin)
        val to = min(samples.size, last + 1 + margin)
        val out = ShortArray(to - from + gap)
        for (i in from until to) {
            val v = (samples[i] * 32767f).coerceIn(-32768f, 32767f)
            out[i - from] = v.toInt().toShort()
        }
        return out
    }
}
