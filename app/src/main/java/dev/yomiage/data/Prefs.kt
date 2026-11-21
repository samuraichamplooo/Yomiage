package dev.yomiage.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class Settings(
    val voiceId: Int = 3,            // af_heart
    val speed: Float = 1.0f,
    val highlight: Boolean = true,   // outline the paragraph being read
    val readBelow: Boolean = true,   // keep going past the bottom of the screen when the app shares that text
    val autoOcr: Boolean = true,     // fall back to image text recognition when an app shares no text
    val showBubble: Boolean = true,
    val readUrls: Boolean = false,   // say "link" instead of spelling out web addresses
    val threads: Int = 4,
    val bubbleRight: Boolean = true,
    val bubbleY: Float = 0.38f,      // fraction of screen height
    val barYdp: Int = 20,            // distance of the player bar from the bottom
)

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
            highlight = sp.getBoolean("highlight", d.highlight),
            readBelow = sp.getBoolean("readBelow", d.readBelow),
            autoOcr = sp.getBoolean("autoOcr", d.autoOcr),
            showBubble = sp.getBoolean("showBubble", d.showBubble),
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
            .putBoolean("highlight", s.highlight)
            .putBoolean("readBelow", s.readBelow)
            .putBoolean("autoOcr", s.autoOcr)
            .putBoolean("showBubble", s.showBubble)
            .putBoolean("readUrls", s.readUrls)
            .putInt("threads", s.threads)
            .putBoolean("bubbleRight", s.bubbleRight)
            .putFloat("bubbleY", s.bubbleY)
            .putInt("barYdp", s.barYdp)
            .apply()
    }
}
