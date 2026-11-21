package dev.yomiage.ui

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import dev.yomiage.BuildConfigInfo
import dev.yomiage.R
import dev.yomiage.data.Accent
import dev.yomiage.data.Prefs
import dev.yomiage.data.Settings as YSettings
import dev.yomiage.data.Voice
import dev.yomiage.data.Voices
import dev.yomiage.overlay.PlayerBar
import dev.yomiage.overlay.speedLabel
import dev.yomiage.reader.Reader
import dev.yomiage.reader.Status
import dev.yomiage.reader.TextChunker
import dev.yomiage.service.ReaderService
import dev.yomiage.tts.ModelManager
import dev.yomiage.tts.ModelStatus
import java.util.Locale

private const val SAMPLE = "Yomiage reads whatever is on your screen with natural Kokoro voices. " +
    "Tap the bubble to read a whole page, or tap a spot to start right there. " +
    "Use the arrows to jump back or ahead two or five seconds, and pick a speed that suits you."

@Composable
fun HomeScreen(pendingText: MutableState<String?>) {
    val context = LocalContext.current
    val model by ModelManager.status.collectAsState()
    val running by ReaderService.running.collectAsState()
    val settings by Prefs.settings.collectAsState()
    val reader by Reader.state.collectAsState()
    var batteryOk by remember { mutableStateOf(isBatteryUnrestricted(context)) }
    var text by rememberSaveable { mutableStateOf(SAMPLE) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        ModelManager.refresh(context)
        batteryOk = isBatteryUnrestricted(context)
    }

    val modelReady = model is ModelStatus.Ready
    LaunchedEffect(pendingText.value, modelReady) {
        val t = pendingText.value ?: return@LaunchedEffect
        text = t
        if (modelReady) {
            Reader.read(TextChunker.paragraphs(t))
            pendingText.value = null
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(InkBlack)
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .padding(horizontal = 18.dp),
    ) {
        Header()

        val allSet = modelReady && running && batteryOk
        if (!allSet) {
            SetupCard(context, model, running, batteryOk)
        } else {
            HowToCard()
        }

        SectionLabel("Try it")
        Group {
            Column(Modifier.padding(16.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    minLines = 4,
                    maxLines = 10,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Seal,
                        unfocusedBorderColor = Color(0xFF3A3530),
                        cursorColor = Seal,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = { Reader.read(TextChunker.paragraphs(text)) },
                        enabled = modelReady && text.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = Seal, contentColor = PaperWhite),
                    ) {
                        Icon(painterResource(R.drawable.ic_play), null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Read this")
                    }
                    Spacer(Modifier.width(10.dp))
                    TextButton(onClick = { text = "" }) { Text("Clear", color = Faint) }
                }
                if (!running && reader.status != Status.Idle) {
                    Spacer(Modifier.height(12.dp))
                    // The floating player only exists while screen reading is on, so show it here too.
                    PlayerBar(
                        state = reader,
                        onPlayPause = { Reader.togglePlay() },
                        onSeek = { Reader.seekBy(it) },
                        onSpeed = { Reader.setSpeed(it) },
                        onTapMode = {
                            Toast.makeText(context, "Turn on screen reading first", Toast.LENGTH_SHORT).show()
                        },
                        onClose = { Reader.stop() },
                        onOpenApp = { Reader.stop() },
                        onDrag = {},
                        onDragEnd = {},
                    )
                }
            }
        }

        VoiceSection(settings, modelReady)
        ReadingSection(settings)
        AdvancedSection(context, settings, modelReady)

        Text(
            "Yomiage ${BuildConfigInfo.versionName(context)} · Kokoro-82M voices (Apache 2.0) running on-device with sherpa-onnx.",
            color = Faint,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 28.dp, bottom = 36.dp, start = 4.dp),
        )
    }
}

