package dev.yomiage.reader

import android.graphics.Rect
import android.icu.text.BreakIterator
import java.util.Locale

/** What a block of text is, for the reading view and for figures and tables. */
object BlockKind {
    const val TEXT = 0
    const val HEADING = 1
    const val FIGURE = 2
    const val TABLE = 3
    const val EXPLAINED = 4   // an AI's plain-language explanation of a figure or table
}

/**
 * A piece of text from the screen (a paragraph, a label, a line of a list) and where it is.
 * With [hold], the block is a placeholder (a figure being explained): reading waits there until
 * the explanation arrives (see Reader.resolveHold), or moves on if it takes too long.
 */
data class TextBlock(
    val text: String,
    val bounds: Rect? = null,
    val kind: Int = BlockKind.TEXT,
    val hold: Boolean = false,
)

/**
 * One unit of speech: usually a sentence. [gapMs] is the pause added after it.
 * [map] gives, for each character of [text], its index in the block's original text, so the
 * sentence and the word being spoken can be found on screen. A [hold] chunk isn't spoken: it
 * marks where an explanation that's still being written will go.
 */
class Chunk(val text: String, val block: Int, val gapMs: Int, val map: IntArray, val hold: Boolean = false) {
    fun withGap(gap: Int) = Chunk(text, block, gap, map, hold)
    fun withBlock(b: Int) = Chunk(text, b, gapMs, map, hold)
}

object TextChunker {
    const val GAP_SPLIT = 40
    const val GAP_SENTENCE = 90
    const val GAP_BLOCK = 300

    private val urlRe = Regex("""(https?://|www\.)\S+""", RegexOption.IGNORE_CASE)
    private val abbreviations = setOf(
        "mr.", "mrs.", "ms.", "dr.", "prof.", "st.", "jr.", "sr.", "vs.", "etc.", "inc.", "ltd.",
        "co.", "corp.", "no.", "fig.", "approx.", "dept.", "est.", "gen.", "gov.", "lt.", "mt.", "sgt.",
        "jan.", "feb.", "mar.", "apr.", "aug.", "sept.", "sep.", "oct.", "nov.", "dec.",
    )
    private val initialsRe = Regex("^(?:[A-Za-z]\\.)+$")

    /** Splits pasted or selected text into paragraphs. */
    fun paragraphs(text: String): List<TextBlock> =
        text.split(Regex("\\n\\s*\\n|\\n")).map { it.trim() }.filter { it.isNotEmpty() }.map { TextBlock(it) }

    private fun isEmoji(cp: Int) =
        cp in 0x1F000..0x1FAFF || cp in 0x2600..0x27BF || cp in 0x2B00..0x2BFF ||
            cp in 0xFE00..0xFE0F || cp == 0x200D || cp == 0x20E3 || cp in 0xE0020..0xE007F

    private class Mapped(val text: String, val map: IntArray)

    /**
     * Cleans text for speech (links read as "link", emoji dropped, spaces collapsed) while keeping
     * track of where each spoken character came from.
     */
    private fun normalize(raw: String, readUrls: Boolean, marks: HighlightMarks.State = HighlightMarks.State()): Mapped {
        val sb = StringBuilder(raw.length)
        val map = IntArray(raw.length + 8)
        var len = 0
        fun emit(c: Char, at: Int) {
            sb.append(c)
            map[len++] = at
        }
        fun dropLast() {
            sb.setLength(sb.length - 1)
            len--
        }
        // Pieces said differently: web addresses ("link"), and screen-reader highlight labels
        // ("Yellow highlight:" once where a highlight begins, nothing for the rest).
        val swaps = ArrayList<Pair<IntRange, String>>()
        if (!readUrls) urlRe.findAll(raw).forEach { swaps.add(it.range to "link") }
        swaps.addAll(HighlightMarks.replacements(raw, marks))
        swaps.sortBy { it.first.first }
        var ui = 0
        var lastSpace = true
        var i = 0
        while (i < raw.length) {
            while (ui < swaps.size && swaps[ui].first.first < i) ui++   // overlapping: skip
            if (ui < swaps.size && i == swaps[ui].first.first) {
                val (range, say) = swaps[ui]
                if (say.isNotEmpty()) {
                    if (!lastSpace) emit(' ', i)
                    for (ch in say) emit(ch, i)
                    emit(' ', i)
                    lastSpace = true
                } else if (!lastSpace) {
                    emit(' ', i)   // keep the words on either side apart
                    lastSpace = true
                }
                i = range.last + 1
                ui++
                continue
            }
            val cp = raw.codePointAt(i)
            val n = Character.charCount(cp)
            if (Character.isWhitespace(cp) || cp == 0xA0 || cp == 0x200B || isEmoji(cp)) {
                if (!lastSpace) {
                    emit(' ', i)
                    lastSpace = true
                }
                i += n
                continue
            }
            val c = raw[i]
            if (n == 1 && c in ".,;:!?" && len > 0 && sb[sb.length - 1] == ' ') dropLast()
            for (k in 0 until n) emit(raw[i + k], i + k)
            lastSpace = false
            i += n
        }
        if (len > 0 && sb[sb.length - 1] == ' ') dropLast()
        return Mapped(sb.toString(), map.copyOf(len))
    }

