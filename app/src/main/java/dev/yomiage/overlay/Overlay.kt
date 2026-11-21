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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import dev.yomiage.data.Prefs
import dev.yomiage.data.Settings
import dev.yomiage.reader.Reader
import dev.yomiage.reader.ReaderState
import dev.yomiage.reader.Status
import dev.yomiage.service.ReaderService
import dev.yomiage.service.ScreenScanner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.hypot

/** Owns the floating windows: bubble, its menu, the player bar, tap mode and the highlight. */
class Overlay(private val service: ReaderService) {
    private val wm = service.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())

    private class Win(val view: ComposeView, val params: LayoutParams, val owner: OverlayOwner)

    private var bubble: Win? = null
    private var menu: Win? = null
    private var bar: Win? = null
    private var tap: Win? = null
    private var highlight: Win? = null
    private var menuClosedAt = 0L

    /** Becomes false when the page scrolls, so a stale outline isn't drawn. */
    val highlightValid = MutableStateFlow(true)
    private var lastSession = -1L

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

    /** Shows the bubble when idle and the player while reading. */
    fun update(state: ReaderState, settings: Settings) {
        if (state.session != lastSession) {
            lastSession = state.session
            highlightValid.value = true
        }
        if (state.status != Status.Idle) {
            closeMenu()
            hideBubble()
            showBar()
        } else {
            hideBar()
            if (settings.showBubble && tap == null) showBubble() else hideBubble()
        }
    }

    fun invalidateHighlight() {
        highlightValid.value = false
    }

    fun onConfigurationChanged() {
        highlightValid.value = false
        bubble?.let {
            placeBubble(it.params)
            relayout(it)
        }
        closeMenu()
        bar?.let {
            clampBar(it.params)
            relayout(it)
        }
    }

    fun destroy() {
        main.removeCallbacks(tapTimeout)
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
        val w = add(p) { BubbleContent() }
        w.view.setOnTouchListener(BubbleTouch(w))
        bubble = w
    }

    private fun hideBubble() {
        remove(bubble)
        bubble = null
    }

    private fun placeBubble(p: LayoutParams) {
        val s = ScreenScanner.screenBounds(service)
        val st = Prefs.current
        p.x = if (st.bubbleRight) s.width() - p.width - dp(4) else dp(4)
        p.y = (st.bubbleY * s.height()).toInt().coerceIn(dp(48), (s.height() - p.height - dp(48)).coerceAtLeast(dp(48)))
    }

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
            service.startTapMode()
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
                    main.postDelayed(longPress, 450)
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
                        // Tapping the bubble while the menu is open closes it (the tap counts as "outside").
                        if (SystemClock.uptimeMillis() - menuClosedAt > 500) toggleMenu()
                    }
                }
                MotionEvent.ACTION_CANCEL -> main.removeCallbacks(longPress)
            }
            return true
        }
    }

    // ---------------------------------------------------------------- menu

    private fun toggleMenu() {
        if (menu != null) closeMenu() else showMenu()
    }

    private fun showMenu() {
        val b = bubble ?: return
        val right = Prefs.current.bubbleRight
        val s = ScreenScanner.screenBounds(service)
        val p = params(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH)
        p.gravity = Gravity.TOP or (if (right) Gravity.END else Gravity.START)
        p.x = b.params.width + dp(8)
        p.y = (b.params.y - dp(60)).coerceIn(dp(40), (s.height() - dp(300)).coerceAtLeast(dp(40)))
        val w = add(p) {
            MenuContent(
                onRead = { service.readScreen(ocr = false) },
                onTap = { service.startTapMode() },
                onScan = { service.readScreen(ocr = true) },
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
        remove(menu)
        menu = null
    }

    // ---------------------------------------------------------------- player bar

    private fun showBar() {
        if (highlight == null) {
            val hp = params(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT,
                LayoutParams.FLAG_NOT_TOUCHABLE or LayoutParams.FLAG_LAYOUT_IN_SCREEN or LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            )
            highlight = add(hp) {
                val st by Reader.state.collectAsState()
                val settings by Prefs.settings.collectAsState()
                val valid by highlightValid.collectAsState()
                val show = settings.highlight && valid && st.fromScreen &&
                    (st.status == Status.Playing || st.status == Status.Paused || st.status == Status.Buffering)
                HighlightLayer(if (show) st.bounds?.let { visiblePart(it) } else null)
            }
        }
        if (bar != null) return
        val p = params(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, 0)
        p.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        p.y = dp(Prefs.current.barYdp)
        val w = Win(ComposeView(service), p, OverlayOwner())
        w.owner.attachTo(w.view)
        w.view.setContent {
            val st by Reader.state.collectAsState()
            PlayerBar(
                state = st,
                onPlayPause = { Reader.togglePlay() },
                onSeek = { Reader.seekBy(it) },
                onSpeed = { Reader.setSpeed(it) },
                onTapMode = { service.startTapMode() },
                onClose = { Reader.stop() },
                onOpenApp = {
                    Reader.stop()
                    service.openApp()
                },
                onDrag = { dy ->
                    w.params.y = (w.params.y - dy).toInt()
                    clampBar(w.params)
                    relayout(w)
                },
                onDragEnd = {
                    val d = service.resources.displayMetrics.density
                    Prefs.update { it.copy(barYdp = (w.params.y / d).toInt()) }
                },
            )
        }
        wm.addView(w.view, p)
        bar = w
    }

    private fun clampBar(p: LayoutParams) {
        val s = ScreenScanner.screenBounds(service)
        p.y = p.y.coerceIn(0, (s.height() - dp(260)).coerceAtLeast(0))
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
        if (restore) update(Reader.state.value, Prefs.current)
    }
}
