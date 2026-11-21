package dev.yomiage.ui

import android.Manifest
import android.app.StatusBarManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import dev.yomiage.BuildConfigInfo
import dev.yomiage.R
import dev.yomiage.ai.Ai
import dev.yomiage.ai.Explainer
import dev.yomiage.ai.NanoState
import dev.yomiage.ai.OnDevice
import dev.yomiage.data.Accent
import dev.yomiage.data.BubbleMode
import dev.yomiage.data.HighlightMode
import dev.yomiage.data.PlayerVisibility
import dev.yomiage.data.Prefs
import dev.yomiage.data.Settings as YSettings
import dev.yomiage.data.Voice
import dev.yomiage.data.Voices
import dev.yomiage.overlay.PlayerActions
import dev.yomiage.overlay.PlayerBar
import dev.yomiage.overlay.speedLabel
import dev.yomiage.reader.Reader
import dev.yomiage.reader.Status
import dev.yomiage.reader.TextChunker
import dev.yomiage.service.ReaderService
import dev.yomiage.tile.ReadTileService
import dev.yomiage.tile.TapTileService
import dev.yomiage.tts.ModelManager
import dev.yomiage.tts.ModelStatus
import java.util.Locale
import kotlinx.coroutines.launch

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
    val progress by Reader.progress.collectAsState()
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
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(
                        onClick = { Explainer.readSimplified(TextChunker.paragraphs(text)) },
                        enabled = modelReady && text.isNotBlank(),
                    ) {
                        Icon(painterResource(R.drawable.ic_sparkle), null, tint = Seal, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Simplify", color = PaperWhite)
                    }
                    Spacer(Modifier.width(4.dp))
                    TextButton(onClick = { text = "" }) { Text("Clear", color = Faint) }
                }
                if (!running && reader.status != Status.Idle) {
                    Spacer(Modifier.height(12.dp))
                    // The floating player only exists while screen reading is on, so show it here too.
                    PlayerBar(
                        state = reader,
                        progress = progress,
                        actions = PlayerActions(
                            onOpenApp = { Reader.stop() },
                            onExplain = { Explainer.explainCurrent(null) },
                            onBackToPage = { Explainer.backToPage() },
                            onToggleText = { Prefs.update { it.copy(showText = !it.showText) } },
                        ),
                        showText = settings.showText,
                    )
                }
            }
        }

        StartSection(context, settings)
        PlayerSection(context, settings)
        SimpleSection(context, settings)
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
            Tip("読", "Tap the bubble to read the screen from the top. Long-press it for Tap to start, image scanning and Hide.")
            Tip("☝", "Tap to start, then touch any sentence to read from there.")
            Tip("⟲", "In the player, use 2 and 5 to jump back or ahead, and the speed chip to change speed.")
            Tip("✦", "The sentence being read is highlighted on screen, and the word being spoken moves along it.")
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

// -------------------------------------------------------------------- ways to start

