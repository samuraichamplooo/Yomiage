package dev.yomiage.service

import android.accessibilityservice.AccessibilityButtonController
import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Point
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
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

        /** Windows that cover the app while a voice assistant or the shade is closing. */
        private val transient = setOf(
            "com.android.systemui",
            "com.google.android.apps.bard",
            "com.google.android.googlequicksearchbox",
            "com.samsung.android.bixby.agent",
        )
    }

    private var scope: CoroutineScope = MainScope()
    private var overlay: Overlay? = null
    private var follow: FollowAlong? = null
    private var scanJob: Job? = null
    private var finishedJob: Job? = null
    private var foregroundJob: Job? = null
    private val foreground = MutableStateFlow<String?>(null)

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
        val f = FollowAlong(scope)
        follow = f
        val o = Overlay(this, f)
        overlay = o
        try {
            accessibilityButtonController.registerAccessibilityButtonCallback(buttonCallback)
        } catch (e: Exception) {
            Log.w(TAG, "accessibility button unavailable", e)
        }
        foreground.value = activeAppPackage()
        scope.launch {
            combine(Reader.state, Prefs.settings, foreground) { st, settings, pkg -> Triple(st, settings, pkg) }
                .collect { (st, settings, pkg) ->
                    o.update(st, settings, pkg)
                    f.onState(st)
                    // Tidy up a finished session after a while.
                    finishedJob?.cancel()
                    if (st.status == Status.Finished || st.status == Status.Error) {
                        val session = st.session
                        finishedJob = launch {
                            delay(if (st.status == Status.Error) 12_000 else 30_000)
                            val now = Reader.state.value
                            if (now.session == session && (now.status == Status.Finished || now.status == Status.Error)) {
                                Reader.stop()
                            }
                        }
                    }
                }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val e = event ?: return
        when (e.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                trackForeground()
                if (e.packageName?.toString() != packageName && Reader.state.value.fromScreen) follow?.onScreenMoved()
            }
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                if (e.packageName?.toString() != packageName && Reader.state.value.fromScreen) follow?.onScreenMoved()
            }
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
        follow = null
        instance = null
        _running.value = false
        Reader.stop()
    }

    // ---------------------------------------------------------------- which app is on screen

    private fun trackForeground() {
        foregroundJob?.cancel()
        foregroundJob = scope.launch {
            delay(250)
            foreground.value = activeAppPackage()
        }
    }

    private fun activeAppPackage(): String? = try {
        val apps = windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        val w = apps.firstOrNull { it.isActive } ?: apps.firstOrNull { it.isFocused }
        (w?.root ?: rootInActiveWindow)?.packageName?.toString()
    } catch (e: Exception) {
        null
    }

    private fun homePackage(): String? = try {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        packageManager.resolveActivity(intent, 0)?.activityInfo?.packageName
    } catch (e: Exception) {
        null
    }

    /** Waits for a voice assistant, the notification shade or our own launcher stub to get out of the way. */
    private suspend fun waitForApp(): String? {
        val until = SystemClock.uptimeMillis() + 3000
        var pkg = activeAppPackage()
        while (SystemClock.uptimeMillis() < until && (pkg == null || pkg in transient || pkg == packageName)) {
            delay(200)
            pkg = activeAppPackage()
        }
        return pkg
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

    /**
     * Reads the app that's on screen once something covering it goes away: used by the
     * "Read My Screen" launcher entry (voice assistant, side button) and the Quick Settings tiles.
     */
    fun readWhenBack(tapMode: Boolean, closeShade: Boolean) {
        if (!ModelManager.isReady(this)) {
            Reader.showMessage("Download the Kokoro voices in Yomiage first.")
            return
        }
        warmUp()
        if (closeShade && Build.VERSION.SDK_INT >= 31) {
            performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE)
        }
        scanJob?.cancel()
        scanJob = scope.launch {
            delay(if (closeShade) 450 else 350)
            val pkg = waitForApp()
            if (pkg != null && pkg == homePackage()) {
                Reader.showMessage("Open the page or app you want read first, then ask again.")
                return@launch
            }
            if (tapMode) startTapMode() else readScreen(ocr = false)
        }
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
            val id = Reader.read(blocks.map { TextBlock(it.text, it.bounds) }, fromScreen = true)
            follow?.attach(id, blocks)
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
            val id = Reader.read(
                blocks.map { TextBlock(it.text, it.bounds) },
                startBlock = block, startChar = offset, fromScreen = true,
            )
            follow?.attach(id, blocks)
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
