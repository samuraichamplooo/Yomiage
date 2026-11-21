package dev.yomiage.overlay

import android.graphics.Rect
import android.graphics.RectF
import android.view.MotionEvent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.pointerInteropFilter
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.yomiage.R
import dev.yomiage.reader.Progress
import dev.yomiage.reader.Reader
import dev.yomiage.reader.ReaderState
import dev.yomiage.reader.Status
import java.util.Locale

val Ink = Color(0xFF141211)
val Paper = Color(0xFFF3EDE4)
val Vermilion = Color(0xFFD8462B)
val Muted = Color(0xFF9A928A)
private val Hairline = Color(0x26FFFFFF)

@Composable
fun BubbleContent(faded: Boolean) {
    val alpha by animateFloatAsState(if (faded) 0.45f else 1f, tween(400), label = "bubble")
    Box(
        Modifier
            .size(52.dp)
            .padding(2.dp)
            .graphicsLayer { this.alpha = alpha }
            .clip(CircleShape)
            .background(Ink.copy(alpha = 0.94f))
            .border(2.dp, Vermilion, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text("読", color = Paper, fontSize = 21.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun MenuContent(
    onRead: () -> Unit,
    onSimple: () -> Unit,
    onTap: () -> Unit,
    onScan: () -> Unit,
    onHide: () -> Unit,
    onOpen: () -> Unit,
    onView: () -> Unit = {},
) {
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = Ink.copy(alpha = 0.97f),
        border = BorderStroke(1.dp, Hairline),
        shadowElevation = 8.dp,
        modifier = Modifier.padding(6.dp),
    ) {
        Column(Modifier.width(IntrinsicSize.Max).padding(vertical = 6.dp)) {
            MenuRow(R.drawable.ic_lines, "Read screen", onRead)
            MenuRow(R.drawable.ic_book, "Read in reading view", onView)
            MenuRow(R.drawable.ic_sparkle, "Read simplified", onSimple)
            MenuRow(R.drawable.ic_tap, "Tap to start", onTap)
            MenuRow(R.drawable.ic_scan, "Scan text in images", onScan)
            HorizontalDivider(color = Hairline, modifier = Modifier.padding(vertical = 4.dp))
            MenuRow(R.drawable.ic_hide, "Hide bubble", onHide)
            MenuRow(R.drawable.ic_settings, "Yomiage settings", onOpen)
        }
    }
}

@Composable
private fun MenuRow(icon: Int, label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(icon), null, tint = Vermilion, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Text(label, color = Paper, fontSize = 15.sp)
    }
}

/** Everything the player can do. Optional actions are hidden when null. */
class PlayerActions(
    val onPlayPause: () -> Unit = { Reader.togglePlay() },
    val onSeek: (Float) -> Unit = { Reader.seekBy(it) },
    val onSpeed: (Float) -> Unit = { Reader.setSpeed(it) },
    val onClose: () -> Unit = { Reader.stop() },
    val onOpenApp: () -> Unit = {},
    val onTapMode: (() -> Unit)? = null,
    val onExplain: (() -> Unit)? = null,
    /** Opens the text in Yomiage's reading view. */
    val onReadingView: (() -> Unit)? = null,
    val onBackToPage: (() -> Unit)? = null,
    val onMinimize: (() -> Unit)? = null,
    val onHide: (() -> Unit)? = null,
    val onExpand: () -> Unit = {},
    val onToggleText: (() -> Unit)? = null,
    val onDrag: (Float, Float) -> Unit = { _, _ -> },
    val onDragEnd: () -> Unit = {},
)

/** A drag handle that reports finger movement in screen pixels. */
private fun Modifier.dragHandle(actions: PlayerActions, last: FloatArray): Modifier =
    pointerInteropFilter { e ->
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                last[0] = e.rawX
                last[1] = e.rawY
            }
            MotionEvent.ACTION_MOVE -> {
                actions.onDrag(e.rawX - last[0], e.rawY - last[1])
                last[0] = e.rawX
                last[1] = e.rawY
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> actions.onDragEnd()
        }
        true
    }

/** The floating player: what's being read, ±2 s / ±5 s, play/pause and speed. */
@Composable
fun PlayerBar(
    state: ReaderState,
    progress: Progress,
    actions: PlayerActions,
    showText: Boolean = false,
) {
    var showSpeed by remember { mutableStateOf(false) }
    val last = remember { FloatArray(2) }
    Surface(
        shape = RoundedCornerShape(26.dp),
        color = Ink.copy(alpha = 0.96f),
        border = BorderStroke(1.dp, Hairline),
        shadowElevation = 10.dp,
        modifier = Modifier
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .widthIn(max = 560.dp)
            .fillMaxWidth(),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, bottom = 10.dp)) {
            // Drag handle (move the bar up or down), with a hide button at the end.
            Row(Modifier.fillMaxWidth().height(30.dp), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.width(30.dp))
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .dragHandle(actions, last),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier
                            .size(width = 36.dp, height = 4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(Color(0x40FFFFFF))
                    )
                }
                actions.onHide?.let { hide ->
                    Box(
                        Modifier
                            .size(30.dp)
                            .clip(CircleShape)
                            .clickable(onClick = hide),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(painterResource(R.drawable.ic_hide), "Hide the player", tint = Muted, modifier = Modifier.size(17.dp))
                    }
                } ?: Spacer(Modifier.width(30.dp))
            }
            if (state.total > 1) {
                LinearProgressIndicator(
                    progress = { (state.index.toFloat() / state.total).coerceIn(0f, 1f) },
                    color = Vermilion,
                    trackColor = Color(0x22FFFFFF),
                    modifier = Modifier
                        .padding(end = 8.dp)
                        .fillMaxWidth()
                        .height(2.dp),
                )
            }
            if (state.label != null) {
                Row(Modifier.padding(top = 8.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(painterResource(R.drawable.ic_sparkle), null, tint = Vermilion, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(state.label, color = Vermilion, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.weight(1f))
                    actions.onToggleText?.let {
                        Pill(if (showText) "Hide text" else "Show text", onClick = it)
                        Spacer(Modifier.width(6.dp))
                    }
                    actions.onBackToPage?.let { back ->
                        if (state.label == "Explanation") Pill("Back to page", accent = true, onClick = back)
                    }
                }
            }
            if (showText && state.label != null && state.blocks.isNotEmpty()) {
                TextPanel(state, progress)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                val caption = when {
                    state.status == Status.Error -> AnnotatedString(state.message ?: "Something went wrong.")
                    state.status == Status.Finished -> AnnotatedString("Done. Press play to hear it again.")
                    else -> karaoke(state, progress)
                }
                Text(
                    caption,
                    color = if (state.status == Status.Error) Muted else Paper,
                    fontSize = 14.sp,
                    lineHeight = 19.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = 8.dp),
                )
                if (state.status != Status.Error) {
                    actions.onReadingView?.let { SmallIcon(R.drawable.ic_book, "Open in reading view", it) }
                    actions.onTapMode?.let { SmallIcon(R.drawable.ic_tap, "Read from a spot you tap", it) }
                    actions.onExplain?.let {
                        if (state.label != "Explanation" && state.chunk != null) {
                            SmallIcon(R.drawable.ic_sparkle, "Explain this simply", it, tint = Vermilion)
                        }
                    }
                }
                actions.onMinimize?.let { SmallIcon(R.drawable.ic_minimize, "Shrink the player", it) }
                SmallIcon(R.drawable.ic_close, "Stop", actions.onClose)
            }
            if (state.status == Status.Error) {
                Text(
                    "Open Yomiage",
                    color = Vermilion,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(onClick = actions.onOpenApp)
                        .padding(vertical = 8.dp, horizontal = 4.dp),
                )
            } else {
                Row(
                    Modifier.fillMaxWidth().padding(end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    SeekButton(R.drawable.ic_rewind, "5", "Back 5 seconds") { actions.onSeek(-5f) }
                    SeekButton(R.drawable.ic_rewind, "2", "Back 2 seconds") { actions.onSeek(-2f) }
                    PlayButton(state.status, actions.onPlayPause)
                    SeekButton(R.drawable.ic_forward, "2", "Forward 2 seconds") { actions.onSeek(2f) }
                    SeekButton(R.drawable.ic_forward, "5", "Forward 5 seconds") { actions.onSeek(5f) }
                    SpeedChip(state.speed, showSpeed) { showSpeed = !showSpeed }
                }
                AnimatedVisibility(showSpeed) {
                    SpeedRow(state.speed, actions.onSpeed)
                }
            }
        }
    }
}