    /** True if a "sentence" really ends at an abbreviation like "Dr." or "U.S." and should continue. */
    private fun endsWithAbbreviation(sentence: String): Boolean {
        val last = sentence.trimEnd().substringAfterLast(' ').trimStart('(', '"', '“')
        if (last.isEmpty() || !last.endsWith(".")) return false
        return last.lowercase() in abbreviations || initialsRe.matches(last)
    }

    /**
     * Joins neighbouring sentences of the same paragraph into chunks of up to [max] letters (for
     * online voices: fewer, longer requests sound more natural and go easier on usage limits).
     * The first [keepFirst] chunks stay short so reading starts quickly.
     */
    fun merge(chunks: List<Chunk>, max: Int, keepFirst: Int = 1): List<Chunk> {
        if (max <= 0 || chunks.size < 2) return chunks
        val out = ArrayList<Chunk>(chunks.size)
        for ((i, c) in chunks.withIndex()) {
            val last = out.lastOrNull()
            if (last != null && i > keepFirst && !last.hold && !c.hold && last.block == c.block &&
                last.text.length + 1 + c.text.length <= max && last.map.size == last.text.length &&
                c.map.size == c.text.length
            ) {
                val text = last.text + " " + c.text
                val map = IntArray(text.length)
                last.map.copyInto(map, 0)
                map[last.text.length] = ((last.map.lastOrNull() ?: 0) + 1).coerceAtMost(c.map.firstOrNull() ?: Int.MAX_VALUE)
                c.map.copyInto(map, last.text.length + 1)
                out[out.size - 1] = Chunk(text, c.block, c.gapMs, map)
            } else {
                out.add(c)
            }
        }
        return out
    }

    /** Index of the start of the sentence that contains [index]. */
    fun sentenceStart(text: String, index: Int): Int {
        if (index <= 0 || text.isEmpty()) return 0
        val i = index.coerceAtMost(text.length - 1)
        val bi = BreakIterator.getSentenceInstance(Locale.getDefault())
        bi.setText(text)
        var p = if (bi.isBoundary(i)) i else bi.preceding(i)
        // Don't start in the middle of "Dr. Smith".
        while (p > 0 && endsWithAbbreviation(text.substring(0, p))) {
            p = bi.preceding(p)
        }
        return if (p == BreakIterator.DONE || p < 0) 0 else p
    }

