package dev.yomiage.ai

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.google.mlkit.genai.prompt.ImagePart
import com.google.mlkit.genai.prompt.TextPart
import com.google.mlkit.genai.prompt.generateContentRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

enum class NanoState { UNKNOWN, AVAILABLE, DOWNLOADABLE, DOWNLOADING, UNAVAILABLE }

/**
 * Gemini Nano, running on the phone through ML Kit's Prompt API. Free, private, works offline
 * and needs no key. Android only lets it run while the app is in front, so a see-through,
 * untouchable Yomiage window is opened for the few seconds it's thinking.
 */
object OnDevice {
    private const val TAG = "OnDevice"
    private const val AICORE = "com.google.android.aicore"
    private lateinit var app: Context
    private var model: GenerativeModel? = null

    private val _state = MutableStateFlow(NanoState.UNKNOWN)
    val state: StateFlow<NanoState> = _state

    private val _download = MutableStateFlow<String?>(null)
    /** What the download is doing, in words (null when there's nothing to say). */
    val download: StateFlow<String?> = _download

    private val _busy = MutableStateFlow(false)
    /** True while Yomiage is running the download (it keeps going if you leave the screen). */
    val busy: StateFlow<Boolean> = _busy

    // Lives as long as the app, so leaving the screen or folding the phone doesn't stop the download.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var downloadJob: Job? = null

    fun init(context: Context) {
        app = context.applicationContext
    }

    private fun client(): GenerativeModel = model ?: Generation.getClient().also { model = it }

    /** False only once we know the phone can't run it. */
    fun mightWork() = _state.value != NanoState.UNAVAILABLE

    suspend fun refresh(): NanoState {
        val s = try {
            when (client().checkStatus()) {
                FeatureStatus.AVAILABLE -> NanoState.AVAILABLE
                FeatureStatus.DOWNLOADABLE -> NanoState.DOWNLOADABLE
                FeatureStatus.DOWNLOADING -> NanoState.DOWNLOADING
                else -> NanoState.UNAVAILABLE
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.w(TAG, "status check failed", e)
            NanoState.UNAVAILABLE
        }
        _state.value = s
        if (s == NanoState.AVAILABLE && !_busy.value) _download.value = null
        return s
    }

    /** Whether Google's Android AICore app (which downloads and runs Gemini Nano) is on the phone. */
    fun aiCoreInstalled(): Boolean = try {
        app.packageManager.getPackageInfo(AICORE, 0)
        true
    } catch (e: Exception) {
        false
    }

    /** Opens Android AICore in the Play Store, to update it. */
    fun openAiCoreInStore(context: Context) {
        val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$AICORE"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(market)
        } catch (e: Exception) {
            try {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$AICORE"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            } catch (_: Exception) {
            }
        }
    }

    /**
     * Starts (or restarts) the one-time Gemini Nano download. It runs in the background and checks
     * on itself, so it finishes even if you leave the screen. Progress goes to [download].
     */
    fun startDownload(restart: Boolean = false) {
        val old = downloadJob
        if (old?.isActive == true && !restart) return
        _busy.value = true
        downloadJob = scope.launch {
            old?.cancelAndJoin()
            val me = coroutineContext[Job]
            try {
                runDownload()
            } finally {
                if (downloadJob === me) _busy.value = false
            }
        }
    }

