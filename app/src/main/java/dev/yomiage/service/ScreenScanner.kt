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
import dev.yomiage.data.Prefs
import dev.yomiage.reader.BlockKind
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
    /** Part of a link. */
    val link: Boolean = false,
)

/**
 * A paragraph (or a figure, or a table) found on screen. [node] is the figure's or table's own
 * node, used to look at the picture and to outline it while it's explained.
 */
class ScreenBlock(
    val text: String,
    val bounds: Rect,
    val parts: List<Part>,
    val kind: Int = BlockKind.TEXT,
    val node: AccessibilityNodeInfo? = null,
)

/** What a scan found. [cut] means it stopped early (long page) and there's more further down. */
class ScanResult(val blocks: List<ScreenBlock>, val cut: Boolean, val web: Boolean)

object ScreenScanner {
    private const val TAG = "ScreenScanner"
    private const val MAX_NODES = 40_000

    // Extras Chrome (and WebView) put on web page nodes.
    private const val ROLE = "AccessibilityNodeInfo.chromeRole"
    private const val UNCLIPPED_TOP = "AccessibilityNodeInfo.unclippedTop"
    private const val UNCLIPPED_BOTTOM = "AccessibilityNodeInfo.unclippedBottom"
    private const val UNCLIPPED_LEFT = "AccessibilityNodeInfo.unclippedLeft"
    private const val UNCLIPPED_RIGHT = "AccessibilityNodeInfo.unclippedRight"

    // Parts of a web page that aren't the reading: site menus, headers, footers, sidebars, controls.
    private val skipRoles = setOf(
        "navigation", "banner", "contentInfo", "complementary", "search", "searchBox",
        "toolbar", "menu", "menuBar", "menuListPopup", "menuItem", "tabList", "tab", "tooltip",
        "button", "popUpButton", "toggleButton", "comboBoxSelect", "comboBoxMenuButton", "textField",
        "textFieldWithComboBox", "checkBox", "radioButton", "switch", "slider", "spinButton",
        "scrollBar", "splitter", "alertDialog", "dialog", "progressIndicator", "meter",
    )
    private val figureRoles = setOf("image", "svgRoot", "canvas", "graphicsDocument")
    private val tableRoles = setOf("table", "grid", "treeGrid")
    private val cellRoles = setOf("cell", "gridCell", "columnHeader", "rowHeader")
    private val pageMarker = Regex("""^(page|p\.|pg\.?)\s*[0-9ivxlc]+[a-z]?$""", RegexOption.IGNORE_CASE)
    private val mathLike = Regex("""[0-9=+−×÷^√∑∫<>≤≥±]""")

    fun screenBounds(context: Context): Rect = try {
        context.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
    } catch (e: Exception) {
        val dm = context.resources.displayMetrics
        Rect(0, 0, dm.widthPixels, dm.heightPixels)
    }

    /** A node's chrome role on web pages ("main", "heading", "image"…), or null in ordinary apps. */
    fun role(node: AccessibilityNodeInfo): String? = try {
        node.extras?.getCharSequence(ROLE)?.toString()
    } catch (e: Exception) {
        null
    }

    /**
     * Where a node really is. Web pages report text that's scrolled off screen squashed against the
     * edge of the screen; Chrome also shares the real position, which is what reading below the
     * screen and scrolling along need.
     */
    fun boundsOf(node: AccessibilityNodeInfo, out: Rect) {
        node.getBoundsInScreen(out)
        val ex = try {
            node.extras
        } catch (e: Exception) {
            null
        } ?: return
        if (!ex.containsKey(UNCLIPPED_TOP)) return
        val t = ex.getInt(UNCLIPPED_TOP, Int.MIN_VALUE)
        val b = ex.getInt(UNCLIPPED_BOTTOM, Int.MIN_VALUE)
        val l = ex.getInt(UNCLIPPED_LEFT, Int.MIN_VALUE)
        val r = ex.getInt(UNCLIPPED_RIGHT, Int.MIN_VALUE)
        if (t != Int.MIN_VALUE && b > t && l != Int.MIN_VALUE && r > l) out.set(l, t, r, b)
    }

    /** The plain-list version, for callers that only need the blocks. */
    fun scan(service: AccessibilityService, includeBelow: Boolean, near: Point? = null): List<ScreenBlock> =
        scanPage(service, includeBelow, near).blocks