/** The shrunk player: drag grip, play/pause with a progress ring, and expand. */
@Composable
fun MiniPlayer(state: ReaderState, actions: PlayerActions) {
    val last = remember { FloatArray(2) }
    Surface(
        shape = RoundedCornerShape(30.dp),
        color = Ink.copy(alpha = 0.9f),
        border = BorderStroke(1.dp, Hairline),
        shadowElevation = 8.dp,
        modifier = Modifier
            .padding(6.dp),
    ) {
        Row(Modifier.padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(width = 22.dp, height = 48.dp)
                    .dragHandle(actions, last),
                contentAlignment = Alignment.Center,
            ) {
                Icon(painterResource(R.drawable.ic_drag), "Move", tint = Muted, modifier = Modifier.size(18.dp))
            }
            val playing = state.status == Status.Playing || state.status == Status.Buffering || state.status == Status.Preparing
            Box(
                Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(Vermilion)
                    .clickable(onClick = actions.onPlayPause),
                contentAlignment = Alignment.Center,
            ) {
                if (state.total > 0) {
                    CircularProgressIndicator(
                        progress = { (state.index.toFloat() / state.total).coerceIn(0f, 1f) },
                        color = Paper,
                        trackColor = Color(0x33FFFFFF),
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(44.dp),
                    )
                }
                Icon(
                    painterResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play),
                    if (playing) "Pause" else "Play",
                    tint = Paper,
                    modifier = Modifier.size(24.dp),
                )
            }
            SmallIcon(R.drawable.ic_expand, "Show the full player", actions.onExpand)
            actions.onHide?.let { SmallIcon(R.drawable.ic_hide, "Hide the player", it) }
        }
    }
}