    private suspend fun runDownload() {
        if (refresh() == NanoState.AVAILABLE) return
        _download.value = "Asking Google's AICore app to start the download…"
        var total = 0L
        var lastEvent = SystemClock.elapsedRealtime()
        var finished = false
        coroutineScope {
            val dl = launch {
                try {
                    client().download().collect { st ->
                        lastEvent = SystemClock.elapsedRealtime()
                        when (st) {
                            is DownloadStatus.DownloadStarted -> {
                                total = st.bytesToDownload
                                _download.value = if (total > 0) "Downloading… 0 of ${mb(total)}" else "Downloading…"
                            }
                            is DownloadStatus.DownloadProgress -> {
                                val got = st.totalBytesDownloaded
                                _download.value = if (total > 0) {
                                    "Downloading… ${mb(got)} of ${mb(total)} (${(got * 100 / total).coerceIn(0, 100)}%)"
                                } else {
                                    "Downloading… ${mb(got)}"
                                }
                            }
                            is DownloadStatus.DownloadCompleted -> {
                                finished = true
                                _download.value = "Finishing up…"
                            }
                            is DownloadStatus.DownloadFailed -> {
                                finished = true
                                _download.value = "Download failed: ${st.e.message ?: "unknown error"}"
                            }
                            else -> {}
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    Log.w(TAG, "download failed", e)
                    finished = true
                    _download.value = "Download failed: ${e.message ?: e.javaClass.simpleName}"
                }
            }
            // Checks on the download now and then: AICore sometimes finishes (or stalls) without saying so.
            val watch = launch {
                while (true) {
                    delay(15_000)
                    if (refresh() == NanoState.AVAILABLE) {
                        finished = true
                        dl.cancel()
                        return@launch
                    }
                    if (SystemClock.elapsedRealtime() - lastEvent > 90_000) {
                        _download.value = "Still waiting on Google's AICore app. Keep Wi-Fi on and the phone " +
                            "plugged in, make sure Android AICore is up to date, then tap Try again."
                    }
                }
            }
            dl.join()
            watch.cancel()
        }
        // The download "completing" isn't proof: check the model is really there.
        when (refresh()) {
            NanoState.AVAILABLE -> _download.value = null
            NanoState.DOWNLOADING -> _download.value =
                "Google's AICore app is still setting it up in the background. This updates when it's ready."
            else -> if (_download.value?.startsWith("Download failed") != true) {
                _download.value = "The download didn't finish. Tap Try again."
            }
        }
    }

    /** Re-checks every so often while AICore downloads on its own, so the screen flips to Ready. */
    suspend fun watchWhileDownloading() {
        while (true) {
            val s = refresh()
            if (s != NanoState.DOWNLOADING && !(s == NanoState.DOWNLOADABLE && _busy.value)) return
            delay(10_000)
        }
    }

    private fun mb(bytes: Long): String =
        if (bytes >= 1_073_741_824) String.format(Locale.US, "%.1f GB", bytes / 1_073_741_824.0)
        else "${bytes / 1_048_576} MB"

    suspend fun stream(prompt: String, cancelled: () -> Boolean, image: Bitmap? = null, onText: (String) -> Unit) {
        var s = _state.value
        if (s != NanoState.AVAILABLE) s = refresh()
        when (s) {
            NanoState.AVAILABLE -> {}
            NanoState.DOWNLOADABLE -> throw AiException("On-device AI needs a one-time download. Set it up in Yomiage.")
            NanoState.DOWNLOADING -> throw AiException("On-device AI is still downloading.")
            else -> throw AiException("On-device AI isn't available on this phone right now.")
        }
        // The prompt must stay under about 4,000 tokens.
        val p = if (prompt.length > 11_000) prompt.take(11_000) else prompt
        AiHost.hold(app) {
            try {
                val flow = if (image != null) {
                    client().generateContentStream(generateContentRequest(ImagePart(image), TextPart(p)) {})
                } else {
                    client().generateContentStream(p)
                }
                flow.collect { chunk ->
                    if (cancelled()) throw CancellationException("cancelled")
                    val t = chunk.candidates.firstOrNull()?.text
                    if (!t.isNullOrEmpty()) onText(t)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "generation failed", e)
                throw AiException("On-device AI couldn't answer (${e.message ?: e.javaClass.simpleName}).")
            }
        }
    }
}

/**
 * Keeps Yomiage "in front" while on-device AI runs, using a window you can't see or touch, so
 * the page underneath stays visible and usable. Skipped when the Yomiage app itself is open.
 */
object AiHost {
    @Volatile
    var appInFront = false

    private var host: AiHostActivity? = null
    private var ready: CompletableDeferred<Unit>? = null
    private var holds = 0

    suspend fun <T> hold(context: Context, block: suspend () -> T): T {
        if (appInFront) return block()
        val wait = withContext(Dispatchers.Main) {
            holds++
            val r = ready ?: CompletableDeferred<Unit>().also { ready = it }
            if (host == null) {
                try {
                    context.startActivity(
                        Intent(context, AiHostActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                    )
                } catch (e: Exception) {
                    Log.w("AiHost", "couldn't open host", e)
                    r.complete(Unit)
                }
            }
            r
        }
        withTimeoutOrNull(2500) { wait.await() }
        try {
            return block()
        } finally {
            withContext(NonCancellable + Dispatchers.Main) {
                holds--
                if (holds <= 0) {
                    holds = 0
                    host?.finish()
                    host = null
                    ready = null
                }
            }
        }
    }

    internal fun resumed(a: AiHostActivity) {
        host = a
        ready?.complete(Unit)
        if (holds <= 0) a.finish()
    }

    internal fun gone(a: AiHostActivity) {
        if (host === a) host = null
    }
}

class AiHostActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        )
    }

    override fun onResume() {
        super.onResume()
        AiHost.resumed(this)
    }

    override fun onDestroy() {
        AiHost.gone(this)
        super.onDestroy()
    }

    override fun finish() {
        super.finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}
