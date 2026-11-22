package dev.yomiage.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class Settings(
    val voiceId: Int = 3,            // af_heart
    val speed: Float = 1.0f,
    val highlightMode: Int = HighlightMode.WORD,
    val readBelow: Boolean = true,   // keep going past the bottom of the screen when the app shares that text
    val autoOcr: Boolean = true,     // fall back to image text recognition when an app shares no text
    val bubbleMode: Int = BubbleMode.BROWSERS,
    val readUrls: Boolean = false,   // say "link" instead of spelling out web addresses
    val threads: Int = 4,
    val bubbleRight: Boolean = true,
    val bubbleY: Float = 0.38f,      // fraction of screen height
    val barYdp: Int = 20,            // distance of the player bar from the bottom
    val playerMini: Boolean = false, // player shrunk to a small pill
    val miniXdp: Int = 12,
    val miniYdp: Int = 96,
    val dotXdp: Int = 10,     // the shrunk-down dot, from the right edge
    val dotYdp: Int = 200,    // and from the bottom
    val geminiKey: String = "",      // the user's own Google AI Studio key, for simple explanations
    val geminiModel: String = "",    // last model that worked
    val audience: String = "",       // "explain things for…", e.g. a nursing student
    val alwaysSimple: Boolean = false,
    val showText: Boolean = true,    // show the text panel for simplified reading
    val playerVisibility: Int = PlayerVisibility.ALWAYS,
    val groqKey: String = "",        // backup AI (free key from console.groq.com)
    val useOnDevice: Boolean = true, // fall back to Gemini Nano on the phone
    val followScroll: Boolean = true,   // scroll the page along with the voice
    val smartPage: Boolean = true,      // on web pages: skip menus, toolbars, footers and page numbers
    val explainVisuals: Boolean = true, // read tables row by row and explain pictures/diagrams with AI
    val openView: Boolean = false,      // open the reading view whenever a page starts reading
    val viewTextSp: Int = 19,           // text size in the reading view
    val theme: Int = 0,                 // ThemeKind: classic, Material You or liquid glass
    val skipSmall: Int = 3,             // seconds for the small skip buttons
    val skipBig: Int = 5,               // seconds for the big skip buttons (and next/previous)
)

object HighlightMode {
    const val OFF = 0
    const val SENTENCE = 1   // shades the sentence being read
    const val WORD = 2       // marks only the word being said
    const val BOTH = 3       // the sentence shaded and the word marked

    fun sentence(mode: Int) = mode == SENTENCE || mode == BOTH
    fun word(mode: Int) = mode == WORD || mode == BOTH
}

object PlayerVisibility {
    const val ALWAYS = 0     // pops up when reading starts and stays until you hide it
    const val NEVER = 2      // starts hidden: bring it up with the gesture or the quick panel
    const val DOT = 3        // starts shrunk to a small dot: tap it for the controls
}

object BubbleMode {
    const val OFF = 0
    const val BROWSERS = 1
    const val ALWAYS = 2
}

object Prefs {
    private lateinit var sp: SharedPreferences
    private val _settings = MutableStateFlow(Settings())
    val settings: StateFlow<Settings> = _settings
    val current: Settings get() = _settings.value

