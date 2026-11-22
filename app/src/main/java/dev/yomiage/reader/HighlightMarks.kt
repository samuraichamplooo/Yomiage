package dev.yomiage.reader

/**
 * Some sites (eBook readers like McGraw Hill's) put hidden labels around highlighted text for
 * screen readers: "Start yellow highlight … End yellow highlight". A highlight broken into pieces
 * (around a link, or across lines) gets a pair for every piece, which sounds like a stuck record.
 * These are found here so a highlight is announced once, where it begins, and the rest is quiet.
 */
object HighlightMarks {
    private const val COLORS = "yellow|blue|green|pink|purple|orange|red|gray|grey|teal|violet"

    private val re = Regex(
        // "Start yellow highlight", "started highlight", "end of blue highlight", "beginning of highlighted text"
        """\b(start(?:ed|s)?|begin(?:s|ning)?|end(?:ed|s)?)(?:\s+of)?(?:\s+the)?(?:\s+($COLORS))?\s+highlight(?:ed)?(?:\s+(?:text|section|passage))?\b""" +
            // "yellow highlight start", "highlight ends"
            """|\b(?:($COLORS)\s+)?highlight(?:ed)?(?:\s+text)?\s+(start(?:s|ed)?|begin(?:s)?|end(?:s|ed)?)\b""",
        RegexOption.IGNORE_CASE,
    )

    class Mark(val range: IntRange, val start: Boolean, val color: String?)

    fun find(text: String): List<Mark> {
        if (!text.contains("highlight", ignoreCase = true)) return emptyList()
        return re.findAll(text).map { m ->
            val verb = (m.groups[1]?.value ?: m.groups[4]?.value ?: "").lowercase()
            val color = (m.groups[2]?.value ?: m.groups[3]?.value)?.lowercase()
            Mark(m.range, !verb.startsWith("end"), color)
        }.toList()
    }

    /** Whether reading is inside a highlight, carried from paragraph to paragraph. */
    class State {
        var inside = false
        /** Letters read since a highlight ended (a highlight that picks up again soon is the same one). */
        var sinceEnd = Int.MAX_VALUE
    }

    private fun bump(st: State, n: Int) {
        if (st.sinceEnd != Int.MAX_VALUE) st.sinceEnd = (st.sinceEnd.toLong() + n).coerceAtMost(Int.MAX_VALUE - 1L).toInt()
    }

    /**
     * What to say instead of each label in [raw]: "Yellow highlight:" where a highlight begins,
     * nothing for the rest (the end, and a highlight that carries on after a short break).
     */
    fun replacements(raw: String, st: State): List<Pair<IntRange, String>> {
        val marks = find(raw)
        if (marks.isEmpty()) {
            if (!st.inside) bump(st, raw.trim().length + 1)
            return emptyList()
        }
        val out = ArrayList<Pair<IntRange, String>>()
        var pos = 0
        for (m in marks) {
            if (!st.inside) bump(st, raw.substring(pos, m.range.first).trim().length)
            if (m.start) {
                val continuing = st.inside || st.sinceEnd <= 30
                out.add(m.range to if (continuing) "" else announce(m.color))
                st.inside = true
                st.sinceEnd = Int.MAX_VALUE
            } else {
                out.add(m.range to "")
                st.inside = false
                st.sinceEnd = 0
            }
            pos = m.range.last + 1
        }
        if (!st.inside) bump(st, raw.substring(pos).trim().length + 1)
        return out
    }

    private fun announce(color: String?): String =
        if (color != null) "${color.replaceFirstChar { it.uppercase() }} highlight:" else "Highlighted:"
}
