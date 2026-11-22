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
import dev.yomiage.theme.Look
import dev.yomiage.theme.ThemeKind
import dev.yomiage.data.Settings as YSettings
import dev.yomiage.data.Voice
import dev.yomiage.data.Voices
import dev.yomiage.data.VoiceEngine
import dev.yomiage.tts.Delivery
import dev.yomiage.tts.SupertonicPack
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
                        unfocusedBorderColor = Line,
                        cursorColor = Seal,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = { Reader.read(TextChunker.paragraphs(text)) },
                        enabled = modelReady && text.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = Seal, contentColor = OnSeal),
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
        LookSection(settings)
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
            Text("読", color = OnSeal, fontSize = 22.sp, fontWeight = FontWeight.Medium)
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
    val p = Look.palette
    val sheen = Look.sheen(p)
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = Card,
        border = if (p.glass) Look.edge(p) else null,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(if (sheen != null) Modifier.background(sheen) else Modifier, content = content)
    }
}

@Composable
private fun Divider() {
    HorizontalDivider(thickness = 1.dp, color = if (Look.palette.glass) Look.palette.outline else Look.palette.background)
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
                    Text(model.message, color = Bad, fontSize = 13.sp)
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
                Icon(painterResource(R.drawable.ic_check), null, tint = OnSeal, modifier = Modifier.size(16.dp))
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
            colors = ButtonDefaults.buttonColors(containerColor = Seal, contentColor = OnSeal),
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
        Text(text, color = SoftText, fontSize = 14.sp, lineHeight = 19.sp)
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
            unfocusedBorderColor = Line,
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
                color = SoftText, fontSize = 13.sp, lineHeight = 18.sp,
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
                        uncheckedBorderColor = Line,
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
                        color = if ("✓" in line) Good else if ("✗" in line) Bad else Faint,
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
                    unfocusedBorderColor = Line,
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
        ) { on -> Prefs.update { it.copy(alwaysSimple = on, alwaysLively = if (on) false else it.alwaysLively) } }
        Divider()
        SwitchRow(
            "Always read as a lively narrator",
            "AI retells each page like an engaging teacher or podcast host, keeping every fact and key term. Free with " +
                "your Gemini or Groq key. Also in the bubble's long-press menu.",
            settings.alwaysLively,
        ) { on -> Prefs.update { it.copy(alwaysLively = on, alwaysSimple = if (on) false else it.alwaysSimple) } }
    }
}

@Composable
private fun LookSection(settings: YSettings) {
    SectionLabel("Look")
    Group {
        Column(Modifier.padding(16.dp)) {
            Text("Theme", color = PaperWhite, fontSize = 15.sp)
            Text(
                "For the app, the reading view and the floating player. Material You takes its colors from your " +
                    "wallpaper. Liquid glass is see-through and frosted, with your wallpaper blurred behind the app. " +
                    "Both follow your phone's light or dark mode.",
                color = Faint, fontSize = 12.sp, lineHeight = 16.sp,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                ThemeTile("Classic", ThemeKind.CLASSIC, settings.theme, Modifier.weight(1f))
                ThemeTile("Material You", ThemeKind.MATERIAL_YOU, settings.theme, Modifier.weight(1f))
                ThemeTile("Liquid glass", ThemeKind.GLASS, settings.theme, Modifier.weight(1f))
            }
        }
    }
}

