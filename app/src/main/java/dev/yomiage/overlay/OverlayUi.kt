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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
fun MenuContent(onRead: () -> Unit, onTap: () -> Unit, onScan: () -> Unit, onHide: () -> Unit, onOpen: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = Ink.copy(alpha = 0.97f),
        border = BorderStroke(1.dp, Hairline),
        shadowElevation = 8.dp,
        modifier = Modifier.padding(6.dp),
    ) {
        Column(Modifier.width(IntrinsicSize.Max).padding(vertical = 6.dp)) {
            MenuRow(R.drawable.ic_lines, "Read screen", onRead)
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

/** The floating player: what's being read, ±2 s / ±5 s, play/pause and speed. */
@Composable
fun PlayerBar(
    state: ReaderState,
    progress: Progress,
    onPlayPause: () -> Unit,
    onSeek: (Float) -> Unit,
    onSpeed: (Float) -> Unit,
    onTapMode: () -> Unit,
    onClose: () -> Unit,
    onOpenApp: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
) {
    var showSpeed by remember { mutableStateOf(false) }
    val lastY = remember { FloatArray(1) }
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
            // Drag handle: move the bar up or down.
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(20.dp)
                    .pointerInteropFilter { e ->
                        when (e.actionMasked) {
                            MotionEvent.ACTION_DOWN -> lastY[0] = e.rawY
                            MotionEvent.ACTION_MOVE -> {
                                onDrag(e.rawY - lastY[0])
                                lastY[0] = e.rawY
                            }
                            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> onDragEnd()
                        }
                        true
                    },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .padding(end = 8.dp)
                        .size(width = 36.dp, height = 4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color(0x40FFFFFF))
                )
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
                    SmallIcon(R.drawable.ic_tap, "Read from a spot you tap", onTapMode)
                }
                SmallIcon(R.drawable.ic_close, "Stop", onClose)
            }
            if (state.status == Status.Error) {
                Text(
                    "Open Yomiage",
                    color = Vermilion,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable(onClick = onOpenApp)
                        .padding(vertical = 8.dp, horizontal = 4.dp),
                )
            } else {
                Row(
                    Modifier.fillMaxWidth().padding(end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    SeekButton(R.drawable.ic_rewind, "5", "Back 5 seconds") { onSeek(-5f) }
                    SeekButton(R.drawable.ic_rewind, "2", "Back 2 seconds") { onSeek(-2f) }
                    PlayButton(state.status, onPlayPause)
                    SeekButton(R.drawable.ic_forward, "2", "Forward 2 seconds") { onSeek(2f) }
                    SeekButton(R.drawable.ic_forward, "5", "Forward 5 seconds") { onSeek(5f) }
                    SpeedChip(state.speed, showSpeed) { showSpeed = !showSpeed }
                }
                AnimatedVisibility(showSpeed) {
                    SpeedRow(state.speed, onSpeed)
                }
            }
        }
    }
}

@Composable
private fun SmallIcon(icon: Int, description: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), description, tint = Muted, modifier = Modifier.size(20.dp))
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
