package dev.yomiage.service

import android.accessibilityservice.AccessibilityButtonController
import android.accessibilityservice.AccessibilityService
import android.app.ActivityOptions
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Point
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import dev.yomiage.MainActivity
import dev.yomiage.ai.Explainer
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
import kotlinx.coroutines.withTimeoutOrNull
import dev.yomiage.view.ReadingView

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
    private var screenSession = -1L
    private var screenBlocks: List<ScreenBlock> = emptyList()
    // The page being read start to finish (null for other kinds of reading).
    private var page: PageSession? = null
    private var screenPkg: String? = null
    // A page that went off to explain a paragraph and will carry on afterwards.
    private var pageAfterExplain: PageSession? = null

    // The last real app that was on screen (not home, not us, not the shade), and when it stopped being.
    private var lastApp: String? = null
    private var lastAppLeftAt = 0L
    // After a gesture: the app to keep in front if the system jumps to the home screen.
    private var keepApp: String? = null
    private var keepUntil = 0L
    private var keepJob: Job? = null
    private var homeCache: Set<String>? = null

    /** The accessibility button / shortcut works right where you are, like the gesture. */
    private val buttonCallback = object : AccessibilityButtonController.AccessibilityButtonCallback() {
        override fun onClicked(controller: AccessibilityButtonController) {
            if (isReading()) overlay?.togglePlayer() else readScreen(ocr = false)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        _running.value = true
        scope.cancel()
        scope = MainScope()
        val f = FollowAlong(
            scope,
            screen = { ScreenScanner.screenBounds(this) },
            mayScroll = {
                Prefs.current.followScroll && Reader.state.value.status == Status.Playing &&
                    screenPkg != null && activeAppPackage() == screenPkg
            },
        )
        follow = f
        val o = Overlay(this, f)
        overlay = o
        Explainer.onScreenResumed = { id, blocks -> attachScreen(id, blocks) }
        try {
            accessibilityButtonController.registerAccessibilityButtonCallback(buttonCallback)
        } catch (e: Exception) {
            Log.w(TAG, "accessibility button unavailable", e)
        }
        foreground.value = activeAppPackage()
        scope.launch {
            combine(Reader.state, Prefs.settings, foreground, ReadingView.open) { st, settings, pkg, _ -> Triple(st, settings, pkg) }
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
                if (keepApp != null) checkKeepApp()
                trackForeground()
                if (e.packageName?.toString() != packageName && Reader.state.value.fromScreen) follow?.onScreenMoved()
            }
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                if (e.packageName?.toString() != packageName && Reader.state.value.fromScreen) follow?.onScrolled()
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
        Explainer.onScreenResumed = null
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
            val pkg = activeAppPackage()
            foreground.value = pkg
            noteApp(pkg)
        }
    }

    private fun isRealApp(pkg: String?): Boolean =
        pkg != null && pkg !in transient && pkg != packageName && !isHome(pkg)

    private fun noteApp(pkg: String?) {
        when {
            pkg == null || pkg in transient -> Unit
            isRealApp(pkg) -> {
                lastApp = pkg
                lastAppLeftAt = 0L
            }
            // The home screen, or Yomiage's own screen.
            lastAppLeftAt == 0L -> lastAppLeftAt = SystemClock.uptimeMillis()
        }
    }

    private fun isReading(): Boolean {
        val status = Reader.state.value.status
        return status == Status.Playing || status == Status.Paused ||
            status == Status.Buffering || status == Status.Preparing
    }

    /**
     * The app you were in when a gesture / shortcut fired. If the home screen is showing, it still
     * counts when it only just appeared (some gesture apps go home on their own before launching us).
     */
    private fun appBeforeGesture(): String? {
        val now = activeAppPackage()
        if (isRealApp(now)) return now
        val last = lastApp ?: return null
        return if (lastAppLeftAt == 0L || SystemClock.uptimeMillis() - lastAppLeftAt < 2_000) last else null
    }

    /**
     * Our gesture entry is an invisible activity. When it closes, some launchers (Good Lock's gestures
     * among them) send you to the home screen instead of back to the page. For a short while after a
     * gesture, if the home screen shows up, this puts the app you were in straight back.
     */
    private fun keepAppInFront(pkg: String?, alreadyBroughtBack: Boolean = false) {
        keepJob?.cancel()
        keepApp = null
        if (pkg == null) return
        keepUntil = SystemClock.uptimeMillis() + 1_800
        keepJob = scope.launch {
            // Give an app we just brought back a moment to come up before checking again.
            if (alreadyBroughtBack) delay(500)
            keepApp = pkg
            while (keepApp != null && SystemClock.uptimeMillis() < keepUntil) {
                checkKeepApp()
                delay(60)
            }
            keepApp = null
        }
    }

    private fun checkKeepApp() {
        val app = keepApp ?: return
        if (SystemClock.uptimeMillis() > keepUntil) {
            keepApp = null
            return
        }
        val now = activeAppPackage()
        if (isHome(now)) {
            keepApp = null
            keepJob?.cancel()
            bringBack(app)
        }
    }

    /** Brings an app's existing screen back to the front, the same way tapping its icon does. */
    private fun bringBack(pkg: String): Boolean {
        val intent = try {
            packageManager.getLaunchIntentForPackage(pkg)
        } catch (e: Exception) {
            null
        } ?: return false
        intent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or
                Intent.FLAG_ACTIVITY_NO_ANIMATION
        )
        return try {
            startActivity(intent, ActivityOptions.makeCustomAnimation(this, 0, 0).toBundle())
            true
        } catch (e: Exception) {
            Log.w(TAG, "couldn't bring $pkg back", e)
            false
        }
    }

    /** The app on screen right now. */
    fun frontPackage(): String? = activeAppPackage()

    /** A screenshot without Yomiage's own bubble, player and highlight on it. */
    suspend fun captureScreen(): android.graphics.Bitmap? {
        val o = overlay
        o?.setInvisible(true)
        delay(160)
        return try {
            OcrScanner.screenshot(this)
        } finally {
            o?.setInvisible(false)
        }
    }

    /**
     * Opens the reading view: the text being read, laid out cleanly, following along. For a page,
     * the rest of it is gathered first, while the page is still on screen to be looked at.
     */
    fun openReadingView() {
        val p = page
        if (p != null && p.id == Reader.state.value.session && screenPkg != null && activeAppPackage() == screenPkg) {
            scope.launch {
                withTimeoutOrNull(6_000) { p.loadNow() }
                launchReadingView()
            }
        } else {
            launchReadingView()
        }
    }

    /** Leaving the reading view: make sure it's the page that comes back, not the home screen. */
    fun keepPageInFront() {
        keepAppInFront(screenPkg)
    }

    private fun launchReadingView() {
        try {
            startActivity(
                Intent(this, dev.yomiage.view.ReadingViewActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            )
        } catch (e: Exception) {
            Log.w(TAG, "couldn't open the reading view", e)
        }
    }

    private fun activeAppPackage(): String? = try {
        val apps = windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        val w = apps.firstOrNull { it.isActive } ?: apps.firstOrNull { it.isFocused }
        (w?.root ?: rootInActiveWindow)?.packageName?.toString()
    } catch (e: Exception) {
        null
    }

    /** Home screen apps (One UI Home, or another launcher you've set). */
    private fun homePackages(): Set<String> {
        homeCache?.let { return it }
        return try {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            val found = packageManager.queryIntentActivities(intent, 0)
                .mapNotNull { it.activityInfo?.packageName }
                // Settings has a fallback "home" used only while the phone boots.
                .filter { it != "android" && it != packageName && it != "com.android.settings" && it != "com.android.provision" }
                .toSet()
            if (found.isNotEmpty()) homeCache = found
            found
        } catch (e: Exception) {
            emptySet()
        }
    }

    private fun isHome(pkg: String?): Boolean = pkg != null && pkg in homePackages()

    /**
     * Waits for a voice assistant, the notification shade or our own launcher stub to get out of the way.
     * With [expect], a home screen that pops up is also waited out while that app is brought back.
     */
    private suspend fun waitForApp(expect: String? = null): String? {
        val until = SystemClock.uptimeMillis() + 3000
        var pkg = activeAppPackage()
        while (SystemClock.uptimeMillis() < until &&
            (pkg == null || pkg in transient || pkg == packageName || (expect != null && isHome(pkg)))
        ) {
            delay(150)
            pkg = activeAppPackage()
        }
        return pkg
    }

    // ---------------------------------------------------------------- actions

    fun warmUp() {
        if (ModelManager.isReady(this)) KokoroEngine.preload(this, Voices.byId(Prefs.current.voiceId))
    }

    /**
     * Read My Screen / Tap to Read / a Good Lock gesture. While something is being read, it shows the
     * player (or hides it); otherwise it starts reading the app underneath. Either way you stay in
     * that app. With [homeNext], the launcher has said it'll show the home screen once our invisible
     * stub closes, so the app is put back in front right away.
     */
    fun gesture(tapMode: Boolean, homeNext: Boolean = false) {
        val app = appBeforeGesture()
        val broughtBack = homeNext && app != null && bringBack(app)
        keepAppInFront(app, alreadyBroughtBack = broughtBack)
        if (isReading() && !tapMode) {
            overlay?.togglePlayer()
            return
        }
        readWhenBack(tapMode = tapMode, closeShade = false, expect = app)
    }

    /** Shows the player again after it was hidden (tapping the reading controls in the quick panel). */
    fun revealPlayer(fromShade: Boolean = false) {
        if (fromShade) keepAppInFront(appBeforeGesture())
        overlay?.revealPlayer()
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
    fun readWhenBack(tapMode: Boolean, closeShade: Boolean, expect: String? = null) {
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
            val pkg = waitForApp(expect)
            if (isHome(pkg)) {
                Reader.showMessage("Open the page or app you want read first, then ask again.")
                return@launch
            }
            if (tapMode) startTapMode() else readScreen(ocr = false)
        }
    }

    private fun attachScreen(id: Long, blocks: List<ScreenBlock>) {
        screenSession = id
        screenBlocks = blocks
        follow?.attach(id, blocks)
        // Back from explaining a paragraph: the page carries on reading to the end.
        pageAfterExplain?.let { p ->
            pageAfterExplain = null
            if (page === p) p.adopt(id)
        }
    }

    /** Explains the paragraph being read in plain language, then carries on with the page. */
    fun explainCurrent() {
        val st = Reader.state.value
        val p = page?.takeIf { it.id == st.session }
        pageAfterExplain = p
        val blocks = p?.blocks() ?: screenBlocks.takeIf { st.session == screenSession }
        Explainer.explainCurrent(if (st.fromScreen) blocks else null)
    }

    /**
     * Reads screen blocks as a page: from [startBlock]/[startChar] on, and (with "read the whole
     * page" on) on past the end of the screen until the page ends, scrolling along.
     */
    private fun startPage(blocks: List<ScreenBlock>, startBlock: Int, startChar: Int, pkg: String?, view: Boolean = false) {
        page?.stop()
        val p = PageSession(this, scope, follow ?: return, pkg)
        page = p
        screenPkg = pkg
        val id = p.start(blocks, startBlock, startChar, more = Prefs.current.readBelow)
        attachScreen(id, p.blocks())
        if (view || Prefs.current.openView) openReadingView()
    }

    /**
     * Reads everything on screen from the top. With [ocr], reads the pixels instead of the app's text.
     * With [simple], Gemini rewrites it in plain language first.
     */
    fun readScreen(
        ocr: Boolean,
        simple: Boolean = Prefs.current.alwaysSimple && Explainer.hasKey(),
        /** Open the reading view once reading starts. */
        view: Boolean = false,
    ) {
        overlay?.closeMenu()
        if (!ModelManager.isReady(this)) {
            Reader.showMessage("Download the Kokoro voices in Yomiage first.")
            return
        }
        warmUp()
        scanJob?.cancel()
        page?.stop()
        scanJob = scope.launch {
            Reader.showPreparing(if (ocr) "Scanning the screen…" else "Looking at the screen…")
            val settings = Prefs.current
            val pkg = activeAppPackage()
            var blocks = if (ocr) {
                ocrScan()
            } else {
                // A few screens to start with, so reading begins quickly; the rest comes as it reads.
                withContext(Dispatchers.Default) {
                    ScreenScanner.scanPage(this@ReaderService, settings.readBelow, screensBelow = 3).blocks
                }
            }
            val fromOcr = ocr || blocks.sumOf { it.text.length } < 20
            if (!ocr && blocks.sumOf { it.text.length } < 20 && settings.autoOcr) {
                Reader.showPreparing("Scanning the screen…")
                blocks = ocrScan()
            }
            if (blocks.isEmpty()) {
                Reader.showMessage("I couldn't find any text on this screen.")
                return@launch
            }
            if (simple) {
                val original = blocks
                Explainer.readSimplified(blocks.map { TextBlock(it.text) }) {
                    val id = Reader.read(original.map { TextBlock(it.text, it.bounds) }, fromScreen = true)
                    attachScreen(id, original)
                }
            } else if (fromOcr) {
                val id = Reader.read(blocks.map { TextBlock(it.text, it.bounds) }, fromScreen = true)
                screenPkg = null
                attachScreen(id, blocks)
            } else {
                startPage(blocks, 0, 0, pkg, view)
            }
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
        page?.stop()
        scanJob = scope.launch {
            delay(80) // let the tap layer disappear before looking at the screen
            val settings = Prefs.current
            val pkg = activeAppPackage()
            var blocks = withContext(Dispatchers.Default) {
                ScreenScanner.scanPage(this@ReaderService, settings.readBelow, near = Point(x, y), screensBelow = 3).blocks
            }
            var hit = withContext(Dispatchers.Default) { ScreenScanner.locate(blocks, x, y) }
            var ocr = false
            if (hit == null && settings.autoOcr) {
                Reader.showPreparing("Scanning the screen…")
                blocks = ocrScan()
                hit = ScreenScanner.locate(blocks, x, y)
                ocr = true
            }
            val (block, offset) = hit ?: run {
                Reader.showMessage("I couldn't find text there. Try tapping right on the words.")
                return@launch
            }
            if (ocr) {
                val id = Reader.read(
                    blocks.map { TextBlock(it.text, it.bounds) },
                    startBlock = block, startChar = offset, fromScreen = true,
                )
                screenPkg = null
                attachScreen(id, blocks)
            } else {
                startPage(blocks, block, offset, pkg)
            }
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