/** The simplified text or explanation, with the sentence being read marked. Scrolls along. */
@Composable
private fun TextPanel(state: ReaderState, progress: Progress) {
    val chunk = state.chunk
    val list = rememberLazyListState()
    LaunchedEffect(chunk?.block) {
        if (chunk != null) list.animateScrollToItem(chunk.block.coerceAtLeast(0))
    }
    LazyColumn(
        state = list,
        modifier = Modifier
            .padding(top = 8.dp, end = 8.dp)
            .fillMaxWidth()
            .heightIn(max = 220.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0x12FFFFFF))
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        itemsIndexed(state.blocks) { i, b ->
            val text = if (chunk != null && chunk.block == i && chunk.map.isNotEmpty()) {
                val s0 = chunk.map.first().coerceIn(0, b.text.length)
                val e0 = (chunk.map.last() + 1).coerceIn(s0, b.text.length)
                buildAnnotatedString {
                    append(b.text.substring(0, s0))
                    withStyle(SpanStyle(background = Vermilion.copy(alpha = 0.22f))) {
                        val ws = progress.wordStart
                        val we = progress.wordEnd
                        if (progress.session == state.session && progress.chunkIndex == state.chunkIndex &&
                            ws >= 0 && we <= chunk.map.size && ws < we
                        ) {
                            val a = chunk.map[ws].coerceIn(s0, e0)
                            val z = (chunk.map[we - 1] + 1).coerceIn(a, e0)
                            append(b.text.substring(s0, a))
                            withStyle(SpanStyle(background = Vermilion.copy(alpha = 0.6f))) { append(b.text.substring(a, z)) }
                            append(b.text.substring(z, e0))
                        } else {
                            append(b.text.substring(s0, e0))
                        }
                    }
                    append(b.text.substring(e0))
                }
            } else {
                AnnotatedString(b.text)
            }
            Text(
                text,
                color = Paper,
                fontSize = 15.sp,
                lineHeight = 22.sp,
                modifier = Modifier.padding(vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun Pill(label: String, accent: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (accent) Vermilion else Color(0x1AFFFFFF))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Text(label, color = Paper, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun SmallIcon(icon: Int, description: String, onClick: () -> Unit, tint: Color = Muted) {
    Box(
        Modifier
            .size(38.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), description, tint = tint, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun SeekButton(icon: Int, label: String, description: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(46.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), description, tint = Paper, modifier = Modifier.size(32.dp))
        Text(
            label,
            color = Paper,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.offset(y = 2.dp),
        )
    }
}

@Composable
private fun PlayButton(status: Status, onClick: () -> Unit) {
    val playing = status == Status.Playing || status == Status.Buffering || status == Status.Preparing
    val waiting = status == Status.Buffering || status == Status.Preparing
    Box(
        Modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(Vermilion)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (waiting) {
            CircularProgressIndicator(
                color = Paper,
                strokeWidth = 2.dp,
                modifier = Modifier.size(50.dp),
            )
        }
        Icon(
            painterResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play),
            if (playing) "Pause" else "Play",
            tint = Paper,
            modifier = Modifier.size(28.dp),
        )
    }
}

/** The sentence with the word being spoken marked, trimmed so that word stays in view. */
private fun karaoke(state: ReaderState, progress: Progress): AnnotatedString {
    val chunk = state.chunk
    if (chunk == null || progress.session != state.session || progress.chunkIndex != state.chunkIndex ||
        progress.wordStart < 0 || progress.wordEnd > chunk.text.length
    ) {
        return AnnotatedString(chunk?.text ?: state.caption)
    }
    val t = chunk.text
    val ws = progress.wordStart
    val we = progress.wordEnd
    var from = 0
    if (ws > 70) {
        val sp = t.lastIndexOf(' ', ws - 45)
        from = if (sp < 0) 0 else sp + 1
    }
    return buildAnnotatedString {
        if (from > 0) append("… ")
        append(t.substring(from, ws))
        withStyle(SpanStyle(background = Vermilion.copy(alpha = 0.6f), fontWeight = FontWeight.Medium)) {
            append(t.substring(ws, we))
        }
        withStyle(SpanStyle(color = Paper.copy(alpha = 0.6f))) { append(t.substring(we)) }
    }
}

fun speedLabel(speed: Float): String {
    val s = String.format(Locale.US, "%.2f", speed).trimEnd('0').trimEnd('.')
    return "$s×"
}

@Composable
private fun SpeedChip(speed: Float, open: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(14.dp))
            .border(1.dp, if (open) Vermilion else Hairline, RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 7.dp),
    ) {
        Text(speedLabel(speed), color = Paper, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}

private val presets = listOf(0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f)

@Composable
private fun SpeedRow(speed: Float, onSpeed: (Float) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StepButton("−") { onSpeed(speed - 0.05f) }
        Row(
            Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Spacer(Modifier.width(2.dp))
            for (p in presets) {
                val selected = kotlin.math.abs(p - speed) < 0.01f
                Box(
                    Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (selected) Vermilion else Color(0x14FFFFFF))
                        .clickable { onSpeed(p) }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Text(speedLabel(p), color = Paper, fontSize = 12.sp)
                }
            }
            Spacer(Modifier.width(2.dp))
        }
        StepButton("+") { onSpeed(speed + 0.05f) }
    }
}

@Composable
private fun StepButton(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(36.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Paper, fontSize = 20.sp)
    }
}

/** Full-screen layer that waits for one tap and reports where it landed (screen pixels). */
@Composable
fun TapCatcher(onTap: (Int, Int) -> Unit, onCancel: () -> Unit) {
    val view = LocalView.current
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0x33000000))
            .pointerInput(Unit) {
                detectTapGestures { off ->
                    val loc = IntArray(2)
                    view.getLocationOnScreen(loc)
                    onTap(off.x.toInt() + loc[0], off.y.toInt() + loc[1])
                }
            },
        contentAlignment = Alignment.TopCenter,
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = Ink.copy(alpha = 0.96f),
            border = BorderStroke(1.dp, Hairline),
            shadowElevation = 8.dp,
            modifier = Modifier.padding(top = 56.dp, start = 16.dp, end = 16.dp),
        ) {
            Row(
                Modifier.padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(painterResource(R.drawable.ic_tap), null, tint = Vermilion, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Text("Tap where you want me to start", color = Paper, fontSize = 14.sp)
                Spacer(Modifier.width(6.dp))
                Text(
                    "Cancel",
                    color = Vermilion,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .clickable(onClick = onCancel)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                )
            }
        }
    }
}

