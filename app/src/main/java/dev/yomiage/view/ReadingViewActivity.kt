package dev.yomiage.view

import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.yomiage.R
import dev.yomiage.ai.Explainer
import dev.yomiage.data.HighlightMode
import dev.yomiage.data.Prefs
import dev.yomiage.overlay.PlayerActions
import dev.yomiage.overlay.PlayerBar
import dev.yomiage.reader.BlockKind
import dev.yomiage.reader.HighlightMarks
import dev.yomiage.reader.Chunk
import dev.yomiage.reader.Progress
import dev.yomiage.reader.Reader
import dev.yomiage.reader.ReaderState
import dev.yomiage.reader.Snapshot
import dev.yomiage.reader.Status
import dev.yomiage.service.ReaderService
import dev.yomiage.theme.Look
import dev.yomiage.theme.Styled
import dev.yomiage.ui.YomiageTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Whether the reading view is on screen (the floating player and page highlight step aside). */
object ReadingView {
    internal val _open = MutableStateFlow(false)
    val open: StateFlow<Boolean> = _open
}

// Colors follow the chosen look (classic, Material You or glass).
private val Ink: Color get() = Look.appBackground()
private val Page: Color get() = Look.palette.card
private val Paper: Color get() = Look.palette.text
private val Soft: Color get() = Look.palette.textSoft
private val Muted: Color get() = Look.palette.muted
private val Vermilion: Color get() = Look.palette.accent
private val Sentence: Color get() = Look.palette.accent.copy(alpha = 0.22f)
private val Word: Color get() = Look.palette.accent.copy(alpha = 0.32f)
private val Card: Color get() = Look.palette.cardHigh

/**
 * The text being read, laid out cleanly like a book: big readable type, the sentence being spoken
 * highlighted and kept in view, figures and tables with their plain-language explanations.
 * Tap any paragraph to read from there. Back returns to the page.
 */
class ReadingViewActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            Styled(this)
            YomiageTheme { ReadingScreen(onBack = { backToPage() }) }
        }
    }

    override fun onDestroy() {
        Look.releaseWindow(this)
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        ReadingView._open.value = true
    }

    override fun onPause() {
        ReadingView._open.value = false
        super.onPause()
    }

    private fun backToPage() {
        ReaderService.instance?.keepPageInFront()
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}

private class Para(val block: Int, val text: String, val kind: Int, val waiting: Boolean)

/** Paragraphs in reading order, from what the reader has. */
private fun paragraphs(snap: Snapshot?): List<Para> {
    if (snap == null) return emptyList()
    val out = ArrayList<Para>()
    val seen = HashSet<Int>()
    for (c in snap.chunks) {
        if (!seen.add(c.block)) continue
        val b = snap.blocks.getOrNull(c.block) ?: continue
        out.add(Para(c.block, b.text, b.kind, c.hold))
    }
    return out
}

