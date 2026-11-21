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
    val geminiKey: String = "",      // the user's own Google AI Studio key, for simple explanations
    val geminiModel: String = "",    // last model that worked
    val audience: String = "",       // "explain things for…", e.g. a nursing student
    val alwaysSimple: Boolean = false,
    val showText: Boolean = true,    // show the text panel for simplified reading
    val playerHidden: Boolean = false, // no on-screen player; use the media controls instead
    val groqKey: String = "",        // backup AI (free key from console.groq.com)
    val useOnDevice: Boolean = true, // fall back to Gemini Nano on the phone
)

object HighlightMode {
    const val OFF = 0
    const val SENTENCE = 1
    const val WORD = 2
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
            geminiKey = sp.getString("geminiKey", d.geminiKey) ?: "",
            geminiModel = sp.getString("geminiModel", d.geminiModel) ?: "",
            audience = sp.getString("audience", d.audience) ?: "",
            alwaysSimple = sp.getBoolean("alwaysSimple", d.alwaysSimple),
            showText = sp.getBoolean("showText", d.showText),
            playerHidden = sp.getBoolean("playerHidden", d.playerHidden),
            groqKey = sp.getString("groqKey", d.groqKey) ?: "",
            useOnDevice = sp.getBoolean("useOnDevice", d.useOnDevice),
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
            .putString("geminiKey", s.geminiKey)
            .putString("geminiModel", s.geminiModel)
            .putString("audience", s.audience)
            .putBoolean("alwaysSimple", s.alwaysSimple)
            .putBoolean("showText", s.showText)
            .putBoolean("playerHidden", s.playerHidden)
            .putString("groqKey", s.groqKey)
            .putBoolean("useOnDevice", s.useOnDevice)
            .apply()
    }
}
