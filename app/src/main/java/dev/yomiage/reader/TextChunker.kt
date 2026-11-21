package dev.yomiage.reader

import android.graphics.Rect
import android.icu.text.BreakIterator
import java.util.Locale

/** A piece of text from the screen (a paragraph, a label, a line of a list) and where it is. */
data class TextBlock(val text: String, val bounds: Rect? = null)

/** One unit of speech: usually a sentence. [gapMs] is the pause added after it. */
data class Chunk(val text: String, val block: Int, val gapMs: Int)

object TextChunker {
    private const val GAP_SPLIT = 40
    private const val GAP_SENTENCE = 90
    private const val GAP_BLOCK = 300

    private val urlRe = Regex("""(https?://|www\.)\S+""", RegexOption.IGNORE_CASE)
    private val emojiRe = Regex(
        "[\\x{1F000}-\\x{1FAFF}\\x{2600}-\\x{27BF}\\x{2B00}-\\x{2BFF}\\x{FE00}-\\x{FE0F}" +
            "\\x{200D}\\x{20E3}\\x{E0020}-\\x{E007F}]"
    )
    private val wsRe = Regex("\\s+")
    private val spaceBeforePunct = Regex("\\s+([.,;:!?])")
    private val abbreviations = setOf(
        "mr.", "mrs.", "ms.", "dr.", "prof.", "st.", "jr.", "sr.", "vs.", "etc.", "inc.", "ltd.",
        "co.", "corp.", "no.", "fig.", "approx.", "dept.", "est.", "gen.", "gov.", "lt.", "mt.", "sgt.",
        "jan.", "feb.", "mar.", "apr.", "aug.", "sept.", "sep.", "oct.", "nov.", "dec.",
    )
    private val initialsRe = Regex("^(?:[A-Za-z]\\.)+$")

    /** True if a "sentence" really ends at an abbreviation like "Dr." or "U.S." and should continue. */
    private fun endsWithAbbreviation(sentence: String): Boolean {
        val last = sentence.trimEnd().substringAfterLast(' ').trimStart('(', '"', '“')
        if (last.isEmpty() || !last.endsWith(".")) return false
        return last.lowercase() in abbreviations || initialsRe.matches(last)
    }

    /** Splits pasted or selected text into paragraphs. */
    fun paragraphs(text: String): List<TextBlock> =
        text.split(Regex("\\n\\s*\\n|\\n")).map { it.trim() }.filter { it.isNotEmpty() }.map { TextBlock(it) }

    fun normalize(s: String, readUrls: Boolean): String {
        var t = s.replace(' ', ' ').replace("​", "")
        if (!readUrls) t = urlRe.replace(t, " link ")
        t = emojiRe.replace(t, " ")
        t = wsRe.replace(t, " ")
        return spaceBeforePunct.replace(t, "$1").trim()
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
    fun build(blocks: List<TextBlock>, startBlock: Int, startChar: Int, readUrls: Boolean): List<Chunk> {
        val out = ArrayList<Chunk>()
        val bi = BreakIterator.getSentenceInstance(Locale.getDefault())
        for (b in startBlock.coerceAtLeast(0) until blocks.size) {
            var raw = blocks[b].text
            if (b == startBlock && startChar > 0 && startChar < raw.length) {
                raw = raw.substring(sentenceStart(raw, startChar))
            }
            val text = normalize(raw, readUrls)
            if (!speakable(text)) continue

            val sentences = ArrayList<String>()
            bi.setText(text)
            var start = bi.first()
            var end = bi.next()
            while (end != BreakIterator.DONE) {
                val s = text.substring(start, end).trim()
                if (s.isNotEmpty()) {
                    if (sentences.isNotEmpty() && endsWithAbbreviation(sentences.last())) {
                        sentences[sentences.size - 1] = sentences.last() + " " + s
                    } else {
                        sentences.add(s)
                    }
                }
                start = end
                end = bi.next()
            }
            if (sentences.isEmpty()) sentences.add(text)

            for ((si, sentence) in sentences.withIndex()) {
                val lastInBlock = si == sentences.size - 1
                var rest = sentence
                while (rest.length > maxLen(out.size)) {
                    val cut = findCut(rest, maxLen(out.size))
                    val piece = rest.substring(0, cut).trim()
                    if (piece.isNotEmpty() && speakable(piece)) out.add(Chunk(piece, b, GAP_SPLIT))
                    rest = rest.substring(cut).trim()
                }
                if (speakable(rest)) {
                    out.add(Chunk(rest, b, if (lastInBlock) GAP_BLOCK else GAP_SENTENCE))
                } else if (lastInBlock && out.isNotEmpty() && out.last().block == b) {
                    out[out.size - 1] = out.last().copy(gapMs = GAP_BLOCK)
                }
            }
        }
        return out
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