@Composable
private fun Header() {
    Row(
        Modifier.padding(top = 28.dp, bottom = 8.dp, start = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(46.dp)
                .clip(CircleShape)
                .background(Seal),
            contentAlignment = Alignment.Center,
        ) {
            Text("読", color = PaperWhite, fontSize = 22.sp, fontWeight = FontWeight.Medium)
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text("Yomiage", color = PaperWhite, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
            Text("読み上げ · reads your screen aloud", color = Faint, fontSize = 13.sp)
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        color = Seal,
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.padding(start = 6.dp, top = 24.dp, bottom = 10.dp),
    )
}

@Composable
private fun Group(content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(24.dp), color = Card, modifier = Modifier.fillMaxWidth()) {
        Column(content = content)
    }
}

@Composable
private fun Divider() {
    HorizontalDivider(thickness = 1.dp, color = InkBlack)
}

// -------------------------------------------------------------------- setup

@Composable
private fun SetupCard(context: Context, model: ModelStatus, running: Boolean, batteryOk: Boolean) {
    SectionLabel("Set up")
    Group {
        SetupStep(
            number = 1,
            title = "Download the voices",
            done = model is ModelStatus.Ready,
            body = "All 54 Kokoro voices, about 330 MB, downloaded once. Wi-Fi is best. After this, reading works offline.",
        ) {
            when (model) {
                is ModelStatus.Downloading -> {
                    val mb = model.bytes / 1_048_576f
                    val total = model.total / 1_048_576f
                    ProgressLine(
                        if (model.total > 0) mb / total else null,
                        if (model.total > 0) String.format(Locale.US, "%.0f of %.0f MB", mb, total)
                        else String.format(Locale.US, "%.0f MB", mb),
                    )
                }
                is ModelStatus.Unpacking -> ProgressLine(model.fraction, "Unpacking… ${(model.fraction * 100).toInt()}%")
                is ModelStatus.Failed -> {
                    Text(model.message, color = Color(0xFFFFB4A6), fontSize = 13.sp)
                    Spacer(Modifier.height(8.dp))
                    StepButton("Try again") { ModelManager.start(context) }
                }
                ModelStatus.Missing -> StepButton("Download") { ModelManager.start(context) }
                ModelStatus.Ready -> {}
            }
        }
        Divider()
        SetupStep(
            number = 2,
            title = "Turn on screen reading",
            done = running,
            body = "In Accessibility, open Installed apps → Yomiage and switch it on. If the switch is greyed out, " +
                "open App info, tap ⋮ in the top corner, choose Allow restricted settings, then try again.",
        ) {
            if (!running) {
                Row {
                    StepButton("Open Accessibility") { openAccessibility(context) }
                    Spacer(Modifier.width(8.dp))
                    StepButton("App info", outlined = true) {
                        open(context, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                    }
                }
            }
        }
        Divider()
        SetupStep(
            number = 3,
            title = "Keep it awake",
            done = batteryOk,
            body = "Stops the phone from putting Yomiage to sleep in the background.",
        ) {
            if (!batteryOk) {
                StepButton("Allow") {
                    open(
                        context,
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")),
                    )
                }
            }
        }
    }
}

@Composable
private fun SetupStep(number: Int, title: String, done: Boolean, body: String, action: @Composable ColumnScope.() -> Unit) {
    Row(Modifier.padding(16.dp)) {
        Box(
            Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(if (done) Seal else CardHigh),
            contentAlignment = Alignment.Center,
        ) {
            if (done) {
                Icon(painterResource(R.drawable.ic_check), null, tint = PaperWhite, modifier = Modifier.size(16.dp))
            } else {
                Text("$number", color = PaperWhite, fontSize = 13.sp)
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, color = PaperWhite, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            if (!done) {
                Spacer(Modifier.height(4.dp))
                Text(body, color = Faint, fontSize = 13.sp, lineHeight = 18.sp)
                Spacer(Modifier.height(10.dp))
                action()
            }
        }
    }
}

@Composable
private fun ProgressLine(fraction: Float?, label: String) {
    if (fraction != null) {
        LinearProgressIndicator(
            progress = { fraction.coerceIn(0f, 1f) },
            color = Seal,
            trackColor = CardHigh,
            modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
        )
    } else {
        LinearProgressIndicator(
            color = Seal,
            trackColor = CardHigh,
            modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
        )
    }
    Spacer(Modifier.height(6.dp))
    Text(label, color = Faint, fontSize = 12.sp)
}

@Composable
private fun StepButton(label: String, outlined: Boolean = false, onClick: () -> Unit) {
    if (outlined) {
        OutlinedButton(onClick = onClick) { Text(label, color = PaperWhite) }
    } else {
        Button(
            onClick = onClick,
            colors = ButtonDefaults.buttonColors(containerColor = Seal, contentColor = PaperWhite),
        ) { Text(label) }
    }
}

@Composable
private fun HowToCard() {
    SectionLabel("How to use it")
    Group {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Tip("読", "Tap the bubble, then Read screen to hear the page from the top.")
            Tip("☝", "Tap to start, then touch any sentence to read from there. Long-press the bubble for a shortcut.")
            Tip("⟲", "In the player, use 2 and 5 to jump back or ahead, and the speed chip to change speed.")
            Tip("⋮", "Select text in any app and choose Read aloud.")
            Tip("⇅", "Drag the bubble to either edge. Drag the player by its handle.")
        }
    }
}

@Composable
private fun Tip(symbol: String, text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Text(symbol, color = Seal, fontSize = 15.sp, modifier = Modifier.width(26.dp))
        Text(text, color = Color(0xFFD9D1C8), fontSize = 14.sp, lineHeight = 19.sp)
    }
}

// -------------------------------------------------------------------- voices

@Composable
private fun VoiceSection(settings: YSettings, modelReady: Boolean) {
    var allLanguages by rememberSaveable { mutableStateOf(false) }
    val selected = Voices.byId(settings.voiceId)
    SectionLabel("Voice")
    Group {
        Row(
            Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(selected.name, color = PaperWhite, fontSize = 18.sp, fontWeight = FontWeight.Medium)
                Text("${selected.accent.label} · ${if (selected.female) "Female" else "Male"}", color = Faint, fontSize = 13.sp)
            }
            FilterChip("English", !allLanguages) { allLanguages = false }
            Spacer(Modifier.width(6.dp))
            FilterChip("All", allLanguages) { allLanguages = true }
        }
        if (!modelReady) {
            Text(
                "Download the voices to hear previews.",
                color = Faint, fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            )
        }
        val accents = if (allLanguages) Accent.entries else listOf(Accent.US, Accent.UK)
        for (accent in accents) {
            val voices = Voices.all.filter { it.accent == accent }
                .sortedWith(compareBy<Voice>({ !it.top }, { it.name }))
            Text(
                accent.label + (accent.note?.let { " · $it" } ?: ""),
                color = Seal, fontSize = 12.sp,
                modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 4.dp),
            )
            for (v in voices) {
                VoiceRow(v, v.id == selected.id, modelReady)
            }
        }
        Spacer(Modifier.height(10.dp))
    }
}

