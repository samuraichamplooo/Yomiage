package dev.yomiage.service

import android.graphics.Rect
import android.graphics.RectF
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
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

/** Finds the sentence and word being read on screen so they can be highlighted. */
class FollowAlong(private val scope: CoroutineScope) {
    private companion object {
        const val TAG = "FollowAlong"
    }

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
        _geometry.value = null
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
            if (s == session && idx == chunkIndex) _geometry.value = Geometry(s, idx, result.first, result.second)
        }
    }

    private fun measure(block: ScreenBlock, chunk: Chunk, refresh: Boolean): Pair<Array<RectF?>?, Rect?> {
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
                val b = if (refresh) freshBounds(part) else part.bounds
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

    private fun freshBounds(part: Part): Rect? {
        val node = part.node ?: return null
        return try {
            if (!node.refresh()) return null
            Rect().also { node.getBoundsInScreen(it) }
        } catch (e: Exception) {
            null
        }
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
