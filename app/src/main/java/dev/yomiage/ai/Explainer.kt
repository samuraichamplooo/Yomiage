package dev.yomiage.ai

import android.content.Context
import android.widget.Toast
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
    private lateinit var app: Context

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

    fun hasKey() = Ai.canTry()

    fun init(context: Context) {
        if (started) return
        started = true
        app = context.applicationContext
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

    internal fun audienceLine(): String {
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
            "separated by blank lines. Plain text only: no markdown, no bullet symbols, no headings, no emoji. " +
            "If you're given one part of a longer page, just rewrite that part, with no introduction or wrap-up."

    private fun explainSystem() =
        "You are a patient tutor." + audienceLine() + " Explain the passage the listener just heard: what it " +
            "means in plain words, any hard terms, and why it matters. Use short sentences and at most about 150 " +
            "words. Add one quick everyday example or comparison if it helps. Stay accurate and don't add facts that " +
            "aren't implied by the passage. Don't repeat the passage word for word. Plain text only: no markdown, no " +
            "lists, no emoji."

    /**
     * Reads [blocks] rewritten in simple language. If no AI can be reached, [fallback] reads the
     * original instead.
     */
    fun readSimplified(blocks: List<TextBlock>, fallback: (() -> Unit)? = null) {
        if (!hasKey()) {
            Reader.showMessage("Add a free AI key or turn on on-device AI in Yomiage to use simple mode.")
            return
        }
        // Long pages go in parts: reading starts sooner, and on-device AI has a small input limit.
        val sections = ArrayList<String>()
        val sb = StringBuilder()
        var total = 0
        for (b in blocks) {
            if (total > 24_000) break
            if (sb.isNotEmpty() && sb.length + b.text.length > 4_500) {
                sections.add(sb.toString())
                sb.setLength(0)
            }
            if (sb.isNotEmpty()) sb.append("\n\n")
            sb.append(b.text.take(4_500))
            total += b.text.length
        }
        if (sb.isNotEmpty()) sections.add(sb.toString())
        val prompts = sections.mapIndexed { i, sec ->
            if (sections.size == 1) "Here is the text on my screen:\n\n$sec"
            else "Here is part ${i + 1} of ${sections.size} of the text on my screen:\n\n$sec"
        }
        resume = null
        start(simplifySystem(), prompts, LABEL_SIMPLE, "Simplifying…", null, fallback)
    }

    /** Explains the paragraph being read, then returns to the next sentence of the page. */
    fun explainCurrent(screen: List<ScreenBlock>?) {
        if (!hasKey()) {
            Reader.showMessage("Add a free AI key or turn on on-device AI in Yomiage to get simple explanations.")
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
        start(explainSystem(), listOf(user), LABEL_EXPLAIN, "Thinking it through…", back)
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

    private fun start(
        system: String,
        prompts: List<String>,
        label: String,
        waiting: String,
        back: Resume?,
        fallback: (() -> Unit)? = null,
    ) {
        job?.cancel()
        val id = Reader.read(emptyList(), open = true, label = label, waiting = waiting)
        explainSession = if (label == LABEL_EXPLAIN) id else -1
        resume = back
        job = scope.launch {
            var got = false
            try {
                val paragraphs = Paragraphs { p ->
                    if (Reader.append(id, listOf(TextBlock(p)), done = false)) got = true
                }
                for (prompt in prompts) {
                    if (Reader.state.value.session != id) break
                    Ai.stream(
                        system, prompt,
                        cancelled = { !isActive || Reader.state.value.session != id },
                        onStatus = { msg -> if (!got) Reader.setWaiting(id, msg) },
                    ) {
                        paragraphs.add(it)
                    }
                    paragraphs.flush()
                }
                Reader.append(id, emptyList(), done = true)
                if (!got && Reader.state.value.session == id) Reader.showMessage("The AI didn't send anything back. Try again.")
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (Reader.state.value.session != id) return@launch
                if (got) {
                    Reader.append(id, emptyList(), done = true)
                    return@launch
                }
                val message = if (e is AiException) e.message ?: "The AI didn't answer." else "Something went wrong talking to the AI."
                val backTo = resume
                when {
                    label == LABEL_EXPLAIN && backTo != null -> {
                        // Couldn't explain: say why and carry on with the page.
                        Toast.makeText(app, message, Toast.LENGTH_LONG).show()
                        explainSession = -1
                        resume = null
                        go(backTo)
                    }
                    fallback != null -> {
                        Toast.makeText(app, "$message Reading the original instead.", Toast.LENGTH_LONG).show()
                        fallback()
                    }
                    else -> Reader.showMessage(message)
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