@Composable
private fun FilterChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) Seal else CardHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(label, color = PaperWhite, fontSize = 13.sp)
    }
}

@Composable
private fun VoiceRow(v: Voice, selected: Boolean, modelReady: Boolean) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { Reader.setVoice(v) }
            .padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(20.dp)
                .clip(CircleShape)
                .border(2.dp, if (selected) Seal else Color(0xFF4A443E), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Box(Modifier.size(10.dp).clip(CircleShape).background(Seal))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(v.name, color = PaperWhite, fontSize = 15.sp)
                if (v.top) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Top pick",
                        color = Seal,
                        fontSize = 11.sp,
                        modifier = Modifier
                            .border(1.dp, Seal.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                            .padding(horizontal = 6.dp, vertical = 1.dp),
                    )
                }
            }
            Text(
                (if (v.female) "Female" else "Male") + " · " + v.code,
                color = Faint, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
            )
        }
        Box(
            Modifier
                .size(44.dp)
                .clip(CircleShape)
                .clickable(enabled = modelReady) { Reader.preview(v) },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painterResource(R.drawable.ic_play), "Preview ${v.name}",
                tint = if (modelReady) PaperWhite else Color(0xFF4A443E),
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

// -------------------------------------------------------------------- settings

@Composable
private fun ReadingSection(settings: YSettings) {
    SectionLabel("Reading")
    Group {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Speed", color = PaperWhite, fontSize = 16.sp, modifier = Modifier.weight(1f))
                Text(speedLabel(settings.speed), color = Seal, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            }
            Slider(
                value = settings.speed,
                onValueChange = { Reader.setSpeed(it) },
                valueRange = 0.5f..3f,
                steps = 49,
                colors = SliderDefaults.colors(
                    thumbColor = Seal,
                    activeTrackColor = Seal,
                    inactiveTrackColor = CardHigh,
                    activeTickColor = Color.Transparent,
                    inactiveTickColor = Color.Transparent,
                ),
            )
        }
        Divider()
        SwitchRow("Outline what's being read", "Draws a box around the paragraph on screen.", settings.highlight) { on ->
            Prefs.update { it.copy(highlight = on) }
        }
        Divider()
        SwitchRow("Keep reading below the screen", "On long web pages, keep going past the bottom when the app allows it.", settings.readBelow) { on ->
            Prefs.update { it.copy(readBelow = on) }
        }
        Divider()
        SwitchRow("Scan images when there's no text", "Uses on-device text recognition for pictures, PDFs and apps that hide their text.", settings.autoOcr) { on ->
            Prefs.update { it.copy(autoOcr = on) }
        }
        Divider()
        SwitchRow("Floating bubble", "Turn off to start reading only from the Accessibility button or Read aloud.", settings.showBubble) { on ->
            Prefs.update { it.copy(showBubble = on) }
        }
        Divider()
        SwitchRow("Read web addresses", "When off, a link is read as \"link\".", settings.readUrls) { on ->
            Prefs.update { it.copy(readUrls = on) }
        }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = PaperWhite, fontSize = 15.sp)
            Text(subtitle, color = Faint, fontSize = 12.sp, lineHeight = 16.sp)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = PaperWhite,
                checkedTrackColor = Seal,
                uncheckedThumbColor = Faint,
                uncheckedTrackColor = CardHigh,
                uncheckedBorderColor = Color(0xFF3A3530),
            ),
        )
    }
}