    /**
     * Turns blocks into chunks, starting at [startChar] of [startBlock] (snapped back to the start
     * of that sentence). The first chunks are kept short so speech starts quickly; later ones are
     * whole sentences.
     */
    fun build(
        blocks: List<TextBlock>,
        startBlock: Int,
        startChar: Int,
        readUrls: Boolean,
        rampFrom: Int = 0,
    ): List<Chunk> {
        val out = ArrayList<Chunk>()
        val bi = BreakIterator.getSentenceInstance(Locale.getDefault())
        val marks = HighlightMarks.State()
        for (b in startBlock.coerceAtLeast(0) until blocks.size) {
            if (blocks[b].hold) {
                val t = blocks[b].text.ifBlank { "Figure" }
                out.add(Chunk(t, b, GAP_BLOCK, IntArray(t.length) { it }, hold = true))
                continue
            }
            val full = normalize(blocks[b].text, readUrls, marks)
            var text = full.text
            var map = full.map
            if (b == startBlock && startChar > 0) {
                var ns = map.indexOfFirst { it >= startChar }
                if (ns < 0) ns = text.length
                val s = sentenceStart(text, ns)
                text = text.substring(s)
                map = map.copyOfRange(s, map.size)
            }
            if (!speakable(text)) continue

            // Sentence ranges, merging splits after abbreviations.
            val ranges = ArrayList<IntArray>()
            bi.setText(text)
            var start = bi.first()
            var end = bi.next()
            while (end != BreakIterator.DONE) {
                val r = trim(text, start, end)
                if (r != null) {
                    val prev = ranges.lastOrNull()
                    if (prev != null && endsWithAbbreviation(text.substring(prev[0], prev[1]))) {
                        prev[1] = r[1]
                    } else {
                        ranges.add(r)
                    }
                }
                start = end
                end = bi.next()
            }

            for ((ri, range) in ranges.withIndex()) {
                val lastInBlock = ri == ranges.size - 1
                var s = range[0]
                val e = range[1]
                while (e - s > maxLen(rampFrom + out.size)) {
                    val cut = s + findCut(text.substring(s, e), maxLen(rampFrom + out.size))
                    val p = trim(text, s, cut)
                    if (p != null && speakable(text.substring(p[0], p[1]))) {
                        out.add(Chunk(text.substring(p[0], p[1]), b, GAP_SPLIT, map.copyOfRange(p[0], p[1])))
                    }
                    s = cut
                    while (s < e && text[s] == ' ') s++
                }
                val r = trim(text, s, e)
                if (r != null && speakable(text.substring(r[0], r[1]))) {
                    out.add(
                        Chunk(
                            text.substring(r[0], r[1]), b,
                            if (lastInBlock) GAP_BLOCK else GAP_SENTENCE,
                            map.copyOfRange(r[0], r[1]),
                        )
                    )
                } else if (lastInBlock && out.isNotEmpty() && out.last().block == b) {
                    out[out.size - 1] = out.last().withGap(GAP_BLOCK)
                }
            }
        }
        return out
    }

    private fun trim(text: String, start: Int, end: Int): IntArray? {
        var s = start
        var e = end
        while (s < e && text[s].isWhitespace()) s++
        while (e > s && text[e - 1].isWhitespace()) e--
        return if (e > s) intArrayOf(s, e) else null
    }

    private fun maxLen(chunkIndex: Int) = when (chunkIndex) {
        0 -> 90
        1 -> 170
        else -> 280
    }

    private fun speakable(s: String) = s.any { it.isLetterOrDigit() }

    /** Where to split an over-long sentence: after a comma-like mark if possible, else at a space. */
    private fun findCut(s: String, max: Int): Int {
        val limit = max.coerceAtMost(s.length - 1)
        val min = (max * 0.4).toInt()
        for (i in limit downTo min) {
            val c = s[i]
            if ((c == ',' || c == ';' || c == ':' || c == '—' || c == '–' || c == ')') &&
                i + 1 < s.length && s[i + 1] == ' '
            ) return i + 1
        }
        val space = s.lastIndexOf(' ', limit)
        return if (space > min / 2) space else limit
    }
}

/** Estimates which word is being spoken from how far through a sentence the audio is. */
object WordTiming {
    private fun weight(c: Char): Float = when {
        c.isLetterOrDigit() -> 1f
        c == ' ' -> 0.35f
        c == ',' || c == ';' || c == ':' || c == '—' || c == '–' -> 2.6f
        c == '.' || c == '!' || c == '?' -> 3f
        else -> 0.4f
    }

    private fun isWordChar(c: Char) = c.isLetterOrDigit() || c == '\'' || c == '’' || c == '-'

    /** Returns [start, end) of the word at [fraction] (0..1) of [text], or null. */
    fun wordAt(text: String, fraction: Float): IntArray? {
        if (text.isEmpty()) return null
        var total = 0f
        for (c in text) total += weight(c)
        val target = fraction.coerceIn(0f, 1f) * total
        var acc = 0f
        var i = text.length - 1
        for (k in text.indices) {
            acc += weight(text[k])
            if (acc >= target) {
                i = k
                break
            }
        }
        // Land on a word: prefer the next one if we're in a gap.
        var j = i
        while (j < text.length && !isWordChar(text[j])) j++
        if (j >= text.length) {
            j = i
            while (j >= 0 && !isWordChar(text[j])) j--
            if (j < 0) return null
        }
        var s = j
        var e = j
        while (s > 0 && isWordChar(text[s - 1])) s--
        while (e < text.length && isWordChar(text[e])) e++
        return intArrayOf(s, e)
    }
}