    fun init(context: Context) {
        sp = context.getSharedPreferences("yomiage", Context.MODE_PRIVATE)
        val d = Settings()
        _settings.value = Settings(
            voiceId = sp.getInt("voiceId", d.voiceId),
            speed = sp.getFloat("speed", d.speed),
            highlightMode = sp.getInt(
                "highlightMode",
                if (sp.getBoolean("highlight", true)) d.highlightMode else HighlightMode.OFF,
            ),
            readBelow = sp.getBoolean("readBelow", d.readBelow),
            autoOcr = sp.getBoolean("autoOcr", d.autoOcr),
            bubbleMode = sp.getInt(
                "bubbleMode",
                if (sp.getBoolean("showBubble", true)) d.bubbleMode else BubbleMode.OFF,
            ),
            readUrls = sp.getBoolean("readUrls", d.readUrls),
            threads = sp.getInt("threads", d.threads),
            bubbleRight = sp.getBoolean("bubbleRight", d.bubbleRight),
            bubbleY = sp.getFloat("bubbleY", d.bubbleY),
            barYdp = sp.getInt("barYdp", d.barYdp),
            playerMini = sp.getBoolean("playerMini", d.playerMini),
            miniXdp = sp.getInt("miniXdp", d.miniXdp),
            miniYdp = sp.getInt("miniYdp", d.miniYdp),
            dotXdp = sp.getInt("dotXdp", d.dotXdp),
            dotYdp = sp.getInt("dotYdp", d.dotYdp),
            geminiKey = sp.getString("geminiKey", d.geminiKey) ?: "",
            geminiModel = sp.getString("geminiModel", d.geminiModel) ?: "",
            audience = sp.getString("audience", d.audience) ?: "",
            alwaysSimple = sp.getBoolean("alwaysSimple", d.alwaysSimple),
            showText = sp.getBoolean("showText", d.showText),
            // The old "at start, then hide" setting (1) became the normal behaviour: shown until you hide it.
            playerVisibility = sp.getInt("playerVisibility", d.playerVisibility)
                .let { if (it == PlayerVisibility.NEVER || it == PlayerVisibility.DOT) it else PlayerVisibility.ALWAYS },
            groqKey = sp.getString("groqKey", d.groqKey) ?: "",
            useOnDevice = sp.getBoolean("useOnDevice", d.useOnDevice),
            followScroll = sp.getBoolean("followScroll", d.followScroll),
            smartPage = sp.getBoolean("smartPage", d.smartPage),
            explainVisuals = sp.getBoolean("explainVisuals", d.explainVisuals),
            openView = sp.getBoolean("openView", d.openView),
            viewTextSp = sp.getInt("viewTextSp", d.viewTextSp),
            theme = sp.getInt("theme", d.theme),
            skipSmall = sp.getInt("skipSmall", d.skipSmall),
            skipBig = sp.getInt("skipBig", d.skipBig),
        )
    }

    fun update(change: (Settings) -> Settings) {
        val s = change(_settings.value)
        _settings.value = s
        sp.edit()
            .putInt("voiceId", s.voiceId)
            .putFloat("speed", s.speed)
            .putInt("highlightMode", s.highlightMode)
            .putBoolean("readBelow", s.readBelow)
            .putBoolean("autoOcr", s.autoOcr)
            .putInt("bubbleMode", s.bubbleMode)
            .putBoolean("readUrls", s.readUrls)
            .putInt("threads", s.threads)
            .putBoolean("bubbleRight", s.bubbleRight)
            .putFloat("bubbleY", s.bubbleY)
            .putInt("barYdp", s.barYdp)
            .putBoolean("playerMini", s.playerMini)
            .putInt("miniXdp", s.miniXdp)
            .putInt("miniYdp", s.miniYdp)
            .putInt("dotXdp", s.dotXdp)
            .putInt("dotYdp", s.dotYdp)
            .putString("geminiKey", s.geminiKey)
            .putString("geminiModel", s.geminiModel)
            .putString("audience", s.audience)
            .putBoolean("alwaysSimple", s.alwaysSimple)
            .putBoolean("showText", s.showText)
            .putInt("playerVisibility", s.playerVisibility)
            .putString("groqKey", s.groqKey)
            .putBoolean("useOnDevice", s.useOnDevice)
            .putBoolean("followScroll", s.followScroll)
            .putBoolean("smartPage", s.smartPage)
            .putBoolean("explainVisuals", s.explainVisuals)
            .putBoolean("openView", s.openView)
            .putInt("viewTextSp", s.viewTextSp)
            .putInt("theme", s.theme)
            .putInt("skipSmall", s.skipSmall)
            .putInt("skipBig", s.skipBig)
            .apply()
    }
}