@Composable
private fun AdvancedSection(context: Context, settings: YSettings, modelReady: Boolean) {
    SectionLabel("Performance")
    Group {
        Column(Modifier.padding(16.dp)) {
            Text("Speech threads", color = PaperWhite, fontSize = 15.sp)
            Text(
                "How many processor cores Kokoro uses. More starts sentences faster; 4 is a good balance.",
                color = Faint, fontSize = 12.sp, lineHeight = 16.sp,
            )
            Spacer(Modifier.height(10.dp))
            Row {
                for (n in listOf(2, 4, 6, 8)) {
                    FilterChip("$n", settings.threads == n) { Prefs.update { it.copy(threads = n) } }
                    Spacer(Modifier.width(8.dp))
                }
            }
        }
        if (modelReady) {
            Divider()
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable {
                        Reader.stop()
                        ModelManager.delete(context)
                    }
                    .padding(16.dp),
            ) {
                Column {
                    Text("Delete voice pack", color = Color(0xFFFFB4A6), fontSize = 15.sp)
                    Text("Frees about 350 MB. You can download it again any time.", color = Faint, fontSize = 12.sp)
                }
            }
        }
    }
}

// -------------------------------------------------------------------- helpers

private fun isBatteryUnrestricted(context: Context): Boolean =
    context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

private fun open(context: Context, intent: Intent) {
    try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "Couldn't open that screen", Toast.LENGTH_SHORT).show()
    } catch (e: SecurityException) {
        Toast.makeText(context, "Couldn't open that screen", Toast.LENGTH_SHORT).show()
    }
}

private fun openAccessibility(context: Context) {
    // Jump straight to Yomiage's switch where the phone allows it, otherwise to the Accessibility list.
    val component = ComponentName(context, ReaderService::class.java).flattenToString()
    try {
        context.startActivity(
            Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
                .putExtra(Intent.EXTRA_COMPONENT_NAME, component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (e: Exception) {
        open(context, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }
}
