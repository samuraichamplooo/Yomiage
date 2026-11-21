package dev.yomiage.ai

import dev.yomiage.data.Prefs
import dev.yomiage.reader.Reader
import dev.yomiage.reader.Status
import dev.yomiage.reader.TextBlock
import dev.yomiage.service.ScreenBlock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Simple mode: reads a page rewritten in plain language, or explains the paragraph being read and
 * then goes back to the page where it left off.
 */
object Explainer {
    const val LABEL_SIMPLE = "Simplified"
    const val LABEL_EXPLAIN = "Explanation"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null
    private var explainSession = -1L
    private var resume: Resume? = null
    private var started = false

    /** Lets the screen reader re-attach on-screen highlighting when going back to the page. */
    var onScreenResumed: ((Long, List<ScreenBlock>) -> Unit)? = null

    private class Resume(
        val blocks: List<TextBlock>,
        val screen: List<ScreenBlock>?,
        val startBlock: Int,
        val startChar: Int,
        val fromScreen: Boolean,
        val label: String?,
    )

    fun hasKey() = Prefs.current.geminiKey.isNotBlank()

    fun init() {
        if (started) return
        started = true
        // When an explanation finishes, carry on with the page.
        scope.launch {
            Reader.state.collect { st ->
                if (st.session == explainSession && st.status == Status.Finished) {
                    explainSession = -1
                    resume?.let { go(it) }
                    resume = null
                }
            }
        }
    }

    private fun audienceLine(): String {
        val a = Prefs.current.audience.trim()
        return if (a.isEmpty()) "" else " The listener is $a; pitch the explanation for them."
    }

    private fun simplifySystem() =
        "You turn pages from textbooks, articles and websites into clear, simple spoken explanations for " +
            "someone who will listen rather than read." + audienceLine() + " Use plain everyday words and short " +
            "sentences. Explain each technical term in a few words the first time it comes up. Keep every important " +
            "fact and its exact meaning, never add facts that aren't there, and keep the original order. Use a quick " +
            "comparison or example when it really helps. Skip page clutter such as menus, buttons, page numbers, " +
            "ads, figure links, copyright lines and anything that isn't the actual content. Write flowing paragraphs " +
            "separated by blank lines. Plain text only: no markdown, no bullet symbols, no headings, no emoji."

    private fun explainSystem() =
        "You are a patient tutor." + audienceLine() + " Explain the passage the listener just heard: what it " +
            "means in plain words, any hard terms, and why it matters. Use short sentences and at most about 150 " +
            "words. Add one quick everyday example or comparison if it helps. Stay accurate and don't add facts that " +
            "aren't implied by the passage. Don't repeat the passage word for word. Plain text only: no markdown, no " +
            "lists, no emoji."

    /** Reads [blocks] rewritten in simple language. */
    fun readSimplified(blocks: List<TextBlock>) {
        if (!hasKey()) {
            Reader.showMessage("Add your free Gemini key in Yomiage to use simple mode.")
            return
        }
        val text = blocks.joinToString("\n\n") { it.text }.take(14_000)
        resume = null
        start(simplifySystem(), "Here is the text on my screen:\n\n$text", LABEL_SIMPLE, "Simplifying with Gemini…", null)
    }

    /** Explains the paragraph being read, then returns to the next sentence of the page. */
    fun explainCurrent(screen: List<ScreenBlock>?) {
        if (!hasKey()) {
            Reader.showMessage("Add your free Gemini key in Yomiage to get simple explanations.")
            return
        }
        val snap = Reader.snapshot() ?: return
        val cur = snap.chunks[snap.chunkIndex]
        val block = snap.blocks.getOrNull(cur.block)?.text ?: return
        val before = snap.blocks.getOrNull(cur.block - 1)?.text.orEmpty()
        val next = snap.chunks.getOrNull(snap.chunkIndex + 1)
        val back = if (next != null) {
            Resume(
                snap.blocks, if (snap.fromScreen) screen else null, next.block, next.map.firstOrNull() ?: 0,
                snap.fromScreen, snap.label,
            )
        } else {
            null
        }
        val user = buildString {
            if (before.isNotBlank()) append("For context, the paragraph before it:\n").append(before.take(1500)).append("\n\n")
            append("The passage to explain:\n").append(block.take(4000))
        }
        start(explainSystem(), user, LABEL_EXPLAIN, "Thinking it through…", back)
    }

    /** From an explanation: stop it and go back to the page now. */
    fun backToPage() {
        val r = resume
        job?.cancel()
        explainSession = -1
        resume = null
        if (r != null) go(r) else Reader.stop()
    }

    private fun go(r: Resume) {
        val id = Reader.read(r.blocks, r.startBlock, r.startChar, r.fromScreen, label = r.label)
        if (r.screen != null) onScreenResumed?.invoke(id, r.screen)
    }

    private fun start(system: String, user: String, label: String, waiting: String, back: Resume?) {
        job?.cancel()
        val key = Prefs.current.geminiKey
        val id = Reader.read(emptyList(), open = true, label = label, waiting = waiting)
        explainSession = if (label == LABEL_EXPLAIN) id else -1
        resume = back
        job = scope.launch {
            var got = false
            try {
                withContext(Dispatchers.IO) {
                    val paragraphs = Paragraphs { p ->
                        if (Reader.append(id, listOf(TextBlock(p)), done = false)) got = true
                    }
                    Gemini.stream(key, system, user, cancelled = { !isActive || Reader.state.value.session != id }) {
                        paragraphs.add(it)
                    }
                    paragraphs.flush()
                }
                Reader.append(id, emptyList(), done = true)
                if (!got && Reader.state.value.session == id) Reader.showMessage("Gemini didn't send anything back. Try again.")
            } catch (e: GeminiException) {
                if (Reader.state.value.session == id) {
                    if (got) Reader.append(id, emptyList(), done = true) else Reader.showMessage(e.message ?: "Gemini failed.")
                }
            }
        }
    }

    /** Collects streamed text and hands out whole paragraphs (or long runs of sentences) to be read. */
    private class Paragraphs(private val emit: (String) -> Unit) {
        private val buf = StringBuilder()

        fun add(t: String) {
            buf.append(t)
            drain(false)
        }

        fun flush() = drain(true)

        private fun drain(all: Boolean) {
            while (true) {
                val nl = buf.indexOf("\n")
                if (nl >= 0) {
                    val p = clean(buf.substring(0, nl))
                    buf.delete(0, nl + 1)
                    if (p.isNotEmpty()) emit(p)
                    continue
                }
                if (!all && buf.length > 420) {
                    val cut = lastSentenceEnd(buf)
                    if (cut > 0) {
                        val p = clean(buf.substring(0, cut))
                        buf.delete(0, cut)
                        if (p.isNotEmpty()) emit(p)
                        continue
                    }
                }
                break
            }
            if (all) {
                val p = clean(buf.toString())
                buf.setLength(0)
                if (p.isNotEmpty()) emit(p)
            }
        }

        private fun lastSentenceEnd(sb: StringBuilder): Int {
            for (i in sb.length - 2 downTo 120) {
                val c = sb[i]
                if ((c == '.' || c == '!' || c == '?') && sb[i + 1] == ' ') return i + 1
            }
            return -1
        }

        private fun clean(s: String): String =
            s.replace("**", "").replace("__", "").replace("`", "")
                .replace(Regex("^\\s*(#+|[-*•]|\\d+[.)])\\s+"), "")
                .trim()
    }
}