@Composable
private fun ReadingScreen(onBack: () -> Unit) {
    val state by Reader.state.collectAsState()
    val progress by Reader.progress.collectAsState()
    val revision by Reader.revision.collectAsState()
    val settings by Prefs.settings.collectAsState()
    val snap = remember(revision, state.session, state.total) { Reader.snapshot() }
    val paras = remember(snap) { paragraphs(snap) }
    val list = rememberLazyListState()
    var userAt by remember { mutableLongStateOf(0L) }
    BackHandler(onBack = onBack)

    // Notice when you scroll yourself, so following along pauses for a bit.
    LaunchedEffect(list) {
        list.interactionSource.interactions.collect { if (it is DragInteraction.Start) userAt = SystemClock.uptimeMillis() }
    }
    val current = state.chunk?.block ?: -1
    val currentIndex = paras.indexOfFirst { it.block == current }
    LaunchedEffect(currentIndex, userAt) {
        if (currentIndex < 0) return@LaunchedEffect
        val idle = SystemClock.uptimeMillis() - userAt
        if (idle < 8_000) {
            kotlinx.coroutines.delay(8_000 - idle)
        }
        val visible = list.layoutInfo.visibleItemsInfo
        val shown = visible.any { it.index == currentIndex + 1 } &&
            visible.firstOrNull { it.index == currentIndex + 1 }?.let { it.offset + it.size < list.layoutInfo.viewportEndOffset - 80 } == true &&
            (visible.firstOrNull { it.index == currentIndex + 1 }?.offset ?: 0) > 0
        if (!shown) list.animateScrollToItem(currentIndex + 1, -120)
    }

    Box(Modifier.fillMaxSize().background(Ink).systemBarsPadding()) {
        Column(Modifier.fillMaxSize()) {
            TopBar(onBack, settings.viewTextSp)
            if (paras.isEmpty()) {
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    Text(
                        if (state.status == Status.Idle) "Nothing is being read right now." else "Getting the text…",
                        color = Muted, fontSize = 15.sp,
                    )
                }
            } else {
                LazyColumn(
                    state = list,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentPadding = PaddingValues(start = 22.dp, end = 22.dp, bottom = 260.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    item { Spacer(Modifier.height(4.dp)) }
                    items(paras, key = { it.block }) { p ->
                        Paragraph(
                            p, settings.viewTextSp, settings.highlightMode,
                            chunk = if (p.block == current) state.chunk else null,
                            progress = progress.takeIf { it.session == state.session && it.chunkIndex == state.chunkIndex },
                        ) {
                            userAt = 0L
                            Reader.jumpToBlock(p.block)
                        }
                    }
                }
            }
        }
        // The usual controls, at the bottom.
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            if (currentIndex >= 0 && SystemClock.uptimeMillis() - userAt < 8_000) {
                Pill("Follow along") { userAt = 0L }
                Spacer(Modifier.height(6.dp))
            }
            if (state.status != Status.Idle) {
                PlayerBar(
                    state, progress,
                    PlayerActions(
                        onClose = {
                            Reader.stop()
                            onBack()
                        },
                        onOpenApp = onBack,
                        onExplain = { ReaderService.instance?.explainCurrent() },
                        onBackToPage = { Explainer.backToPage() },
                    ),
                )
            }
        }
    }
}

@Composable
private fun TopBar(onBack: () -> Unit, size: Int) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.clip(RoundedCornerShape(20.dp)).clickable(onClick = onBack).padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painterResource(R.drawable.ic_rewind), null, tint = Paper, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Back to page", color = Paper, fontSize = 15.sp)
        }
        Spacer(Modifier.weight(1f))
        SizeButton("A−", 14) { Prefs.update { it.copy(viewTextSp = (size - 2).coerceAtLeast(14)) } }
        Spacer(Modifier.width(6.dp))
        SizeButton("A+", 19) { Prefs.update { it.copy(viewTextSp = (size + 2).coerceAtMost(32)) } }
    }
}

@Composable
private fun SizeButton(label: String, textSp: Int, onClick: () -> Unit) {
    Box(
        Modifier.size(42.dp).clip(CircleShape).background(Card).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Paper, fontSize = textSp.sp, fontFamily = FontFamily.Serif)
    }
}

@Composable
private fun Pill(label: String, onClick: () -> Unit) {
    Surface(shape = RoundedCornerShape(20.dp), color = Vermilion, modifier = Modifier.clip(RoundedCornerShape(20.dp)).clickable(onClick = onClick)) {
        Text(label, color = Look.palette.onAccent, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp))
    }
}