    /**
     * Collects the text of the app on screen in reading order, merged into paragraphs.
     * With [includeBelow], text below the visible area (the rest of a long web page) is included.
     * [screensBelow] limits how far down to look, so reading can start quickly on a long page;
     * the rest is picked up later ([ScanResult.cut]). On web pages, with smart reading on, site
     * menus, toolbars, footers and page numbers are left out, tables are read row by row and big
     * pictures become figure blocks.
     */
    fun scanPage(
        service: AccessibilityService,
        includeBelow: Boolean,
        near: Point? = null,
        screensBelow: Int = Int.MAX_VALUE,
        smart: Boolean = Prefs.current.smartPage,
    ): ScanResult {
        val screen = screenBounds(service)
        val root = pickRoot(service, near) ?: return ScanResult(emptyList(), false, false)
        val maxBottom = if (screensBelow == Int.MAX_VALUE) Int.MAX_VALUE
        else screen.bottom + screen.height() * screensBelow
        val ctx = Ctx(screen, includeBelow, maxBottom, smart)
        val items = ArrayList<Item>()
        try {
            walk(root, 0, intArrayOf(0, -1, -2), items, ctx, Flags.NONE)
        } catch (e: Exception) {
            Log.w(TAG, "walk failed", e)
        }
        if (!(ctx.web && smart)) return ScanResult(merge(items), ctx.cut, ctx.web)

        // Web page: leave out the browser's own bar, and keep the page's main content when it marks one.
        val page = items.filter { it.web }.let { w -> if (w.sumOf { it.text.length } >= 50 || w.any { it.kind != BlockKind.TEXT }) w else items }
        val mainText = page.filter { it.main }.sumOf { it.text.length }
        val kept = if (mainText >= 200) page.filter { it.main } else page
        val blocks = merge(kept).filter { b -> keepBlock(b) }
        val total = blocks.sumOf { it.text.length }
        // Smart reading left (almost) nothing: read everything rather than nothing.
        val result = if (total < 30 && blocks.none { it.kind == BlockKind.FIGURE }) merge(items) else blocks
        return ScanResult(result, ctx.cut, true)
    }