/** A little preview of a look: its background, a card and the accent dot. */
@Composable
private fun ThemeTile(name: String, kind: Int, current: Int, modifier: Modifier) {
    val selected = kind == current
    val dynamic = android.os.Build.VERSION.SDK_INT >= 31
    val context = LocalContext.current
    val cs = remember(kind) {
        if (dynamic) androidx.compose.material3.dynamicDarkColorScheme(context) else null
    }
    val (bg, card, accent) = when (kind) {
        ThemeKind.MATERIAL_YOU -> Triple(
            cs?.surface ?: Color(0xFF1B1B1F), cs?.surfaceContainerHighest ?: Color(0xFF34343A), cs?.primary ?: Color(0xFFADC6FF),
        )
        ThemeKind.GLASS -> Triple(Color(0xFF26324A), Color(0x40FFFFFF), cs?.primary ?: Color(0xFF64A8FF))
        else -> Triple(Color(0xFF0E0D0C), Color(0xFF221F1C), Color(0xFFD8462B))
    }
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .border(2.dp, if (selected) Seal else Line, RoundedCornerShape(16.dp))
            .clickable { Prefs.update { it.copy(theme = kind) } }
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(64.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(
                    if (kind == ThemeKind.GLASS) {
                        androidx.compose.ui.graphics.Brush.linearGradient(
                            listOf(Color(0xFF3B5BA5), Color(0xFF8E5AA8), Color(0xFFE08A6B)),
                        )
                    } else {
                        androidx.compose.ui.graphics.SolidColor(bg)
                    }
                ),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                Modifier
                    .padding(horizontal = 10.dp)
                    .fillMaxWidth()
                    .height(26.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(card)
                    .then(
                        if (kind == ThemeKind.GLASS) Modifier.border(1.dp, Color(0x99FFFFFF), RoundedCornerShape(13.dp))
                        else Modifier
                    )
                    .padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(14.dp).clip(CircleShape).background(accent))
                Spacer(Modifier.width(6.dp))
                Box(Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color(0x66FFFFFF)))
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            name, color = if (selected) PaperWhite else Faint, fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal, maxLines = 1,
        )
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
                "Full shows the whole player. Small is a pill you can drag anywhere, with play/pause and skip back and ahead.",
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
            Text("Skip buttons", color = PaperWhite, fontSize = 15.sp)
            Text(
                "How far the skip buttons jump, on the player, in the quick panel, on the lock screen and on headphones " +
                    "(next and previous use the bigger skip).",
                color = Faint, fontSize = 12.sp, lineHeight = 16.sp,
            )
            Spacer(Modifier.height(10.dp))
            Text("Small skip", color = SoftText, fontSize = 13.sp)
            Spacer(Modifier.height(6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (n in listOf(2, 3, 4)) {
                    FilterChip("$n seconds", settings.skipSmall == n) { Prefs.update { it.copy(skipSmall = n) } }
                }
            }
            Spacer(Modifier.height(10.dp))
            Text("Big skip", color = SoftText, fontSize = 13.sp)
            Spacer(Modifier.height(6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (n in listOf(5, 10, 15)) {
                    FilterChip("$n seconds", settings.skipBig == n) { Prefs.update { it.copy(skipBig = n) } }
                }
            }
        }
        Divider()
        Column(Modifier.padding(16.dp)) {
            Text("Reading controls", color = PaperWhite, fontSize = 15.sp)
            Text(
                "The controls pop up when reading starts. Tap the dot button (top left of the player) to shrink them " +
                    "to a small dot: tap the dot to get them back, hold it to pause. The eye button hides them " +
                    "completely; your Read My Screen gesture or the quick panel controls bring them back, and you stay on the page.",
                color = Faint, fontSize = 12.sp, lineHeight = 16.sp,
            )
            Spacer(Modifier.height(10.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip("Show when reading starts", settings.playerVisibility == PlayerVisibility.ALWAYS) {
                    Prefs.update { it.copy(playerVisibility = PlayerVisibility.ALWAYS) }
                }
                FilterChip("Start as a dot", settings.playerVisibility == PlayerVisibility.DOT) {
                    Prefs.update { it.copy(playerVisibility = PlayerVisibility.DOT) }
                }
                FilterChip("Start hidden", settings.playerVisibility == PlayerVisibility.NEVER) {
                    Prefs.update { it.copy(playerVisibility = PlayerVisibility.NEVER) }
                    if (!notificationsOk) ask()
                }
            }
        }
        Divider()
        Column(Modifier.padding(16.dp)) {
            Text("Quick panel, lock screen and Always On Display", color = PaperWhite, fontSize = 15.sp)
            Text(
                if (notificationsOk) {
                    "On. While reading, a minimal black player shows in the quick panel and in Samsung's Now bar on the " +
                        "lock screen and Always On Display: play/pause, skip 5 seconds, and drag the bar to move through " +
                        "the text. It only updates when something changes, to save battery. If it doesn't show on the lock " +
                        "screen, turn on Settings > Lock screen and AOD > Now bar, and allow Yomiage there."
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
    var tab by rememberSaveable { mutableStateOf(selected.engine.ordinal) }
    val engine = VoiceEngine.entries[tab.coerceIn(0, VoiceEngine.entries.size - 1)]
    val context = LocalContext.current
    val stStatus by SupertonicPack.status.collectAsState()
    LaunchedEffect(Unit) { SupertonicPack.refresh(context) }
    val stReady = stStatus is ModelStatus.Ready
    SectionLabel("Voice")
    Group {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 6.dp)) {
            Text(selected.name, color = PaperWhite, fontSize = 18.sp, fontWeight = FontWeight.Medium)
            Text(
                listOfNotNull(
                    selected.engine.label,
                    selected.vibe ?: selected.accent.label,
                    if (selected.female) "Female" else "Male",
                ).joinToString(" · "),
                color = Faint, fontSize = 13.sp,
            )
            Spacer(Modifier.height(12.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip("Kokoro", engine == VoiceEngine.KOKORO) { tab = VoiceEngine.KOKORO.ordinal }
                FilterChip("Supertonic", engine == VoiceEngine.SUPERTONIC) { tab = VoiceEngine.SUPERTONIC.ordinal }
                FilterChip("Gemini ✦", engine == VoiceEngine.GEMINI) { tab = VoiceEngine.GEMINI.ordinal }
                FilterChip("Orpheus ✦", engine == VoiceEngine.ORPHEUS) { tab = VoiceEngine.ORPHEUS.ordinal }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                when (engine) {
                    VoiceEngine.KOKORO -> "On your phone: free, private, works offline. 54 voices in 9 languages."
                    VoiceEngine.SUPERTONIC -> "On your phone: free, private, works offline. 10 natural, quick voices " +
                        "(a one-time download)."
                    VoiceEngine.GEMINI -> "Online and very expressive: about 30 voices that read the way you ask (pick a " +
                        "delivery below). Uses your Gemini key. Google's free allowance only covers a few minutes a day; " +
                        "with billing on, it's roughly \$0.55–\$0.80 per hour of listening. When the limit's reached, " +
                        "Heart reads instead. The text is sent to Google."
                    VoiceEngine.ORPHEUS -> "Online and the most expressive: 6 voices that can sound cheerful, dramatic " +
                        "or excited (pick a delivery below). Uses your Groq key. The free allowance is a few minutes a " +
                        "day; paid, it's about \$1.20 per hour of listening. Groq may ask you to accept the model's terms " +
                        "once at console.groq.com. The text is sent to Groq."
                },
                color = Faint, fontSize = 12.sp, lineHeight = 16.sp,
            )
            if (engine == VoiceEngine.GEMINI || engine == VoiceEngine.ORPHEUS) {
                val hasKey = if (engine == VoiceEngine.GEMINI) settings.geminiKey.isNotBlank() else settings.groqKey.isNotBlank()
                if (!hasKey) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Add your ${if (engine == VoiceEngine.GEMINI) "Gemini" else "Groq"} key under Simple explanations first.",
                        color = Bad, fontSize = 12.sp,
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text("Delivery", color = SoftText, fontSize = 13.sp)
                Spacer(Modifier.height(6.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Delivery.names.forEachIndexed { i, label ->
                        FilterChip(label, settings.delivery == i) { Prefs.update { it.copy(delivery = i) } }
                    }
                }
            }
            if (engine == VoiceEngine.SUPERTONIC && !stReady) {
                Spacer(Modifier.height(10.dp))
                when (val st = stStatus) {
                    is ModelStatus.Downloading -> Text(
                        "Downloading… " + (if (st.total > 0) "${st.bytes * 100 / st.total}%" else "${st.bytes / 1_048_576} MB"),
                        color = PaperWhite, fontSize = 13.sp,
                    )
                    is ModelStatus.Unpacking -> Text("Unpacking… ${(st.fraction * 100).toInt()}%", color = PaperWhite, fontSize = 13.sp)
                    is ModelStatus.Failed -> {
                        Text(st.message, color = Bad, fontSize = 12.sp)
                        Spacer(Modifier.height(6.dp))
                        StepButton("Try again") { SupertonicPack.start(context) }
                    }
                    else -> StepButton("Download Supertonic voices") { SupertonicPack.start(context) }
                }
            }
            if (engine == VoiceEngine.KOKORO) {
                Spacer(Modifier.height(10.dp))
                Row {
                    FilterChip("English", !allLanguages) { allLanguages = false }
                    Spacer(Modifier.width(6.dp))
                    FilterChip("All languages", allLanguages) { allLanguages = true }
                }
            }
        }
        when (engine) {
            VoiceEngine.KOKORO -> {
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
                    for (v in voices) VoiceRow(v, v.id == selected.id, modelReady)
                }
            }
            else -> {
                val list = when (engine) {
                    VoiceEngine.SUPERTONIC -> Voices.supertonic
                    VoiceEngine.GEMINI -> Voices.gemini
                    else -> Voices.orpheus
                }.sortedWith(compareBy<Voice>({ !it.top }, { it.name }))
                val usable = when (engine) {
                    VoiceEngine.SUPERTONIC -> stReady
                    VoiceEngine.GEMINI -> settings.geminiKey.isNotBlank()
                    else -> settings.groqKey.isNotBlank()
                }
                Spacer(Modifier.height(6.dp))
                for (v in list) VoiceRow(v, v.id == selected.id, usable)
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
        Text(label, color = if (selected) OnSeal else PaperWhite, fontSize = 13.sp)
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
                .border(2.dp, if (selected) Seal else Line, CircleShape),
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
                (if (v.female) "Female" else "Male") + " · " + (v.vibe ?: v.code),
                color = Faint, fontSize = 12.sp, fontFamily = if (v.vibe == null) FontFamily.Monospace else FontFamily.Default,
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
                tint = if (modelReady) PaperWhite else Line,
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
                "Word marks just the word being said, moving with the voice. Sentence shades the whole sentence. Both does both.",
                color = Faint, fontSize = 12.sp, lineHeight = 16.sp,
            )
            Spacer(Modifier.height(10.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip("Off", settings.highlightMode == HighlightMode.OFF) { Prefs.update { it.copy(highlightMode = HighlightMode.OFF) } }
                FilterChip("Word", settings.highlightMode == HighlightMode.WORD) { Prefs.update { it.copy(highlightMode = HighlightMode.WORD) } }
                FilterChip("Sentence", settings.highlightMode == HighlightMode.SENTENCE) { Prefs.update { it.copy(highlightMode = HighlightMode.SENTENCE) } }
                FilterChip("Both", settings.highlightMode == HighlightMode.BOTH) { Prefs.update { it.copy(highlightMode = HighlightMode.BOTH) } }
            }
        }
        Divider()
        SwitchRow(
            "Read the whole page",
            "Keeps going past the bottom of the screen to the end of the page or chapter, loading more as it goes. " +
                "No tapping needed.",
            settings.readBelow,
        ) { on ->
            Prefs.update { it.copy(readBelow = on) }
        }
        Divider()
        SwitchRow(
            "Scroll the page as it reads",
            "Keeps the sentence being read on screen. Scroll yourself any time and it waits a few seconds before following again.",
            settings.followScroll,
        ) { on ->
            Prefs.update { it.copy(followScroll = on) }
        }
        Divider()
        SwitchRow(
            "Skip page clutter",
            "On web pages, leaves out menus, toolbars, buttons, footers and page numbers, and reads the main text.",
            settings.smartPage,
        ) { on ->
            Prefs.update { it.copy(smartPage = on) }
        }
        Divider()
        SwitchRow(
            "Explain pictures and tables",
            "Reads tables row by row, then sums them up simply. When it reaches a diagram or picture, AI explains " +
                "what it shows in plain words. Uses your AI setup under Simple explanations.",
            settings.explainVisuals,
        ) { on ->
            Prefs.update { it.copy(explainVisuals = on) }
        }
        Divider()
        SwitchRow(
            "Open in reading view",
            "Shows the text in a clean, book-like view that follows along as it reads. Tap any paragraph to jump " +
                "there. You can also open it from the book button on the player.",
            settings.openView,
        ) { on ->
            Prefs.update { it.copy(openView = on) }
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
                uncheckedBorderColor = Line,
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
                    Text("Delete voice pack", color = Bad, fontSize = 15.sp)
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