@Composable
private fun Paragraph(p: Para, size: Int, mode: Int, chunk: Chunk?, progress: Progress?, onTap: () -> Unit) {
    val text = highlighted(p.text, chunk, progress, mode)
    val tap = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onTap)
    when {
        p.waiting -> Box(tap.background(Card).padding(16.dp)) {
            Text(
                if (p.kind == BlockKind.TABLE) "Summing up the table…" else "Looking at ${p.text}…",
                color = Muted, fontSize = (size - 2).sp, fontStyle = FontStyle.Italic,
            )
        }
        p.kind == BlockKind.EXPLAINED || p.kind == BlockKind.FIGURE -> Column(tap.background(Card).padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.ic_sparkle), null, tint = Vermilion, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    if (p.kind == BlockKind.EXPLAINED) "Explained simply" else "Picture",
                    color = Vermilion, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(text, color = Soft, fontSize = (size - 1).sp, lineHeight = (size * 1.5f).sp, fontFamily = FontFamily.Serif)
        }
        p.kind == BlockKind.TABLE -> Column(tap.background(Page).padding(16.dp)) {
            Text("Table", color = Muted, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(6.dp))
            Text(text, color = Soft, fontSize = (size - 2).sp, lineHeight = (size * 1.45f).sp)
        }
        p.kind == BlockKind.HEADING -> Text(
            text, color = Paper, fontSize = (size + 5).sp, lineHeight = (size * 1.35f + 6).sp,
            fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Serif,
            modifier = tap.padding(top = 10.dp, bottom = 2.dp),
        )
        else -> Text(
            text, color = Paper, fontSize = size.sp, lineHeight = (size * 1.55f).sp,
            fontFamily = FontFamily.Serif, modifier = tap.widthIn(max = 720.dp),
        )
    }
}

/** A paragraph as shown: the site's hidden highlight labels taken out, its highlights kept. */
private class Shown(val text: String, val at: IntArray, val marked: List<IntRange>)

private fun shown(text: String): Shown {
    val marks = HighlightMarks.find(text)
    val sb = StringBuilder(text.length)
    val at = IntArray(text.length + 1)
    val marked = ArrayList<IntRange>()
    var from = -1
    fun copy(a: Int, b: Int) {
        for (i in a until b) {
            val c = text[i]
            // Where a label came out, don't leave a double space behind.
            if (c.isWhitespace() && (sb.isEmpty() || sb.last().isWhitespace())) {
                at[i] = sb.length
                continue
            }
            at[i] = sb.length
            sb.append(c)
        }
    }
    var pos = 0
    for (m in marks) {
        copy(pos, m.range.first)
        for (i in m.range) at[i] = sb.length
        if (m.start) {
            if (from < 0) from = sb.length
        } else {
            if (from >= 0 && sb.length > from) marked.add(from until sb.length)
            from = -1
        }
        pos = m.range.last + 1
    }
    copy(pos, text.length)
    at[text.length] = sb.length
    if (from >= 0 && sb.length > from) marked.add(from until sb.length)
    return Shown(sb.toString(), at, marked)
}

private val PageHighlight = Color(0x45F2C230)

/**
 * The paragraph with the page's own highlights tinted, and the sentence being read shaded and/or
 * the word being said marked (your highlight setting).
 */
private fun highlighted(raw: String, chunk: Chunk?, progress: Progress?, mode: Int): AnnotatedString {
    val v = shown(raw)
    val text = v.text
    fun to(i: Int) = v.at[i.coerceIn(0, raw.length)]
    return buildAnnotatedString {
        append(text)
        for (r in v.marked) addStyle(SpanStyle(background = PageHighlight), r.first, r.last + 1)
        if (chunk == null || chunk.hold || chunk.map.isEmpty() || mode == HighlightMode.OFF) return@buildAnnotatedString
        val s = to(chunk.map.first()).coerceIn(0, text.length)
        val e = to(chunk.map.last() + 1).coerceIn(s, text.length)
        if (HighlightMode.sentence(mode)) addStyle(SpanStyle(background = Sentence), s, e)
        if (HighlightMode.word(mode) && progress != null && progress.wordStart >= 0 && progress.wordEnd > progress.wordStart) {
            val ws0 = chunk.map.getOrNull(progress.wordStart)
            val we0 = chunk.map.getOrNull(progress.wordEnd - 1)
            if (ws0 != null && we0 != null && ws0 in raw.indices && we0 in raw.indices && we0 >= ws0) {
                val ws = to(ws0)
                val we = to(we0 + 1).coerceAtLeast(ws + 1).coerceAtMost(text.length)
                if (ws < we) {
                    addStyle(
                        SpanStyle(
                            background = Word, textDecoration = TextDecoration.Underline,
                            fontWeight = FontWeight.SemiBold, color = Paper,
                        ),
                        ws, we,
                    )
                }
            }
        }
    }
}
