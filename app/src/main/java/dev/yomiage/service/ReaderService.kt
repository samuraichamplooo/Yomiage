package dev.yomiage.service

import android.accessibilityservice.AccessibilityButtonController
import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Point
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import dev.yomiage.MainActivity
import dev.yomiage.data.Prefs
import dev.yomiage.data.Voices
import dev.yomiage.overlay.Overlay
import dev.yomiage.reader.Reader
import dev.yomiage.reader.Status
import dev.yomiage.reader.TextBlock
import dev.yomiage.tts.KokoroEngine
import dev.yomiage.tts.ModelManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The accessibility service: it reads the text of the app on screen (only when asked),
 * and shows the floating bubble and player.
 */
class ReaderService : AccessibilityService() {
    companion object {
        private const val TAG = "ReaderService"
        @Volatile
        var instance: ReaderService? = null
            private set
        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running
    }

    private var scope: CoroutineScope = MainScope()
    private var overlay: Overlay? = null
    private var scanJob: Job? = null
    private var finishedJob: Job? = null
    private val buttonCallback = object : AccessibilityButtonController.AccessibilityButtonCallback() {
        override fun onClicked(controller: AccessibilityButtonController) {
            if (Reader.state.value.status == Status.Idle) readScreen(ocr = false) else Reader.togglePlay()
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        _running.value = true
        scope.cancel()
        scope = MainScope()
        val o = Overlay(this)
        overlay = o
        try {
            accessibilityButtonController.registerAccessibilityButtonCallback(buttonCallback)
        } catch (e: Exception) {
            Log.w(TAG, "accessibility button unavailable", e)
        }
        scope.launch {
            combine(Reader.state, Prefs.settings) { st, settings -> st to settings }.collect { (st, settings) ->
                o.update(st, settings)
                // Tidy up a finished session after a while.
                finishedJob?.cancel()
                if (st.status == Status.Finished || st.status == Status.Error) {
                    val session = st.session
                    finishedJob = launch {
                        delay(if (st.status == Status.Error) 12_000 else 30_000)
                        val now = Reader.state.value
                        if (now.session == session && (now.status == Status.Finished || now.status == Status.Error)) Reader.stop()
                    }
                }
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        if (e.packageName?.toString() == packageName) return
        when (e.eventType) {
            AccessibilityEvent.TYPE_VIEW_SCROLLED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> if (Reader.state.value.fromScreen) overlay?.invalidateHighlight()
        }
    }

    override fun onInterrupt() {
        Reader.pause()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        overlay?.onConfigurationChanged()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        shutdown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        shutdown()
        super.onDestroy()
    }

    private fun shutdown() {
        if (instance == null && overlay == null) return
        try {
            accessibilityButtonController.unregisterAccessibilityButtonCallback(buttonCallback)
        } catch (_: Exception) {
        }
        scope.cancel()
        overlay?.destroy()
        overlay = null
        instance = null
        _running.value = false
        Reader.stop()
    }

    // ---------------------------------------------------------------- actions

    fun warmUp() {
        if (ModelManager.isReady(this)) KokoroEngine.preload(this, Voices.byId(Prefs.current.voiceId))
    }

    fun openApp() {
        startActivity(
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    /** Reads everything on screen from the top. With [ocr], reads the pixels instead of the app's text. */
    fun readScreen(ocr: Boolean) {
        overlay?.closeMenu()
        if (!ModelManager.isReady(this)) {
            Reader.showMessage("Download the Kokoro voices in Yomiage first.")
            return
        }
        warmUp()
        scanJob?.cancel()
        scanJob = scope.launch {
            Reader.showPreparing(if (ocr) "Scanning the screen…" else "Looking at the screen…")
            val settings = Prefs.current
            var blocks = if (ocr) {
                ocrScan()
            } else {
                withContext(Dispatchers.Default) { ScreenScanner.scan(this@ReaderService, settings.readBelow) }
            }
            if (!ocr && blocks.sumOf { it.text.length } < 20 && settings.autoOcr) {
                Reader.showPreparing("Scanning the screen…")
                blocks = ocrScan()
            }
            if (blocks.isEmpty()) {
                Reader.showMessage("I couldn't find any text on this screen.")
                return@launch
            }
            Reader.read(blocks.map { TextBlock(it.text, it.bounds) }, fromScreen = true)
        }
    }

    fun startTapMode() {
        if (!ModelManager.isReady(this)) {
            Reader.showMessage("Download the Kokoro voices in Yomiage first.")
            return
        }
        warmUp()
        overlay?.showTapCatcher { x, y -> readFromTap(x, y) }
    }

    private fun readFromTap(x: Int, y: Int) {
        scanJob?.cancel()
        scanJob = scope.launch {
            delay(80) // let the tap layer disappear before looking at the screen
            val settings = Prefs.current
            var blocks = withContext(Dispatchers.Default) {
                ScreenScanner.scan(this@ReaderService, settings.readBelow, near = Point(x, y))
            }
            var hit = withContext(Dispatchers.Default) { ScreenScanner.locate(blocks, x, y) }
            if (hit == null && settings.autoOcr) {
                Reader.showPreparing("Scanning the screen…")
                blocks = ocrScan()
                hit = ScreenScanner.locate(blocks, x, y)
            }
            val (block, offset) = hit ?: run {
                Reader.showMessage("I couldn't find text there. Try tapping right on the words.")
                return@launch
            }
            Reader.read(
                blocks.map { TextBlock(it.text, it.bounds) },
                startBlock = block, startChar = offset, fromScreen = true,
            )
        }
    }

    private suspend fun ocrScan(): List<ScreenBlock> {
        val o = overlay
        o?.setInvisible(true)
        delay(160) // wait for the hidden windows to leave the screen
        return try {
            OcrScanner.scan(this)
        } finally {
            o?.setInvisible(false)
        }
    }
}