    private fun keepBlock(b: ScreenBlock): Boolean {
        if (b.kind == BlockKind.FIGURE || b.kind == BlockKind.TABLE) return true
        val t = b.text.trim()
        if (pageMarker.matches(t)) return false
        if (t.startsWith("©") || t.contains("All Rights Reserved", ignoreCase = true) && t.length < 120) return false
        if (b.kind == BlockKind.HEADING) return true
        // A short line made only of links ("To Questions", "Terms of Use") is a control, not reading.
        val allLinks = b.parts.isNotEmpty() && b.parts.all { it.link }
        if (allLinks && t.length < 40 && !t.endsWith(".") && !t.endsWith("?") && !t.endsWith("!")) return false
        return true
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

    private class Ctx(val screen: Rect, val below: Boolean, val maxBottom: Int, val smart: Boolean) {
        val budget = intArrayOf(MAX_NODES)
        val counter = intArrayOf(1)
        var cut = false
        var stop = false
        var web = false
    }

    private object Flags {
        const val NONE = 0
        const val MAIN = 1
        const val LINK = 2
        const val HEADING = 4
        const val WEB = 8
    }

    private class Item(
        val text: String,
        val bounds: Rect,
        val node: AccessibilityNodeInfo,
        val lead: Int,
        /** Ids of the parent, grandparent and great-grandparent in the walk. */
        val anc: IntArray,
        val flags: Int,
        val kind: Int = BlockKind.TEXT,
        /** For tables: one piece per cell, laid out in [text]. */
        val cells: List<Part>? = null,
    ) {
        val main get() = flags and Flags.MAIN != 0
        val link get() = flags and Flags.LINK != 0
        val heading get() = flags and Flags.HEADING != 0
        val web get() = flags and Flags.WEB != 0
    }

    private fun walk(
        node: AccessibilityNodeInfo,
        depth: Int,
        anc: IntArray,
        out: MutableList<Item>,
        ctx: Ctx,
        inherited: Int,
    ) {
        if (ctx.stop) return
        if (ctx.budget[0]-- <= 0 || depth > 90) {
            ctx.cut = true
            ctx.stop = true
            return
        }
        val r = Rect()
        boundsOf(node, r)
        val role = role(node)
        val screen = ctx.screen
        if (role != null) {
            ctx.web = true
            // Web pages share the whole page. Leave out what's scrolled past (above), what's off to
            // the side (carousels, hidden drawers) and, unless reading below, what's further down.
            if (r.bottom <= screen.top + 2 && r.height() > 0) return
            if (r.right <= screen.left || r.left >= screen.right) return
            if (!ctx.below && r.top >= screen.bottom) return
        } else {
            val visible = node.isVisibleToUser
            // Off-screen text is only kept when it's further down the page (not menus hidden to the side).
            val belowOk = ctx.below && r.top >= screen.top && r.left < screen.right && r.right > screen.left
            if (!visible && !belowOk) return
        }
        if (node.isPassword) return

        var flags = inherited
        if (role != null) flags = flags or Flags.WEB
        if (role != null && ctx.smart) {
            if (role in skipRoles) return
            if (role == "main" || role == "article") flags = flags or Flags.MAIN
        }
        if (role == "link") flags = flags or Flags.LINK
        if (role == "heading" || node.isHeading) flags = flags or Flags.HEADING

        val childCount = node.childCount

        if (role != null && ctx.smart) {
            // A big picture or diagram: a figure, explained when reading gets to it.
            if (role in figureRoles) {
                val bigEnough = r.width() >= screen.width() * 0.35f && r.height() >= screen.width() * 0.2f
                val alt = (node.contentDescription ?: node.text)?.toString()?.trim().orEmpty()
                if (bigEnough) {
                    if (r.top > ctx.maxBottom) {
                        ctx.cut = true
                        ctx.stop = true
                        return
                    }
                    out.add(Item(alt, r, node, 0, anc, flags, BlockKind.FIGURE))
                } else if (alt.length in 1..60 && mathLike.containsMatchIn(alt) && r.width() > 1) {
                    // A small picture of an equation inside a sentence: read its text.
                    out.add(Item(alt, r, node, 0, anc, flags))
                }
                return
            }
            if (role in tableRoles) {
                val table = readTable(node, r, anc, flags, ctx)
                if (table != null) {
                    if (r.top > ctx.maxBottom) {
                        ctx.cut = true
                        ctx.stop = true
                        return
                    }
                    out.add(table)
                    return
                }
            }
        }

        val raw = node.text?.toString()
        val trimmed = raw?.trim()
        if (raw != null && !trimmed.isNullOrEmpty() && r.width() > 1 && r.height() > 1) {
            if (r.top > ctx.maxBottom) {
                // Far enough down for now: the rest is read later.
                ctx.cut = true
                ctx.stop = true
                return
            }
            val lead = raw.length - raw.trimStart().length
            val own = Item(trimmed, r, node, lead, anc, flags)
            if (childCount == 0) {
                out.add(own)
                return
            }
            // Usually a node's text already includes its children's (a link made of styled spans).
            // But a container can carry just a label, like a web page's title, while its children hold
            // the real content, so use the children when they have most of the text.
            val sub = ArrayList<Item>()
            val id = ctx.counter[0]++
            val childAnc = intArrayOf(id, anc[0], anc[1])
            for (i in 0 until childCount) {
                val child = try {
                    node.getChild(i)
                } catch (e: Exception) {
                    null
                } ?: continue
                walk(child, depth + 1, childAnc, sub, ctx, flags)
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
                if (r.top > ctx.maxBottom) {
                    ctx.cut = true
                    ctx.stop = true
                    return
                }
                out.add(Item(desc, r, node, 0, anc, flags))
            }
            return
        }
        val id = ctx.counter[0]++
        val childAnc = intArrayOf(id, anc[0], anc[1])
        for (i in 0 until childCount) {
            if (ctx.stop) return
            val child = try {
                node.getChild(i)
            } catch (e: Exception) {
                null
            } ?: continue
            walk(child, depth + 1, childAnc, out, ctx, flags)
        }
    }

    // ---------------------------------------------------------------- tables

    private class Cell(val row: Int, val col: Int, val header: Boolean, val text: String, val node: AccessibilityNodeInfo, val bounds: Rect)

    /**
     * Reads a table so it makes sense out loud: each row as "column name: value". Returns null for
     * tables only used for page layout, which read better as ordinary text.
     */
    private fun readTable(table: AccessibilityNodeInfo, r: Rect, anc: IntArray, flags: Int, ctx: Ctx): Item? {
        val cells = ArrayList<Cell>()
        var caption = ""
        var nextRow = 0
        fun collect(n: AccessibilityNodeInfo, depth: Int, rowGuess: Int) {
            if (depth > 12 || ctx.budget[0]-- <= 0) return
            val role = role(n)
            if (role == "caption" && caption.isEmpty()) {
                caption = textOf(n, 0).trim()
                return
            }
            val info = n.collectionItemInfo
            if (role in cellRoles || (info != null && role != "row")) {
                val t = textOf(n, 0).replace(Regex("\\s+"), " ").trim()
                val b = Rect().also { boundsOf(n, it) }
                val row = info?.rowIndex ?: rowGuess
                val col = info?.columnIndex ?: cells.count { it.row == row }
                val header = role == "columnHeader" || role == "rowHeader" || info?.isHeading == true
                cells.add(Cell(row, col, header, t, n, b))
                return
            }
            var row = rowGuess
            if (role == "row") row = nextRow++
            for (i in 0 until n.childCount) {
                val c = try {
                    n.getChild(i)
                } catch (e: Exception) {
                    null
                } ?: continue
                collect(c, depth + 1, row)
            }
        }
        collect(table, 0, 0)
        if (cells.isEmpty()) return null
        val cols = cells.maxOf { it.col } + 1
        val longest = cells.maxOf { it.text.length }
        if (cols < 2 || longest > 400) return null   // a layout table

        val rows = cells.groupBy { it.row }.toSortedMap()
        val first = rows.values.first()
        val headerRow = first.all { it.header || it.text.isEmpty() } && rows.size > 1
        val heads = if (headerRow) first.sortedBy { it.col }.associate { it.col to it.text } else emptyMap()

        val sb = StringBuilder()
        val parts = ArrayList<Part>()
        fun say(s: String) {
            sb.append(s)
        }
        fun cell(c: Cell) {
            if (c.text.isEmpty()) return
            parts.add(Part(sb.length, c.text, c.bounds, c.node, 0, link = false))
            sb.append(c.text)
        }
        say(if (caption.isNotEmpty()) "Table: $caption. " else "Table. ")
        if (headerRow) say("Columns: " + heads.values.filter { it.isNotEmpty() }.joinToString(", ") + ". ")
        for ((ri, entry) in rows.entries.withIndex()) {
            if (headerRow && ri == 0) continue
            val row = entry.value.sortedBy { it.col }.filter { it.text.isNotEmpty() }
            if (row.isEmpty()) continue
            for ((ci, c) in row.withIndex()) {
                val head = heads[c.col]?.takeIf { it.isNotEmpty() && ci > 0 }
                if (head != null) say("$head: ")
                cell(c)
                say(if (ci == row.size - 1) ". " else if (head != null || headerRow) "; " else ", ")
            }
        }
        val text = sb.toString().trim()
        if (parts.isEmpty()) return null
        return Item(text, Rect(r), table, 0, anc, flags, BlockKind.TABLE, parts)
    }

    /** All the text inside a node (for table cells). */
    private fun textOf(n: AccessibilityNodeInfo, depth: Int): String {
        if (depth > 10) return ""
        val own = n.text?.toString()
        if (!own.isNullOrBlank() && n.childCount == 0) return own
        val sb = StringBuilder()
        for (i in 0 until n.childCount) {
            val c = try {
                n.getChild(i)
            } catch (e: Exception) {
                null
            } ?: continue
            val t = textOf(c, depth + 1)
            if (t.isNotBlank()) {
                if (sb.isNotEmpty()) sb.append(' ')
                sb.append(t.trim())
            }
        }
        if (sb.isEmpty() && !own.isNullOrBlank()) return own
        return sb.toString()
    }

    // ---------------------------------------------------------------- paragraphs

    private fun related(a: IntArray, b: IntArray): Boolean =
        a[0] == b[0] || a[0] == b[1] || a[1] == b[0] || (a[1] == b[1] && a[1] > 0)

    /** Joins inline pieces (a link inside a paragraph, runs of styled text) into one paragraph. */
    private fun merge(items: List<Item>): List<ScreenBlock> {
        val blocks = ArrayList<ScreenBlock>()
        var text = StringBuilder()
        var parts = ArrayList<Part>()
        var bounds = Rect()
        var heading = true
        var last: Item? = null

        fun flush() {
            if (parts.isNotEmpty()) {
                blocks.add(
                    ScreenBlock(
                        text.toString(), Rect(bounds), parts,
                        if (heading) BlockKind.HEADING else BlockKind.TEXT,
                    )
                )
            }
            text = StringBuilder()
            parts = ArrayList()
            bounds = Rect()
            heading = true
        }

        for (item in items) {
            if (item.kind == BlockKind.FIGURE || item.kind == BlockKind.TABLE) {
                // Figures and tables stand on their own.
                flush()
                val p = item.cells ?: listOf(Part(0, item.text, item.bounds, item.node, 0))
                blocks.add(ScreenBlock(item.text, Rect(item.bounds), p, item.kind, item.node))
                last = null
                continue
            }
            val prev = last
            val sameLine = prev != null &&
                related(prev.anc, item.anc) &&
                prev.heading == item.heading &&
                item.bounds.top < prev.bounds.bottom - min(prev.bounds.height(), item.bounds.height()) * 0.3f &&
                item.bounds.bottom > prev.bounds.top
            if (!sameLine) flush()
            if (text.isNotEmpty() && needsSpace(text, item.text)) text.append(' ')
            parts.add(Part(text.length, item.text, item.bounds, item.node, item.lead, item.link))
            text.append(item.text)
            if (!item.heading) heading = false
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
