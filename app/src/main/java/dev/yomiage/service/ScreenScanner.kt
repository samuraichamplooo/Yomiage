package dev.yomiage.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Point
import android.graphics.Rect
import android.graphics.RectF
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** A piece of a block: one text node (or one OCR line) and where it sits in the block's text. */
class Part(
    val start: Int,
    val text: String,
    val bounds: Rect,
    val node: AccessibilityNodeInfo?,
    /** How many characters were trimmed off the front of the node's text. */
    val lead: Int = 0,
)

class ScreenBlock(val text: String, val bounds: Rect, val parts: List<Part>)

object ScreenScanner {
    private const val TAG = "ScreenScanner"
    private const val MAX_NODES = 8000

    fun screenBounds(context: Context): Rect = try {
        context.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
    } catch (e: Exception) {
        val dm = context.resources.displayMetrics
        Rect(0, 0, dm.widthPixels, dm.heightPixels)
    }

    /**
     * Collects the text of the app on screen in reading order, merged into paragraphs.
     * With [includeBelow], text the app shares that is below the visible area (long web pages)
     * is included too, so reading can continue past the bottom of the screen.
     */
    fun scan(service: AccessibilityService, includeBelow: Boolean, near: Point? = null): List<ScreenBlock> {
        val screen = screenBounds(service)
        val root = pickRoot(service, near) ?: return emptyList()
        val items = ArrayList<Item>()
        val budget = intArrayOf(MAX_NODES)
        val counter = intArrayOf(1)
        try {
            walk(root, 0, intArrayOf(0, -1, -2), items, screen, includeBelow, budget, counter)
        } catch (e: Exception) {
            Log.w(TAG, "walk failed", e)
        }
        return merge(items)
    }

    private fun pickRoot(service: AccessibilityService, near: Point?): AccessibilityNodeInfo? {
        val windows = try {
            service.windows
        } catch (e: Exception) {
            emptyList()
        }
        val apps = windows.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        val window = if (near != null) {
            apps.sortedByDescending { it.layer }.firstOrNull { w ->
                val r = Rect()
                w.getBoundsInScreen(r)
                r.contains(near.x, near.y)
            }
        } else {
            apps.firstOrNull { it.isActive } ?: apps.firstOrNull { it.isFocused }
                ?: apps.maxByOrNull { w ->
                    val r = Rect()
                    w.getBoundsInScreen(r)
                    r.width().toLong() * r.height()
                }
        }
        return window?.root ?: service.rootInActiveWindow
    }

    private class Item(
        val text: String,
        val bounds: Rect,
        val node: AccessibilityNodeInfo,
        val lead: Int,
        /** Ids of the parent, grandparent and great-grandparent in the walk. */
        val anc: IntArray,
    )

    private fun walk(
        node: AccessibilityNodeInfo,
        depth: Int,
        anc: IntArray,
        out: MutableList<Item>,
        screen: Rect,
        below: Boolean,
        budget: IntArray,
        counter: IntArray,
    ) {
        if (budget[0]-- <= 0 || depth > 80) return
        val r = Rect()
        node.getBoundsInScreen(r)
        val visible = node.isVisibleToUser
        // Off-screen text is only kept when it's further down the page (not menus hidden to the side).
        val belowOk = below && r.top >= screen.top && r.left < screen.right && r.right > screen.left
        if (!visible && !belowOk) return
        if (node.isPassword) return

        val raw = node.text?.toString()
        val trimmed = raw?.trim()
        val childCount = node.childCount
        if (raw != null && !trimmed.isNullOrEmpty() && r.width() > 1 && r.height() > 1) {
            val lead = raw.length - raw.trimStart().length
            val own = Item(trimmed, r, node, lead, anc)
            if (childCount == 0) {
                out.add(own)
                return
            }
            // Usually a node's text already includes its children's (a link made of styled spans).
            // But a container can carry just a label, like a web page's title, while its children hold
            // the real content, so use the children when they have most of the text.
            val sub = ArrayList<Item>()
            val id = counter[0]++
            val childAnc = intArrayOf(id, anc[0], anc[1])
            for (i in 0 until childCount) {
                val child = try {
                    node.getChild(i)
                } catch (e: Exception) {
                    null
                } ?: continue
                walk(child, depth + 1, childAnc, sub, screen, below, budget, counter)
            }
            val subLen = sub.sumOf { it.text.length }
            if (sub.isNotEmpty() && subLen >= trimmed.length / 2) out.addAll(sub) else out.add(own)
            return
        }
        if (childCount == 0) {
            // Some apps (Flutter, some feeds) only expose text as a description. Use it when it reads
            // like a sentence rather than a button label.
            val desc = node.contentDescription?.toString()?.trim()
            if (desc != null && desc.length >= 25 && desc.contains(' ') && r.width() > 1 && r.height() > 1) {
                out.add(Item(desc, r, node, 0, anc))
            }
            return
        }
        val id = counter[0]++
        val childAnc = intArrayOf(id, anc[0], anc[1])
        for (i in 0 until childCount) {
            val child = try {
                node.getChild(i)
            } catch (e: Exception) {
                null
            } ?: continue
            walk(child, depth + 1, childAnc, out, screen, below, budget, counter)
        }
    }

