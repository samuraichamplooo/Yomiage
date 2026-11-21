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
            .apply()
    }
}
