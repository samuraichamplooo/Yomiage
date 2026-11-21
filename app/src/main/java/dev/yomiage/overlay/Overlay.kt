package dev.yomiage.overlay

import android.annotation.SuppressLint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.WindowManager.LayoutParams
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import dev.yomiage.ai.Explainer
import dev.yomiage.data.BubbleMode
import dev.yomiage.data.Browsers
import dev.yomiage.data.HighlightMode
import dev.yomiage.data.Prefs
import dev.yomiage.data.Settings
import dev.yomiage.reader.Reader
import dev.yomiage.reader.ReaderState
import dev.yomiage.reader.Status
import dev.yomiage.service.FollowAlong
import dev.yomiage.service.ReaderService
import dev.yomiage.service.ScreenScanner
import dev.yomiage.service.lineRects
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.hypot

/** Owns the floating windows: bubble, its menu, the player bar, tap mode and the highlight. */
class Overlay(private val service: ReaderService, private val follow: FollowAlong) {
    private val wm = service.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())

    private class Win(val view: ComposeView, val params: LayoutParams, val owner: OverlayOwner)

    private var bubble: Win? = null
    private var menu: Win? = null
    private var bar: Win? = null
    private var tap: Win? = null
    private var highlight: Win? = null
    private var menuClosedAt = 0L
    private var lastPackage: String? = null

    /** The bubble fades back when it hasn't been touched for a few seconds. */
    private val bubbleFaded = MutableStateFlow(false)
    private val fade = Runnable { bubbleFaded.value = true }

    private fun dp(v: Int): Int = (v * service.resources.displayMetrics.density).toInt()

    private fun params(w: Int, h: Int, flags: Int) = LayoutParams(
        w, h, LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        LayoutParams.FLAG_NOT_FOCUSABLE or flags,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        layoutInDisplayCutoutMode = LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
    }

    private fun add(p: LayoutParams, content: @Composable () -> Unit): Win {
        val owner = OverlayOwner()
        val view = ComposeView(service)
        owner.attachTo(view)
        view.setContent { content() }
        wm.addView(view, p)
        return Win(view, p, owner)
    }

    private fun remove(w: Win?) {
        if (w == null) return
        // Not "immediate": this is often called from a click inside the window being removed.
        try {
            wm.removeView(w.view)
        } catch (_: Exception) {
        }
        main.post { w.owner.destroy() }
    }

    private fun relayout(w: Win) {
        try {
            wm.updateViewLayout(w.view, w.params)
        } catch (_: Exception) {
        }
    }

    // ---------------------------------------------------------------- state

    /** Shows the player while reading; otherwise the bubble, depending on its setting and the app on screen. */
    fun update(state: ReaderState, settings: Settings, foreground: String?) {
        lastPackage = foreground
        if (state.status != Status.Idle) {
            closeMenu()
            hideBubble()
            showBar()
        } else {
            hideBar()
            val wanted = when (settings.bubbleMode) {
                BubbleMode.ALWAYS -> true
                BubbleMode.BROWSERS -> Browsers.isBrowser(foreground) || menu != null
                else -> false
            }
            if (wanted && tap == null) showBubble() else if (menu == null) hideBubble()
        }
    }

    private fun refresh() = update(Reader.state.value, Prefs.current, lastPackage)

    fun onConfigurationChanged() {
        follow.onScreenMoved()
        bubble?.let {
            placeBubble(it.params)
            relayout(it)
        }
        closeMenu()
        bar?.let {
            placeBar(it.params, barMini)
            relayout(it)
        }
    }

    fun destroy() {
        main.removeCallbacks(tapTimeout)
        main.removeCallbacks(fade)
        for (w in listOf(tap, menu, bar, highlight, bubble)) remove(w)
        tap = null
        menu = null
        bar = null
        highlight = null
        bubble = null
    }

    /** Hides every window, e.g. so a screenshot only shows the app underneath. */
    fun setInvisible(invisible: Boolean) {
        val v = if (invisible) View.INVISIBLE else View.VISIBLE
        for (w in listOf(bubble, menu, bar, highlight, tap)) w?.view?.visibility = v
    }

    // ---------------------------------------------------------------- bubble

    private fun showBubble() {
        if (bubble != null) return
        val size = dp(56)
        val p = params(size, size, LayoutParams.FLAG_LAYOUT_NO_LIMITS)
        placeBubble(p)
        val w = add(p) {
            val faded by bubbleFaded.collectAsState()
            BubbleContent(faded)
        }
        w.view.setOnTouchListener(BubbleTouch(w))
        bubble = w
        wake()
    }

    private fun hideBubble() {
        remove(bubble)
        bubble = null
    }

    private fun wake() {
        bubbleFaded.value = false
        main.removeCallbacks(fade)
        main.postDelayed(fade, 3500)
    }

    private fun placeBubble(p: LayoutParams) {
        val s = ScreenScanner.screenBounds(service)
        val st = Prefs.current
        p.x = if (st.bubbleRight) s.width() - p.width - dp(4) else dp(4)
        p.y = (st.bubbleY * s.height()).toInt().coerceIn(dp(48), (s.height() - p.height - dp(48)).coerceAtLeast(dp(48)))
    }

    /** Tap: read the screen. Long-press: menu. Drag: move it to either edge. */
    private inner class BubbleTouch(private val w: Win) : View.OnTouchListener {
        private val slop = ViewConfiguration.get(service).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var dragging = false
        private var longPressed = false
        private val longPress = Runnable {
            longPressed = true
            showMenu()
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    startX = w.params.x
                    startY = w.params.y
                    dragging = false
                    longPressed = false
                    wake()
                    main.postDelayed(longPress, 420)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!dragging && hypot(dx, dy) > slop) {
                        dragging = true
                        main.removeCallbacks(longPress)
                        closeMenu()
                    }
                    if (dragging) {
                        w.params.x = (startX + dx).toInt()
                        w.params.y = (startY + dy).toInt()
                        relayout(w)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    main.removeCallbacks(longPress)
                    if (dragging) {
                        val s = ScreenScanner.screenBounds(service)
                        val right = w.params.x + w.params.width / 2 > s.width() / 2
                        val y = w.params.y.toFloat() / s.height().coerceAtLeast(1)
                        Prefs.update { it.copy(bubbleRight = right, bubbleY = y.coerceIn(0.05f, 0.9f)) }
                        placeBubble(w.params)
                        relayout(w)
                    } else if (!longPressed) {
                        if (menu != null || SystemClock.uptimeMillis() - menuClosedAt < 500) {
                            closeMenu()
                        } else {
                            service.readScreen(ocr = false)
                        }
                    }
                    wake()
                }
                MotionEvent.ACTION_CANCEL -> main.removeCallbacks(longPress)
            }
            return true
        }
    }

    // ---------------------------------------------------------------- menu

    private fun showMenu() {
        if (menu != null) return
        val b = bubble ?: return
        val right = Prefs.current.bubbleRight
        val s = ScreenScanner.screenBounds(service)
        val p = params(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH)
        p.gravity = Gravity.TOP or (if (right) Gravity.END else Gravity.START)
        p.x = b.params.width + dp(8)
        p.y = (b.params.y - dp(60)).coerceIn(dp(40), (s.height() - dp(340)).coerceAtLeast(dp(40)))
        val w = add(p) {
            MenuContent(
                onRead = { service.readScreen(ocr = false, simple = false) },
                onSimple = { service.readScreen(ocr = false, simple = true) },
                onTap = { service.startTapMode() },
                onScan = { service.readScreen(ocr = true, simple = false) },
                onHide = {
                    closeMenu()
                    Prefs.update { it.copy(bubbleMode = BubbleMode.OFF) }
                    Toast.makeText(service, "Bubble hidden. You can turn it back on in Yomiage.", Toast.LENGTH_LONG).show()
                },
                onOpen = {
                    closeMenu()
                    service.openApp()
                },
            )
        }
        w.view.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                closeMenu()
                menuClosedAt = SystemClock.uptimeMillis()
                true
            } else {
                false
            }
        }
        menu = w
        service.warmUp()
    }

    fun closeMenu() {
        if (menu == null) return
        remove(menu)
        menu = null
        wake()
    }

    // ---------------------------------------------------------------- player bar

    private var barMini = false

    private fun placeBar(p: LayoutParams, mini: Boolean) {
        val st = Prefs.current
        if (mini) {
            p.gravity = Gravity.BOTTOM or Gravity.END
            p.x = dp(st.miniXdp)
            p.y = dp(st.miniYdp)
        } else {
            p.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            p.x = 0
            p.y = dp(st.barYdp)
        }
        clampBar(p, mini)
    }

    private fun showBar() {
        if (highlight == null) {
            val hp = params(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT,
                LayoutParams.FLAG_NOT_TOUCHABLE or LayoutParams.FLAG_LAYOUT_IN_SCREEN or LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            )
            highlight = add(hp) { FollowAlongLayer() }
        }
        val existing = bar
        if (existing != null) {
            val mini = Prefs.current.playerMini
            if (mini != barMini) {
                barMini = mini
                placeBar(existing.params, mini)
                relayout(existing)
            }
            return
        }
        barMini = Prefs.current.playerMini
        val p = params(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, 0)
        placeBar(p, barMini)
        val w = Win(ComposeView(service), p, OverlayOwner())
        w.owner.attachTo(w.view)
        val actions = PlayerActions(
            onOpenApp = {
                Reader.stop()
                service.openApp()
            },
            onTapMode = { service.startTapMode() },
            onExplain = { service.explainCurrent() },
            onBackToPage = { Explainer.backToPage() },
            onMinimize = { Prefs.update { it.copy(playerMini = true) } },
            onExpand = { Prefs.update { it.copy(playerMini = false) } },
            onToggleText = { Prefs.update { it.copy(showText = !it.showText) } },
            onDrag = { dx, dy ->
                if (barMini) w.params.x = (w.params.x - dx).toInt()
                w.params.y = (w.params.y - dy).toInt()
                clampBar(w.params, barMini)
                relayout(w)
            },
            onDragEnd = {
                val d = service.resources.displayMetrics.density
                if (barMini) {
                    Prefs.update { it.copy(miniXdp = (w.params.x / d).toInt(), miniYdp = (w.params.y / d).toInt()) }
                } else {
                    Prefs.update { it.copy(barYdp = (w.params.y / d).toInt()) }
                }
            },
        )
        w.view.setContent {
            val st by Reader.state.collectAsState()
            val progress by Reader.progress.collectAsState()
            val settings by Prefs.settings.collectAsState()
            if (settings.playerMini) {
                MiniPlayer(st, actions)
            } else {
                PlayerBar(st, progress, actions, showText = settings.showText)
            }
        }
        wm.addView(w.view, p)
        bar = w
    }

    /** Highlights the sentence and the word being read, or outlines the paragraph. */
    @Composable
    private fun FollowAlongLayer() {
        val st by Reader.state.collectAsState()
        val settings by Prefs.settings.collectAsState()
        val geo by follow.geometry.collectAsState()
        val progress by Reader.progress.collectAsState()
        val speaking = st.status == Status.Playing || st.status == Status.Paused || st.status == Status.Buffering
        val g = geo
        if (settings.highlightMode == HighlightMode.OFF || !st.fromScreen || !speaking || g == null ||
            g.session != st.session || g.chunkIndex != st.chunkIndex
        ) {
            HighlightLayer(emptyList(), emptyList(), null)
            return
        }
        val chars = g.chars
        if (chars == null) {
            HighlightLayer(emptyList(), emptyList(), g.fallback?.let { visiblePart(it) })
            return
        }
        val sentence = lineRects(chars, 0, chars.size)
        val word = if (settings.highlightMode == HighlightMode.WORD &&
            progress.session == st.session && progress.chunkIndex == st.chunkIndex && progress.wordStart >= 0
        ) {
            lineRects(chars, progress.wordStart, progress.wordEnd)
        } else {
            emptyList()
        }
        HighlightLayer(sentence, word, null)
    }

    private fun clampBar(p: LayoutParams, mini: Boolean) {
        val s = ScreenScanner.screenBounds(service)
        if (mini) {
            p.x = p.x.coerceIn(0, (s.width() - dp(150)).coerceAtLeast(0))
            p.y = p.y.coerceIn(0, (s.height() - dp(90)).coerceAtLeast(0))
        } else {
            p.y = p.y.coerceIn(0, (s.height() - dp(260)).coerceAtLeast(0))
        }
    }

    private fun hideBar() {
        remove(bar)
        bar = null
        remove(highlight)
        highlight = null
    }

    private fun visiblePart(r: Rect): Rect? {
        val s = ScreenScanner.screenBounds(service)
        val out = Rect(r)
        return if (out.intersect(s)) out else null
    }

    // ---------------------------------------------------------------- tap mode

    private val tapTimeout = Runnable { hideTapCatcher() }

    fun showTapCatcher(onTap: (Int, Int) -> Unit) {
        closeMenu()
        if (tap != null) return
        hideBubble()
        val p = params(
            LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT,
            LayoutParams.FLAG_LAYOUT_IN_SCREEN or LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        )
        tap = add(p) {
            TapCatcher(
                onTap = { x, y ->
                    hideTapCatcher(restore = false)
                    onTap(x, y)
                },
                onCancel = { hideTapCatcher() },
            )
        }
        main.removeCallbacks(tapTimeout)
        main.postDelayed(tapTimeout, 20_000)
    }

    fun hideTapCatcher(restore: Boolean = true) {
        main.removeCallbacks(tapTimeout)
        if (tap == null) return
        remove(tap)
        tap = null
        if (restore) refresh()
    }
}