@Composable
private fun StartSection(context: Context, settings: YSettings) {
    var launcherOn by remember { mutableStateOf(isLauncherEnabled(context)) }
    var tapLauncherOn by remember { mutableStateOf(isEnabled(context, TAP_LAUNCHER)) }
    SectionLabel("Ways to start reading")
    Group {
        Column(Modifier.padding(16.dp)) {
            Text("Floating bubble", color = PaperWhite, fontSize = 15.sp)
            Text(
                "Tap it to read the screen. In Browsers only, it shows up in Chrome, Samsung Internet and other browsers, and stays out of the way everywhere else.",
                color = Faint, fontSize = 12.sp, lineHeight = 16.sp,
            )
            Spacer(Modifier.height(10.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip("Off", settings.bubbleMode == BubbleMode.OFF) { Prefs.update { it.copy(bubbleMode = BubbleMode.OFF) } }
                FilterChip("Browsers only", settings.bubbleMode == BubbleMode.BROWSERS) { Prefs.update { it.copy(bubbleMode = BubbleMode.BROWSERS) } }
                FilterChip("Always", settings.bubbleMode == BubbleMode.ALWAYS) { Prefs.update { it.copy(bubbleMode = BubbleMode.ALWAYS) } }
            }
        }
        Divider()
        Column(Modifier.padding(16.dp)) {
            Text("Quick Settings tiles", color = PaperWhite, fontSize = 15.sp)
            Text(
                "Swipe down from the top and tap Read screen. The shade closes and Yomiage reads the app underneath. Tap it again to stop.",
                color = Faint, fontSize = 12.sp, lineHeight = 16.sp,
            )
            if (Build.VERSION.SDK_INT >= 33) {
                Spacer(Modifier.height(10.dp))
                Row {
                    StepButton("Add Read screen") {
                        addTile(context, ReadTileService::class.java, "Read screen", R.drawable.ic_lines)
                    }
                    Spacer(Modifier.width(8.dp))
                    StepButton("Add Tap to read", outlined = true) {
                        addTile(context, TapTileService::class.java, "Tap to read", R.drawable.ic_tap)
                    }
                }
            }
        }
        Divider()
        Column(Modifier.padding(16.dp)) {
            Text("Voice: “Hey Google, open Read My Screen”", color = PaperWhite, fontSize = 15.sp)
            Text(
                "Read My Screen is a second icon in your app drawer. Opening it reads whatever app is underneath, " +
                    "so you can ask Gemini to open it while you're on a page.",
                color = Faint, fontSize = 12.sp, lineHeight = 16.sp,
            )
        }
        SwitchRow("Show the Read My Screen icon", "Needed for the voice command and the side button.", launcherOn) { on ->
            setLauncherEnabled(context, on)
            launcherOn = on
        }
        Divider()
        Column(Modifier.padding(16.dp)) {
            Text("Side button double press", color = PaperWhite, fontSize = 15.sp)
            Text(
                "Settings → Advanced features → Side button → Double press → Open app → Read My Screen. " +
                    "Then double-press the side button on any page to start reading.",
                color = Faint, fontSize = 12.sp, lineHeight = 16.sp,
            )
            Spacer(Modifier.height(10.dp))
            StepButton("Open Advanced features", outlined = true) { openAdvancedFeatures(context) }
        }
        Divider()
        Column(Modifier.padding(16.dp)) {
            Text("Good Lock gestures", color = PaperWhite, fontSize = 15.sp)
            Spacer(Modifier.height(4.dp))
            Text(
                "One Hand Operation+: turn it on, pick a handle and a swipe direction, choose Open app and pick " +
                    "Read My Screen (or Tap to Read). If it offers app shortcuts, Yomiage → Read screen works too. " +
                    "Then swipe that handle on any page to read it. While it's reading, the same swipe shows or hides " +
                    "the controls, and you stay on the page.",
                color = Faint, fontSize = 12.sp, lineHeight = 16.sp,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "RegiStar: under Side key (press and hold) or Back-Tap action, choose Open app → Read My Screen.",
                color = Faint, fontSize = 12.sp, lineHeight = 16.sp,
            )
            Spacer(Modifier.height(10.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                StepButton("One Hand Operation+", outlined = true) {
                    openPackage(context, "com.samsung.android.sidegesturepad")
                }
                StepButton("RegiStar", outlined = true) {
                    openPackage(context, "com.samsung.android.app.galaxyregistry")
                }
            }
        }
        SwitchRow(
            "Show the Tap to Read icon",
            "A second app entry that starts tap mode, so a gesture can go straight to picking a spot.",
            tapLauncherOn,
        ) { on ->
            setEnabled(context, TAP_LAUNCHER, on)
            tapLauncherOn = on
        }
        Divider()
        Column(Modifier.padding(16.dp)) {
            Text("Selected text", color = PaperWhite, fontSize = 15.sp)
            Text(
                "Select text in any app, then choose Read aloud from the menu. Long-press the Yomiage icon for Read screen and Tap to start shortcuts.",
                color = Faint, fontSize = 12.sp, lineHeight = 16.sp,
            )
        }
    }
}

private const val TAP_LAUNCHER = "dev.yomiage.TapToReadLauncher"

private fun isEnabled(context: Context, cls: String): Boolean =
    context.packageManager.getComponentEnabledSetting(ComponentName(context, cls)) ==
        PackageManager.COMPONENT_ENABLED_STATE_ENABLED

private fun setEnabled(context: Context, cls: String, on: Boolean) {
    context.packageManager.setComponentEnabledSetting(
        ComponentName(context, cls),
        if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
        PackageManager.DONT_KILL_APP,
    )
}

/** Opens a Good Lock module, or Good Lock / the Galaxy Store page if it isn't installed. */
private fun openPackage(context: Context, pkg: String) {
    val pm = context.packageManager
    val launch = pm.getLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage("com.samsung.android.goodlock")
    if (launch != null) {
        open(context, launch)
        return
    }
    try {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, Uri.parse("samsungapps://ProductDetail/$pkg")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (e: Exception) {
        Toast.makeText(context, "Install Good Lock from the Galaxy Store first", Toast.LENGTH_LONG).show()
    }
}

private fun launcherComponent(context: Context) = ComponentName(context, "dev.yomiage.ReadScreenLauncher")

private fun isLauncherEnabled(context: Context): Boolean =
    context.packageManager.getComponentEnabledSetting(launcherComponent(context)) !=
        PackageManager.COMPONENT_ENABLED_STATE_DISABLED

private fun setLauncherEnabled(context: Context, on: Boolean) {
    context.packageManager.setComponentEnabledSetting(
        launcherComponent(context),
        if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
        PackageManager.DONT_KILL_APP,
    )
}

private fun addTile(context: Context, cls: Class<*>, label: String, icon: Int) {
    if (Build.VERSION.SDK_INT < 33) return
    try {
        val sbm = context.getSystemService(StatusBarManager::class.java)
        sbm.requestAddTileService(
            ComponentName(context, cls), label, Icon.createWithResource(context, icon), context.mainExecutor,
        ) { result ->
            val msg = when (result) {
                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> "Tile added"
                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> "That tile is already in Quick Settings"
                StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED -> null
                else -> "Add it from the Quick Settings edit screen instead"
            }
            if (msg != null) Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    } catch (e: Exception) {
        Toast.makeText(context, "Add it from the Quick Settings edit screen instead", Toast.LENGTH_SHORT).show()
    }
}

private fun openAdvancedFeatures(context: Context) {
    // Samsung's Advanced features page, or the main Settings screen elsewhere.
    try {
        context.startActivity(
            Intent("com.samsung.android.settings.ADVANCED_FEATURES").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (e: Exception) {
        open(context, Intent(Settings.ACTION_SETTINGS))
    }
}

// -------------------------------------------------------------------- simple explanations

@Composable
private fun KeyField(value: String, hint: String, onChange: (String) -> Unit) {
    var show by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.trim()) },
        singleLine = true,
        placeholder = { Text(hint, color = Faint) },
        visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = {
            TextButton(onClick = { show = !show }) { Text(if (show) "Hide" else "Show", color = Faint) }
        },
        colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Seal,
            unfocusedBorderColor = Color(0xFF3A3530),
            cursorColor = Seal,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun SimpleSection(context: Context, settings: YSettings) {
    val scope = rememberCoroutineScope()
    var testing by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<String>?>(null) }
    val nano by OnDevice.state.collectAsState()
    val nanoDownload by OnDevice.download.collectAsState()
    val nanoBusy by OnDevice.busy.collectAsState()
    LaunchedEffect(Unit) { OnDevice.refresh() }
    // While it's downloading, keep checking so this flips to Ready by itself.
    LaunchedEffect(nano, nanoBusy) {
        if (nano == NanoState.DOWNLOADING || nanoBusy) OnDevice.watchWhileDownloading()
    }

    SectionLabel("Simple explanations")
    Group {
        Column(Modifier.padding(16.dp)) {
            Text(
                "Breaks hard passages down into plain language. Tap ✦ in the player to hear the paragraph you're on " +
                    "explained, and then it goes back to the page. Or long-press the bubble and choose Read simplified to hear " +
                    "the whole page rewritten simply.",
                color = Color(0xFFD9D1C8), fontSize = 13.sp, lineHeight = 18.sp,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Yomiage tries Gemini first, then Groq, then the AI built into your phone. If one isn't answering, " +
                    "it moves to the next on its own and skips the busy one for a while.",
                color = Faint, fontSize = 12.sp, lineHeight = 16.sp,
            )
        }
        Divider()
        // On-device first in the list: it needs no key.
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("On this phone (Gemini Nano)", color = PaperWhite, fontSize = 15.sp)
                    Text(
                        when (nano) {
                            NanoState.AVAILABLE -> "Ready. Free, private, works offline, no key needed."
                            NanoState.DOWNLOADABLE ->
                                if (nanoBusy) "Downloading. It keeps going if you leave this screen."
                                else "Needs a one-time download from Google (large, so use Wi-Fi)."
                            NanoState.DOWNLOADING ->
                                "Google's AICore app is downloading it in the background. This turns to Ready by itself " +
                                    "when it's done, even if you leave."
                            NanoState.UNAVAILABLE ->
                                if (!OnDevice.aiCoreInstalled()) "Needs Google's Android AICore app, which isn't on this phone."
                                else "Not available on this phone right now. Updating Android AICore or restarting the phone can help."
                            NanoState.UNKNOWN -> "Checking…"
                        },
                        color = Faint, fontSize = 12.sp, lineHeight = 16.sp,
                    )
                }
                Switch(
                    checked = settings.useOnDevice,
                    onCheckedChange = { on -> Prefs.update { it.copy(useOnDevice = on) } },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = PaperWhite,
                        checkedTrackColor = Seal,
                        uncheckedThumbColor = Faint,
                        uncheckedTrackColor = CardHigh,
                        uncheckedBorderColor = Color(0xFF3A3530),
                    ),
                )
            }
            if (nano != NanoState.AVAILABLE && nano != NanoState.UNKNOWN) {
                nanoDownload?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = PaperWhite, fontSize = 12.sp, lineHeight = 16.sp)
                }
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    when {
                        nano == NanoState.DOWNLOADABLE && !nanoBusy ->
                            StepButton("Download on-device AI") { OnDevice.startDownload() }
                        nanoBusy || nano == NanoState.DOWNLOADING ->
                            StepButton("Try again", outlined = true) { OnDevice.startDownload(restart = true) }
                    }
                    StepButton("Update Android AICore", outlined = true) { OnDevice.openAiCoreInStore(context) }
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "While it thinks, Yomiage briefly sits in front of the page in an invisible window, because Android only " +
                    "lets on-device AI run for the app in front.",
                color = Faint, fontSize = 12.sp, lineHeight = 16.sp,
            )
        }
        Divider()
        Column(Modifier.padding(16.dp)) {
            Text("Gemini (Google)", color = PaperWhite, fontSize = 15.sp)
            Text(
                "Best quality. Free key from Google AI Studio. The free tier is often overloaded, which is why there are backups.",
                color = Faint, fontSize = 12.sp, lineHeight = 16.sp,
            )
            Spacer(Modifier.height(8.dp))
            KeyField(settings.geminiKey, "Gemini API key") { v -> Prefs.update { it.copy(geminiKey = v) } }
            Spacer(Modifier.height(6.dp))
            StepButton("Get a free Gemini key", outlined = true) {
                open(context, Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/apikey")))
            }
        }
        Divider()
        Column(Modifier.padding(16.dp)) {
            Text("Groq (backup)", color = PaperWhite, fontSize = 15.sp)
            Text(
                "Very fast, free key. Sign in at console.groq.com, open API Keys, tap Create API Key and paste it here.",
                color = Faint, fontSize = 12.sp, lineHeight = 16.sp,
            )
            Spacer(Modifier.height(8.dp))
            KeyField(settings.groqKey, "Groq API key") { v -> Prefs.update { it.copy(groqKey = v) } }
            Spacer(Modifier.height(6.dp))
            StepButton("Get a free Groq key", outlined = true) {
                open(context, Intent(Intent.ACTION_VIEW, Uri.parse("https://console.groq.com/keys")))
            }
        }
        Divider()
        Column(Modifier.padding(16.dp)) {
            StepButton(if (testing) "Testing… (can take a bit when an AI is busy)" else "Test all") {
                if (testing) return@StepButton
                testing = true
                results = null
                scope.launch {
                    results = try {
                        Ai.testAll()
                    } catch (e: Exception) {
                        listOf("Test failed: ${e.message ?: e.javaClass.simpleName}")
                    }
                    testing = false
                }
            }
            results?.let { lines ->
                Spacer(Modifier.height(10.dp))
                for (line in lines) {
                    Text(
                        line,
                        color = if ("✓" in line) Color(0xFFB9E4B0) else if ("✗" in line) Color(0xFFFFB4A6) else Faint,
                        fontSize = 13.sp, lineHeight = 18.sp,
                        modifier = Modifier.padding(vertical = 2.dp),
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            Text("Explain things for (optional)", color = PaperWhite, fontSize = 15.sp)
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = settings.audience,
                onValueChange = { v -> Prefs.update { it.copy(audience = v) } },
                singleLine = true,
                placeholder = { Text("e.g. a nursing student, a beginner", color = Faint) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Seal,
                    unfocusedBorderColor = Color(0xFF3A3530),
                    cursorColor = Seal,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "Gemini and Groq run online. On Gemini's free tier, Google may use what you send to improve its products, " +
                    "so avoid them for private documents. Gemini Nano keeps everything on the phone.",
                color = Faint, fontSize = 12.sp, lineHeight = 16.sp,
            )
        }
        Divider()
        SwitchRow(
            "Always read the screen simplified",
            "The bubble, tiles and Read My Screen read the plain-language version instead of the original.",
            settings.alwaysSimple,
        ) { on -> Prefs.update { it.copy(alwaysSimple = on) } }
    }
}

@Composable
private fun PlayerSection(context: Context, settings: YSettings) {
    var notificationsOk by remember { mutableStateOf(notificationsAllowed(context)) }
    val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        notificationsOk = ok || notificationsAllowed(context)
        if (!ok) open(context, Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { notificationsOk = notificationsAllowed(context) }
    fun ask() {
        if (Build.VERSION.SDK_INT >= 33) askPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        else open(context, Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
    }
    SectionLabel("Player")
    Group {
        Column(Modifier.padding(16.dp)) {
            Text("Player size", color = PaperWhite, fontSize = 15.sp)
            Text(
                "Full shows the whole player. Small is a pill you can drag anywhere.",
                color = Faint, fontSize = 12.sp, lineHeight = 16.sp,
            )
            Spacer(Modifier.height(10.dp))
            Row {
                FilterChip("Full", !settings.playerMini) { Prefs.update { it.copy(playerMini = false) } }
                Spacer(Modifier.width(8.dp))
                FilterChip("Small", settings.playerMini) { Prefs.update { it.copy(playerMini = true) } }
            }
        }
        Divider()
        Column(Modifier.padding(16.dp)) {
            Text("Reading controls", color = PaperWhite, fontSize = 15.sp)
            Text(
                "The controls pop up when reading starts and stay until you tap the eye button. To bring them back " +
                    "(or hide them again), use your Read My Screen gesture: you stay on the page. Tapping the reading " +
                    "controls in your quick panel brings them back too.",
                color = Faint, fontSize = 12.sp, lineHeight = 16.sp,
            )
            Spacer(Modifier.height(10.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip("Show when reading starts", settings.playerVisibility != PlayerVisibility.NEVER) {
                    Prefs.update { it.copy(playerVisibility = PlayerVisibility.ALWAYS) }
                }
                FilterChip("Start hidden", settings.playerVisibility == PlayerVisibility.NEVER) {
                    Prefs.update { it.copy(playerVisibility = PlayerVisibility.NEVER) }
                    if (!notificationsOk) ask()
                }
            }
        }
        Divider()
        Column(Modifier.padding(16.dp)) {
            Text("Quick panel controls", color = PaperWhite, fontSize = 15.sp)
            Text(
                if (notificationsOk) {
                    "On. While reading, swipe down for play/pause, skip 5 seconds (next/previous) and skip 2 seconds."
                } else {
                    "Allow notifications so the reading controls show in your quick panel and on the lock screen."
                },
                color = Faint, fontSize = 12.sp, lineHeight = 16.sp,
            )
            if (!notificationsOk) {
                Spacer(Modifier.height(8.dp))
                StepButton("Allow notifications") { ask() }
            }
        }
    }
}

private fun notificationsAllowed(context: Context): Boolean =
    context.getSystemService(android.app.NotificationManager::class.java).areNotificationsEnabled()

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
        Column(Modifier.padding(16.dp)) {
            Text("Highlight while reading", color = PaperWhite, fontSize = 15.sp)
            Text(
                "Marks the sentence on screen so you can follow along. Word by word also moves a marker with the voice.",
                color = Faint, fontSize = 12.sp, lineHeight = 16.sp,
            )
            Spacer(Modifier.height(10.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip("Off", settings.highlightMode == HighlightMode.OFF) { Prefs.update { it.copy(highlightMode = HighlightMode.OFF) } }
                FilterChip("Sentence", settings.highlightMode == HighlightMode.SENTENCE) { Prefs.update { it.copy(highlightMode = HighlightMode.SENTENCE) } }
                FilterChip("Word by word", settings.highlightMode == HighlightMode.WORD) { Prefs.update { it.copy(highlightMode = HighlightMode.WORD) } }
            }
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
