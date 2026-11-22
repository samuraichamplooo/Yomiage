package dev.yomiage.service

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import dev.yomiage.ai.Ai
import dev.yomiage.ai.Explainer
import dev.yomiage.ai.Picture
import dev.yomiage.data.Prefs
import dev.yomiage.reader.BlockKind
import dev.yomiage.reader.Reader
import dev.yomiage.reader.ReaderState
import dev.yomiage.reader.Status
import dev.yomiage.reader.TextBlock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.math.max

/**
 * Reads a whole page, start to finish, without any tapping. It starts with what's on screen and a
 * few screens below, then keeps finding the rest of the page as reading gets near the end of what
 * it has (scrolling the page to make it load more when it needs to), until the page really ends.
 * Big pictures and diagrams are explained in plain words by AI when reading reaches them, and
 * tables are read row by row and then summed up simply.
 */
class PageSession(
    private val service: ReaderService,
    private val scope: CoroutineScope,
    private val follow: FollowAlong,
    /** The app the page is in: more of the page is only looked for while it's on screen. */
    private val pkg: String?,
) {
    private companion object {
        const val TAG = "PageSession"
        const val IMAGE_REQUEST = "AccessibilityNodeInfo.requestImageData"
        const val IMAGE_DATA = "AccessibilityNodeInfo.imageData"
        const val NEAR_END_CHUNKS = 8      // look for more of the page when this close to the end
        const val EXPLAIN_AHEAD_BLOCKS = 14 // start explaining a figure this many paragraphs early
        const val AT_ONCE = 2               // explanations being written at the same time
        const val PICTURE_EDGE = 1568       // longest side of a picture sent to the AI (sharp labels)

        /** Explanations already made (reading the same page again doesn't ask again). */
        private val made = object : LinkedHashMap<String, String>(32, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 80
        }
        /** Screenshots one at a time (Android allows about one a second). */
        private val shots = Mutex()
    }

    /** A figure or table waiting for its plain-language explanation. */
    private class Hold(val block: Int, val screen: ScreenBlock, val context: String)

    var id = -1L
        private set
    private val screens = ArrayList<ScreenBlock>()   // one per Reader block, same order
    private val seen = HashSet<AccessibilityNodeInfo>()
    private val seenText = HashSet<String>()
    private val seenPlain = HashSet<String>()
    private val holds = ArrayList<Hold>()
    private var finished = false
    private var loading = false
    private var watchJob: Job? = null
    private var explainJob: Job? = null
    private val started = HashSet<Hold>()
    private val slots = Semaphore(AT_ONCE)
    private var loadJob: Job? = null

    /**
     * Starts reading [found] (from block [startBlock], character [startChar]). With [more], the page
     * goes on past what was found and the rest is picked up as reading goes.
     */
    fun start(found: List<ScreenBlock>, startBlock: Int, startChar: Int, more: Boolean): Long {
        val explain = Prefs.current.explainVisuals && Ai.canTry()
        val (texts, scr, index) = expand(found, explain, 0)
        val first = index.getOrElse(startBlock) { 0 }
        screens.addAll(scr)
        remember(found)
        finished = !more
        id = Reader.read(texts, startBlock = first, startChar = startChar, fromScreen = true, open = more)
        collectHolds(texts, scr, 0)
        watch()
        if (more) {
            // Gather the rest of the page soon, while it's still on screen: then reading carries on
            // even if the phone gets locked (a locked phone's browser can't be read).
            scope.launch {
                delay(1_500)
                if (Reader.state.value.session == id && (pkg == null || service.frontPackage() == pkg)) {
                    try {
                        loadNow()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "early gather failed", e)
                    }
                }
            }
        }
        return id
    }

    fun stop() {
        watchJob?.cancel()
        explainJob?.cancel()
    }

    /**
     * Reading of this page carries on in a new session (after a paragraph was explained): keep
     * finding the rest of the page and explaining figures for it.
     */
    fun adopt(newId: Long) {
        id = newId
        if (!finished) Reader.reopen(newId)
        watch()
    }

    /** Gathers the rest of the page right now (before the reading view covers it). */
    suspend fun loadNow() {
        if (finished) return
        val res = withContext(Dispatchers.Default) { ScreenScanner.scanPage(service, includeBelow = true) }
        val fresh = newBlocks(res.blocks)
        if (fresh.isNotEmpty()) add(fresh)
    }

    /** The screen blocks reading is using (for explaining the current paragraph). */
    fun blocks(): List<ScreenBlock> = screens.toList()

    // ---------------------------------------------------------------- turning screen blocks into reading

    private data class Expanded(val texts: List<TextBlock>, val screens: List<ScreenBlock>, val index: IntArray)

    /**
     * Screen blocks become Reader blocks. A figure becomes a placeholder that waits for its
     * explanation; a table is read as it is, then gets a short summary after it.
     */
    private fun expand(found: List<ScreenBlock>, explain: Boolean, base: Int): Expanded {
        val texts = ArrayList<TextBlock>()
        val scr = ArrayList<ScreenBlock>()
        val index = IntArray(found.size)
        for ((i, b) in found.withIndex()) {
            index[i] = texts.size
            when (b.kind) {
                BlockKind.FIGURE -> {
                    if (explain) {
                        texts.add(TextBlock(figureName(found, i), b.bounds, BlockKind.FIGURE, hold = true))
                    } else {
                        val alt = b.text.trim()
                        texts.add(TextBlock(if (usefulAlt(alt)) "Picture: $alt" else "", b.bounds, BlockKind.FIGURE))
                    }
                    scr.add(b)
                }
                BlockKind.TABLE -> {
                    texts.add(TextBlock(b.text, b.bounds, BlockKind.TABLE))
                    scr.add(b)
                    if (explain) {
                        texts.add(TextBlock("the table", b.bounds, BlockKind.TABLE, hold = true))
                        scr.add(b)
                    }
                }
                else -> {
                    texts.add(TextBlock(b.text, b.bounds, b.kind))
                    scr.add(b)
                }
            }
        }
        return Expanded(texts, scr, index)
    }

    private fun usefulAlt(alt: String): Boolean =
        alt.length >= 4 && alt.contains(' ') && !Regex("""\.(png|jpe?g|gif|svg|webp)$""", RegexOption.IGNORE_CASE).containsMatchIn(alt)

    /** "Figure 12.1" when the caption right after the picture names it, otherwise "the figure". */
    private fun figureName(found: List<ScreenBlock>, i: Int): String {
        val near = (found.getOrNull(i + 1)?.text ?: "") + " " + (found.getOrNull(i - 1)?.text ?: "")
        val m = Regex("""\b(Figure|Fig\.|Diagram|Chart|Graph|Table)\s*([0-9]+(?:\.[0-9]+)*)""", RegexOption.IGNORE_CASE).find(near)
        return m?.let { "${it.groupValues[1].replaceFirstChar { c -> c.uppercase() }.removeSuffix(".")} ${it.groupValues[2]}" }
            ?: "the figure"
    }

    private fun collectHolds(texts: List<TextBlock>, scr: List<ScreenBlock>, base: Int) {
        for ((i, t) in texts.withIndex()) {
            if (!t.hold) continue
            val b = scr[i]
            val heading = screens.subList(0, (base + i).coerceAtMost(screens.size))
                .lastOrNull { it.kind == BlockKind.HEADING }?.text?.take(200)
            val context = buildString {
                if (heading != null) append("It's in the section: ").append(heading).append("\n\n")
                // The caption usually comes right after a picture; the paragraph before sets the scene.
                val before = scr.getOrNull(i - 1)?.takeIf { it !== b && it.kind != BlockKind.FIGURE }?.text
                val after = scr.getOrNull(i + 1)?.takeIf { it !== b }?.text
                if (b.kind == BlockKind.TABLE) append("The table, read out row by row:\n").append(b.text.take(6000))
                else if (b.text.isNotBlank()) append("Its description: ").append(b.text.take(400))
                if (after != null) append("\n\nText right after it (often the caption): ").append(after.take(900))
                if (before != null) append("\n\nText right before it: ").append(before.take(700))
            }
            holds.add(Hold(base + i, b, context))
        }
    }

    private fun remember(found: List<ScreenBlock>) {
        for (b in found) {
            b.node?.let { seen.add(it) }
            for (p in b.parts) p.node?.let { seen.add(it) }
            textKey(b)?.let { seenText.add(it) }
            seenPlain.add(plainKey(b))
        }
    }

    private fun textKey(b: ScreenBlock): String? {
        val t = b.text.lowercase().replace(Regex("\\s+"), " ").trim()
        return if (t.length >= 25) t else null
    }

    // ---------------------------------------------------------------- reading on past the end

    private fun watch() {
        watchJob?.cancel()
        watchJob = scope.launch {
            Reader.state.collect { st ->
                if (st.session != id) {
                    // Something else is being read now (this page may come back via adopt).
                    if (st.status != Status.Idle && st.session > id) {
                        explainJob?.cancel()
                        loadJob?.cancel()
                    }
                    return@collect
                }
                if (!finished && !loading && nearEnd(st)) loadMore()
                if (holds.isNotEmpty() && explainJob?.isActive != true) explainNext()
            }
        }
    }

    private fun nearEnd(st: ReaderState): Boolean =
        (st.status == Status.Playing || st.status == Status.Buffering || st.status == Status.Preparing) &&
            st.total - st.index <= NEAR_END_CHUNKS

    private fun loadMore() {
        loading = true
        loadJob = scope.launch {
            try {
                var scrolls = 0
                var waits = 0
                while (Reader.state.value.session == id) {
                    // Only look while the page is on screen (not while the reading view is open).
                    if (pkg != null && service.frontPackage() != pkg) {
                        if (++waits > 600) return@launch
                        delay(2_000)
                        continue
                    }
                    val res = withContext(Dispatchers.Default) {
                        ScreenScanner.scanPage(service, includeBelow = true)
                    }
                    val fresh = newBlocks(res.blocks)
                    if (fresh.isNotEmpty()) {
                        add(fresh)
                        return@launch
                    }
                    // Nothing new: the page may load more as it's scrolled (long eBooks do).
                    if (scrolls >= 2 || !scrollDown()) {
                        finished = true
                        Reader.append(id, emptyList(), done = true)
                        return@launch
                    }
                    scrolls++
                    delay(1_400)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "couldn't find more of the page", e)
                finished = true
                Reader.append(id, emptyList(), done = true)
            } finally {
                loading = false
            }
        }
    }

    /** What the latest scan found that isn't being read yet, in page order. */
    private fun newBlocks(found: List<ScreenBlock>): List<ScreenBlock> {
        // Web page nodes stay the same while the page scrolls, so they identify text already read.
        // Ordinary apps reuse list rows for new text, so there it's the text itself that counts.
        fun web(b: ScreenBlock) = (b.node ?: b.parts.firstOrNull()?.node)?.let { ScreenScanner.role(it) != null } == true
        fun known(b: ScreenBlock): Boolean =
            if (web(b)) (b.node != null && b.node in seen) || b.parts.any { it.node != null && it.node in seen }
            else plainKey(b) in seenPlain
        val lastKnown = found.indexOfLast { known(it) }
        val after = if (lastKnown >= 0) found.subList(lastKnown + 1, found.size) else found
        return after.filter { !known(it) && (textKey(it) == null || textKey(it) !in seenText) }
    }

    private fun plainKey(b: ScreenBlock) = b.text.lowercase().replace(Regex("\\s+"), " ").trim()

    private fun add(fresh: List<ScreenBlock>) {
        val explain = Prefs.current.explainVisuals && Ai.canTry()
        val base = screens.size
        val (texts, scr, _) = expand(fresh, explain, base)
        remember(fresh)
        if (!Reader.append(id, texts, done = false)) return
        screens.addAll(scr)
        follow.extend(id, scr)
        collectHolds(texts, scr, base)
    }

    /** Scrolls the page down a screen so it loads more. Returns false if it can't scroll. */
    private fun scrollDown(): Boolean {
        val last = screens.lastOrNull() ?: return false
        val start = last.node ?: last.parts.lastOrNull()?.node ?: return false
        var n: AccessibilityNodeInfo? = start
        var depth = 0
        while (n != null && depth < 60) {
            if (n.isScrollable) {
                follow.noteOwnScroll()
                return try {
                    n.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                } catch (e: Exception) {
                    false
                }
            }
            n = try {
                n.parent
            } catch (e: Exception) {
                null
            }
            depth++
        }
        return false
    }

    // ---------------------------------------------------------------- figures and tables

    /**
     * Explains figures and tables as reading gets close to them, a couple at a time, so a picture
     * waiting to come on screen doesn't hold up a table after it.
     */
    private fun explainNext() {
        explainJob = scope.launch {
            while (Reader.state.value.session == id && (holds.isNotEmpty() || started.isNotEmpty())) {
                val at = Reader.state.value.chunk?.block ?: 0
                // Ones reading already passed without starting them (it skipped ahead): let them go.
                holds.filter { it.block < at }.forEach { h ->
                    holds.remove(h)
                    Reader.resolveHold(id, h.block, null)
                }
                for (h in holds.sortedBy { it.block }) {
                    if (h.block - at > EXPLAIN_AHEAD_BLOCKS) break
                    holds.remove(h)
                    started.add(h)
                    launch {
                        try {
                            explainOne(h)
                        } catch (e: CancellationException) {
                            holds.add(h)   // not done: pick it up again later
                            throw e
                        } catch (e: Exception) {
                            Log.w(TAG, "couldn't explain block ${h.block}", e)
                            Reader.resolveHold(id, h.block, fallbackFor(h))
                        } finally {
                            started.remove(h)
                        }
                    }
                }
                delay(600)
            }
        }
    }

    /** What to say with no explanation: what the page calls the picture, if it says. */
    private fun fallbackFor(h: Hold): String? {
        val alt = h.screen.text.trim()
        return if (h.screen.kind == BlockKind.FIGURE && usefulAlt(alt)) "Picture: $alt" else null
    }

    private suspend fun explainOne(h: Hold) {
        val table = h.screen.kind == BlockKind.TABLE
        val key = (if (table) "T|" else "F|") + h.screen.text.take(500) + "|" + h.context.hashCode()
        synchronized(made) { made[key] }?.let {
            Reader.feedHold(id, h.block, it, done = true)
            return
        }
        val picture = if (table) null else picture(h)
        if (!table && picture == null && h.screen.text.isBlank() && !h.context.contains("caption")) {
            Reader.resolveHold(id, h.block, fallbackFor(h))
            return
        }
        val ask = when {
            table -> h.context + "\n\nExplain simply what this table tells us: what it compares, the main pattern, " +
                "and the one or two numbers that matter most, exactly as written. Start with \"In short,\"."
            picture != null -> "Here is a picture from the page." + (if (h.context.isNotBlank()) "\n\n${h.context}" else "") +
                "\n\nExplain it simply. Start with what kind of picture it is, for example \"This flowchart shows…\", " +
                "\"This graph shows…\" or \"This photo shows…\"."
            else -> "A figure on the page couldn't be seen, but here is what the page says about it.\n\n${h.context}" +
                "\n\nIn two or three sentences, explain simply what this figure is about. Start with \"This figure\"."
        }
        val sb = StringBuilder()
        var complete = false
        try {
            slots.withPermit {
                Ai.stream(
                    system(if (table) "tables" else "figures, diagrams and pictures"),
                    ask,
                    cancelled = { Reader.state.value.session != id },
                    image = picture,
                ) { t ->
                    // Each finished sentence can be read while the rest is being written.
                    val so = synchronized(sb) { sb.append(t); plain(sb.toString()) }
                    Reader.feedHold(id, h.block, so, done = false)
                }
            }
            // Stopped early (something else started reading) isn't a whole answer: don't keep it.
            complete = Reader.state.value.session == id
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "explaining block ${h.block} stopped", e)
        }
        val text = plain(synchronized(sb) { sb.toString() }).trim()
        if (text.isEmpty()) {
            Reader.resolveHold(id, h.block, fallbackFor(h))
            return
        }
        Reader.feedHold(id, h.block, text, done = true)
        if (complete) synchronized(made) { made[key] = text }
    }

    /** Leaves out markdown the AI might add anyway (the same way as the text grows). */
    private fun plain(s: String): String = s.replace("*", "").replace("#", "").replace("`", "")

    private fun system(what: String) =
        "You explain $what from textbooks and web pages to someone who is listening, not looking." +
            Explainer.audienceLine() + " Use plain, friendly words and short sentences, about 60 to 120 words in " +
            "all. Your first sentence says what it is and its main point, because it's read aloud the moment it's " +
            "written. Then explain what it shows and how the parts connect: what leads to what, what is bigger or " +
            "smaller, what changes. Use the exact labels, names, numbers and units it shows. Explain any hard term " +
            "in a few words, and connect it to the text around it. Be precise: describe only what you can actually " +
            "see or read, and if part of it is too small or unclear to read, say so in a few words instead of " +
            "guessing. Plain text only: no markdown, lists, symbols or emoji."

    /**
     * Gets the picture: browsers hand it over directly; otherwise it's cut out of a screenshot as
     * soon as it's on screen (reading scrolls along, so it usually is before reading gets there).
     */
    private suspend fun picture(h: Hold): Picture? {
        val b = h.screen
        val node = b.node
        if (node != null) {
            val bytes = withContext(Dispatchers.IO) { imageData(node) }
            if (bytes != null) {
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bmp != null) return toPicture(bmp)
            }
        }
        val deadline = SystemClock.elapsedRealtime() + 6 * 60_000L
        var tries = 0
        while (Reader.state.value.session == id && SystemClock.elapsedRealtime() < deadline) {
            // Reading went well past it without it ever being on screen: explain from the caption.
            if ((Reader.state.value.chunk?.block ?: 0) > h.block + 1) return null
            val r = Rect()
            if (node != null) {
                try {
                    node.refresh()
                } catch (_: Exception) {
                }
                ScreenScanner.boundsOf(node, r)
            } else {
                r.set(b.bounds)
            }
            val sc = ScreenScanner.screenBounds(service)
            val visible = Rect(r)
            val onScreen = !r.isEmpty && visible.intersect(sc)
            val enough = onScreen && (visible.height() >= r.height() * 0.8f || visible.height() >= sc.height() * 0.6f)
            if (enough && (pkg == null || service.frontPackage() == pkg)) {
                val pic = shots.withLock { capture(node, visible) }
                if (pic != null) return pic
                if (++tries >= 3) return null
                delay(1_100)   // screenshots are limited to about one a second
                continue
            }
            delay(700)
        }
        return null
    }

    /** Cuts [area] (screen coordinates) out of a screenshot of the page's window, or of the screen. */
    private suspend fun capture(node: AccessibilityNodeInfo?, area: Rect): Picture? {
        if (node != null) {
            service.captureWindowOf(node)?.let { (shot, win) ->
                val sx = shot.width / win.width().toFloat()
                val sy = shot.height / win.height().toFloat()
                val crop = Rect(
                    ((area.left - win.left) * sx).toInt(), ((area.top - win.top) * sy).toInt(),
                    ((area.right - win.left) * sx).toInt(), ((area.bottom - win.top) * sy).toInt(),
                )
                if (crop.intersect(0, 0, shot.width, shot.height) && crop.width() >= 40 && crop.height() >= 40) {
                    return toPicture(Bitmap.createBitmap(shot, crop.left, crop.top, crop.width(), crop.height()))
                }
            }
        }
        val shot = service.captureScreen() ?: return null
        val crop = Rect(area)
        if (!crop.intersect(0, 0, shot.width, shot.height) || crop.width() < 40 || crop.height() < 40) return null
        return toPicture(Bitmap.createBitmap(shot, crop.left, crop.top, crop.width(), crop.height()))
    }

    /** Chrome can send a picture's own image data to accessibility services. */
    private fun imageData(node: AccessibilityNodeInfo): ByteArray? {
        try {
            if (!node.availableExtraData.contains(IMAGE_REQUEST)) return null
            repeat(5) {
                node.refreshWithExtraData(IMAGE_REQUEST, Bundle())
                val data = node.extras?.getByteArray(IMAGE_DATA)
                if (data != null && data.size > 100) return data
                Thread.sleep(400)
            }
        } catch (e: Exception) {
            Log.w(TAG, "image data unavailable", e)
        }
        return null
    }

    private fun toPicture(src: Bitmap): Picture {
        val longest = max(src.width, src.height)
        val bmp = if (longest > PICTURE_EDGE) {
            val f = PICTURE_EDGE.toFloat() / longest
            Bitmap.createScaledBitmap(src, (src.width * f).toInt().coerceAtLeast(1), (src.height * f).toInt().coerceAtLeast(1), true)
        } else {
            src
        }
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 90, out)
        return Picture(out.toByteArray(), bmp)
    }
}