    private fun related(a: IntArray, b: IntArray): Boolean =
        a[0] == b[0] || a[0] == b[1] || a[1] == b[0] || (a[1] == b[1] && a[1] > 0)

    /** Joins inline pieces (a link inside a paragraph, runs of styled text) into one paragraph. */
    private fun merge(items: List<Item>): List<ScreenBlock> {
        val blocks = ArrayList<ScreenBlock>()
        var text = StringBuilder()
        var parts = ArrayList<Part>()
        var bounds = Rect()
        var last: Item? = null

        fun flush() {
            if (parts.isNotEmpty()) blocks.add(ScreenBlock(text.toString(), Rect(bounds), parts))
            text = StringBuilder()
            parts = ArrayList()
            bounds = Rect()
        }

        for (item in items) {
            val prev = last
            val sameLine = prev != null &&
                related(prev.anc, item.anc) &&
                item.bounds.top < prev.bounds.bottom - min(prev.bounds.height(), item.bounds.height()) * 0.3f &&
                item.bounds.bottom > prev.bounds.top
            if (!sameLine) flush()
            if (text.isNotEmpty() && needsSpace(text, item.text)) text.append(' ')
            parts.add(Part(text.length, item.text, item.bounds, item.node, item.lead))
            text.append(item.text)
            if (bounds.isEmpty) bounds.set(item.bounds) else bounds.union(item.bounds)
            last = item
        }
        flush()
        return blocks
    }

    private fun needsSpace(sb: StringBuilder, next: String): Boolean {
        val a = sb[sb.length - 1]
        val b = next[0]
        if (a.isWhitespace() || b.isWhitespace()) return false
        if (b in ".,;:!?)]}%'’”") return false
        if (a in "([{“‘") return false
        return true
    }

    /**
     * Finds what was tapped: the block index and the character offset within that block's text.
     * Uses the app's per-character positions when it shares them, otherwise estimates.
     */
    fun locate(blocks: List<ScreenBlock>, x: Int, y: Int): Pair<Int, Int>? {
        var bestBlock = -1
        var bestPart: Part? = null
        var bestScore = Double.MAX_VALUE
        for ((bi, b) in blocks.withIndex()) {
            for (p in b.parts) {
                val r = p.bounds
                val score = if (r.contains(x, y)) {
                    // Inside: prefer the smallest box (the most specific text).
                    r.width().toDouble() * r.height() / 1e9
                } else {
                    val dx = max(max(r.left - x, 0), x - r.right).toDouble()
                    val dy = max(max(r.top - y, 0), y - r.bottom).toDouble()
                    val d = hypot(dx, dy)
                    if (d > 160) continue
                    1.0 + d
                }
                if (score < bestScore) {
                    bestScore = score
                    bestBlock = bi
                    bestPart = p
                }
            }
        }
        val part = bestPart ?: return null
        val inPart = charIndex(part, x, y).coerceIn(0, max(0, part.text.length - 1))
        return bestBlock to (part.start + inPart)
    }

    private fun charIndex(part: Part, x: Int, y: Int): Int {
        val node = part.node
        if (node != null) {
            try {
                val key = AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY
                if (node.availableExtraData.contains(key)) {
                    val total = part.lead + part.text.length
                    val args = Bundle().apply {
                        putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_START_INDEX, 0)
                        putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_LENGTH, min(total, 20000))
                    }
                    if (node.refreshWithExtraData(key, args)) {
                        @Suppress("DEPRECATION")
                        val rects = node.extras.getParcelableArray(key)
                        if (rects != null) {
                            var best = -1
                            var bestD = Float.MAX_VALUE
                            for (i in rects.indices) {
                                val rf = rects[i] as? RectF ?: continue
                                if (rf.contains(x.toFloat(), y.toFloat())) {
                                    best = i
                                    break
                                }
                                // Same line counts much more than distance across lines.
                                val dy = if (y < rf.top) rf.top - y else if (y > rf.bottom) y - rf.bottom else 0f
                                val dx = if (x < rf.left) rf.left - x else if (x > rf.right) x - rf.right else 0f
                                val d = dy * 10 + dx
                                if (d < bestD) {
                                    bestD = d
                                    best = i
                                }
                            }
                            if (best >= 0) return best - part.lead
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "character positions unavailable", e)
            }
        }
        // Estimate from where in the box the tap landed (good enough to find the sentence).
        val r = part.bounds
        if (r.height() <= 0 || r.width() <= 0) return 0
        val lineH = estimateLineHeight(part)
        val lines = max(1, (r.height() / lineH).toInt())
        val line = ((y - r.top) / lineH).toInt().coerceIn(0, lines - 1)
        val fx = ((x - r.left).toFloat() / r.width()).coerceIn(0f, 1f)
        val pos = (line + fx) / lines
        return (pos * part.text.length).toInt()
    }

    private fun estimateLineHeight(part: Part): Float {
        val r = part.bounds
        // Roughly: characters per line ~ width / (0.5 * line height).
        val area = r.width().toFloat() * r.height()
        val perChar = area / max(1, part.text.length)
        val h = kotlin.math.sqrt(perChar * 2.2f)
        return h.coerceIn(20f, max(20f, r.height().toFloat()))
    }
}
