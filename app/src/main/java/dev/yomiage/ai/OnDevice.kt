package dev.yomiage.ai

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import com.google.mlkit.genai.common.DownloadStatus
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

enum class NanoState { UNKNOWN, AVAILABLE, DOWNLOADABLE, DOWNLOADING, UNAVAILABLE }

/**
 * Gemini Nano, running on the phone through ML Kit's Prompt API. Free, private, works offline
 * and needs no key. Android only lets it run while the app is in front, so a see-through,
 * untouchable Yomiage window is opened for the few seconds it's thinking.
 */
object OnDevice {
    private const val TAG = "OnDevice"
    private lateinit var app: Context
    private var model: GenerativeModel? = null

    private val _state = MutableStateFlow(NanoState.UNKNOWN)
    val state: StateFlow<NanoState> = _state

    private val _download = MutableStateFlow<String?>(null)
    val download: StateFlow<String?> = _download

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
        return s
    }

    /** Downloads Gemini Nano (once). Progress goes to [download]. */
    suspend fun setUp() {
        if (refresh() == NanoState.AVAILABLE) return
        try {
            _download.value = "Starting download…"
            client().download().collect { st ->
                when (st) {
                    is DownloadStatus.DownloadStarted -> _download.value = "Downloading…"
                    is DownloadStatus.DownloadProgress ->
                        _download.value = "Downloading… ${st.totalBytesDownloaded / 1_048_576} MB"
                    is DownloadStatus.DownloadCompleted -> _download.value = null
                    is DownloadStatus.DownloadFailed -> _download.value = "Download failed. Try again on Wi-Fi."
                    else -> {}
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.w(TAG, "download failed", e)
            _download.value = "Download failed: ${e.message ?: e.javaClass.simpleName}"
        }
        refresh()
    }

    suspend fun stream(prompt: String, cancelled: () -> Boolean, onText: (String) -> Unit) {
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
                client().generateContentStream(p).collect { chunk ->
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
