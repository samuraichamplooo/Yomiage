package dev.yomiage.service

import android.graphics.Rect
import android.graphics.RectF
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import android.os.SystemClock
import dev.yomiage.reader.BlockKind
import dev.yomiage.reader.Chunk
import dev.yomiage.reader.ReaderState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.min

/**
 * Where the sentence being read is on screen. [chars] has one rectangle (screen pixels) per
 * character of the chunk's text when the app shares character positions; otherwise [fallback]
 * outlines the text it came from.
 */
class Geometry(val session: Long, val chunkIndex: Int, val chars: Array<RectF?>?, val fallback: Rect?)

/**
 * Finds the sentence and word being read on screen so they can be highlighted, and scrolls the
 * page so what's being read stays in view (unless you've just scrolled it yourself).
 */
class FollowAlong(
    private val scope: CoroutineScope,
    private val screen: () -> Rect,
    /** Whether scrolling along is allowed right now (setting on, the page is in front, playing). */
    private val mayScroll: () -> Boolean,
) {
    private companion object {
        const val TAG = "FollowAlong"
        const val HANDS_OFF_MS = 7_000L   // after you scroll the page yourself
    }

    private var ownScrollAt = 0L
    private var userScrollAt = 0L
    private var scrolledFor = -1      // chunk index the last automatic scroll was for
    private var scrollTries = 0

    private var session = -1L
    private var blocks: List<ScreenBlock> = emptyList()
    private var ocr = false
    private var moved = false
    private var job: Job? = null
    private var chunk: Chunk? = null
    private var chunkIndex = -1

    private val _geometry = MutableStateFlow<Geometry?>(null)
    val geometry: StateFlow<Geometry?> = _geometry

    /** Remembers which screen text a reading session came from. */
    fun attach(session: Long, blocks: List<ScreenBlock>) {
        job?.cancel()
        this.session = session
        this.blocks = blocks
        ocr = blocks.isNotEmpty() && blocks.all { b -> b.parts.all { it.node == null } }
        moved = false
        chunk = null
        chunkIndex = -1
        scrolledFor = -1
        scrollTries = 0
        _geometry.value = null
    }

    /** More of the page was found (reading continues further down). */
    fun extend(session: Long, more: List<ScreenBlock>) {
        if (session != this.session || more.isEmpty()) return
        blocks = blocks + more
    }

    /** Yomiage is about to scroll the page itself (so it isn't mistaken for you scrolling). */
    fun noteOwnScroll() {
        ownScrollAt = SystemClock.uptimeMillis()
    }

    /** The page scrolled: by us, or by you (then automatic scrolling waits a few seconds). */
    fun onScrolled() {
        val now = SystemClock.uptimeMillis()
        if (now - ownScrollAt > 1_500) userScrollAt = now
        onScreenMoved()
    }

    fun onState(st: ReaderState) {
        if (st.session != session) {
            if (_geometry.value != null) _geometry.value = null
            return
        }
        val c = st.chunk ?: return
        if (st.chunkIndex < 0 || (st.chunkIndex == chunkIndex && chunk === c)) return
        chunk = c
        chunkIndex = st.chunkIndex
        measureLater(0)
    }

    /** The page scrolled or the window changed: positions are stale, so measure again. */
    fun onScreenMoved() {
        if (session < 0 || chunk == null) return
        moved = true
        _geometry.value = null
        measureLater(350)
    }

    private fun measureLater(delayMs: Long) {
        val c = chunk ?: return
        val idx = chunkIndex
        val s = session
        job?.cancel()
        job = scope.launch {
            if (delayMs > 0) delay(delayMs)
            // Text recognized from a screenshot can't follow the page once it moves.
            if (ocr && moved) return@launch
            val block = blocks.getOrNull(c.block) ?: return@launch
            val result = withContext(Dispatchers.Default) {
                try {
                    measure(block, c, moved)
                } catch (e: Exception) {
                    Log.w(TAG, "measure failed", e)
                    null
                }
            } ?: return@launch
            if (s == session && idx == chunkIndex) {
                _geometry.value = Geometry(s, idx, result.first, result.second)
                if (!ocr) keepInView(block, c, idx, result.first, result.second)
            }
        }
    }

    /** Scrolls the page when the sentence being read is off screen or about to go under the player. */
    private fun keepInView(block: ScreenBlock, c: Chunk, idx: Int, chars: Array<RectF?>?, fallback: Rect?) {
        val now = SystemClock.uptimeMillis()
        if (now - userScrollAt < HANDS_OFF_MS || !mayScroll()) return
        if (scrolledFor != idx) {
            scrolledFor = idx
            scrollTries = 0
        }
        if (scrollTries >= 2) return
        val where = RectF()
        chars?.forEach { r -> if (r != null && r.width() > 0f && r.height() > 0f) where.union(r) }
        if (where.isEmpty && fallback != null && !fallback.isEmpty) where.set(fallback)
        val node = firstNode(block, c)
        // Only web pages: in ordinary apps, list rows get reused while scrolling, so following
        // along by scrolling would end up pointing at the wrong text.
        if (node == null || ScreenScanner.role(node) == null) return
        if (where.isEmpty) {
            val r = Rect()
            ScreenScanner.boundsOf(node, r)
            where.set(r)
        }
        if (where.isEmpty) return
        if (now - ownScrollAt < 900) {
            // The page may still be moving from the last scroll: look again once it has settled.
            measureLater(950 - (now - ownScrollAt))
            return
        }
        val sc = screen()
        val zoneTop = sc.top + sc.height() * 0.10f
        val zoneBottom = sc.bottom - sc.height() * 0.26f
        val below = where.top > zoneBottom
        val above = where.bottom < zoneTop
        if (!below && !above) return
        scrollTries++
        val attempt = scrollTries
        noteOwnScroll()
        scope.launch(Dispatchers.Default) {
            try {
                // First try "show this on screen" (web pages center it); if that doesn't move the
                // page (a long paragraph that's already partly visible), page down or up instead.
                val shown = attempt == 1 && node != null &&
                    node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
                if (!shown) {
                    val scroller = scrollerFor(node ?: block.node)
                    scroller?.performAction(
                        if (below) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "scroll failed", e)
            }
        }
    }

    /** The node holding the start of the sentence (or the figure/table itself). */
    private fun firstNode(block: ScreenBlock, c: Chunk): AccessibilityNodeInfo? {
        if (block.kind == BlockKind.FIGURE || block.kind == BlockKind.TABLE) return block.node
        val first = c.map.firstOrNull() ?: return block.parts.firstOrNull()?.node
        return block.parts.firstOrNull { first >= it.start && first < it.start + it.text.length }?.node
            ?: block.parts.firstOrNull()?.node
    }

    private fun measure(block: ScreenBlock, chunk: Chunk, refresh: Boolean): Pair<Array<RectF?>?, Rect?> {
        if (block.kind == BlockKind.FIGURE || block.kind == BlockKind.TABLE || chunk.hold) {
            // A figure or table (or its explanation): outline the whole thing.
            val n = block.node
            val b = if (n != null) freshBounds(n) else null
            return null to (b ?: block.bounds)
        }
        val map = chunk.map
        val out = arrayOfNulls<RectF>(map.size)
        var any = false
        var fallback: Rect? = null
        for (part in block.parts) {
            val pStart = part.start
            val pEnd = part.start + part.text.length
            var first = -1
            var last = -1
            for (i in map.indices) {
                if (map[i] in pStart until pEnd) {
                    if (first < 0) first = i
                    last = i
                }
            }
            if (first < 0) continue
            val lo = map[first] - pStart
            val hi = map[last] - pStart + 1
            val rects = partRects(part, lo, hi)
            if (rects != null) {
                for (i in first..last) {
                    val k = map[i] - pStart - lo
                    if (k in rects.indices) {
                        out[i] = rects[k]
                        if (rects[k] != null) any = true
                    }
                }
            } else {
                // No per-character positions: outline this piece of text instead.
                val b = if (refresh) part.node?.let { freshBounds(it) } else part.bounds
                if (b != null) {
                    val f = fallback
                    if (f == null) fallback = Rect(b) else f.union(b)
                }
            }
        }
        return (if (any) out else null) to fallback
    }

    /** Rectangles for characters [lo, hi) of a part, or null if the app doesn't share them. */
    private fun partRects(part: Part, lo: Int, hi: Int): Array<RectF?>? {
        val node = part.node
        if (node == null) {
            // A line found by text recognition: spread the characters evenly across it.
            val r = part.bounds
            val len = part.text.length.coerceAtLeast(1)
            val w = r.width().toFloat() / len
            return Array(hi - lo) { i ->
                val x = r.left + (lo + i) * w
                RectF(x, r.top.toFloat(), x + w, r.bottom.toFloat())
            }
        }
        val key = AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY
        if (!node.availableExtraData.contains(key)) return null
        val args = Bundle().apply {
            putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_START_INDEX, part.lead + lo)
            putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_LENGTH, hi - lo)
        }
        if (!node.refreshWithExtraData(key, args)) return null
        @Suppress("DEPRECATION")
        val arr = node.extras.getParcelableArray(key) ?: return null
        return Array(hi - lo) { i -> arr.getOrNull(i) as? RectF }
    }

    private fun freshBounds(node: AccessibilityNodeInfo): Rect? = try {
        if (!node.refresh()) null else Rect().also { ScreenScanner.boundsOf(node, it) }
    } catch (e: Exception) {
        null
    }

    /** The scrolling container a node sits in (the web page itself, or a list). */
    private fun scrollerFor(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        var n = node
        var depth = 0
        while (n != null && depth < 60) {
            if (n.isScrollable) return n
            n = try {
                n.parent
            } catch (e: Exception) {
                null
            }
            depth++
        }
        return null
    }
}

/** Joins per-character rectangles [from, to) into one rectangle per line of text. */
fun lineRects(chars: Array<RectF?>, from: Int, to: Int): List<RectF> {
    val out = ArrayList<RectF>()
    var cur: RectF? = null
    for (i in from.coerceAtLeast(0) until to.coerceAtMost(chars.size)) {
        val r = chars[i] ?: continue
        if (r.width() <= 0f || r.height() <= 0f) continue
        val c = cur
        if (c != null && abs(r.centerY() - c.centerY()) < min(r.height(), c.height()) * 0.5f) {
            c.union(r)
        } else {
            val n = RectF(r)
            out.add(n)
            cur = n
        }
    }
    return out
}