/** Marks the sentence being read (a soft wash), the word being spoken (stronger), or a paragraph outline. */
@Composable
fun HighlightLayer(sentence: List<RectF>, word: List<RectF>, outline: Rect?) {
    val view = LocalView.current
    Canvas(Modifier.fillMaxSize()) {
        if (sentence.isEmpty() && word.isEmpty() && outline == null) return@Canvas
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        val ox = loc[0].toFloat()
        val oy = loc[1].toFloat()
        val pad = 2.dp.toPx()
        val radius = CornerRadius(4.dp.toPx())
        for (r in sentence) {
            drawRoundRect(
                Vermilion.copy(alpha = 0.16f),
                Offset(r.left - ox - pad, r.top - oy - pad),
                Size(r.width() + pad * 2, r.height() + pad * 2),
                radius,
            )
        }
        for (r in word) {
            drawRoundRect(
                Vermilion.copy(alpha = 0.42f),
                Offset(r.left - ox - pad, r.top - oy - pad),
                Size(r.width() + pad * 2, r.height() + pad * 2),
                radius,
            )
            drawRect(
                Vermilion,
                Offset(r.left - ox - pad, r.bottom - oy + pad),
                Size(r.width() + pad * 2, 2.dp.toPx()),
            )
        }
        if (outline != null) {
            val p = 6.dp.toPx()
            val tl = Offset(outline.left - ox - p, outline.top - oy - p)
            val sz = Size(outline.width() + p * 2, outline.height() + p * 2)
            drawRoundRect(Vermilion.copy(alpha = 0.10f), tl, sz, CornerRadius(10.dp.toPx()))
            drawRoundRect(Vermilion.copy(alpha = 0.8f), tl, sz, CornerRadius(10.dp.toPx()), style = Stroke(width = 2.dp.toPx()))
        }
    }
}
