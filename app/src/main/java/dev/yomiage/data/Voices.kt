package dev.yomiage.data

enum class Accent(val label: String, val note: String?) {
    US("American English", null),
    UK("British English", null),
    JA("Japanese", "Reads English with a Japanese accent"),
    ES("Spanish", "Best for Spanish text"),
    FR("French", "Best for French text"),
    IT("Italian", "Best for Italian text"),
    PT("Brazilian Portuguese", "Best for Portuguese text"),
    HI("Hindi", "Best for Hindi text"),
    ZH("Mandarin Chinese", "Reads Chinese and English"),
}

/** Where a voice comes from. */
enum class VoiceEngine(val label: String) {
    KOKORO("Kokoro"),         // on the phone, free
    SUPERTONIC("Supertonic"), // on the phone, free
    GEMINI("Gemini"),         // online, expressive, uses your Gemini key
    ORPHEUS("Orpheus"),       // online, expressive, uses your Groq key
}

/**
 * One voice. For Kokoro, [id] is the speaker id inside voices.bin (see sherpa-onnx
 * scripts/kokoro/v1.0/generate_voices_bin.py); other engines use their own id ranges
 * (Supertonic 3000+, Gemini 1000+, Orpheus 2000+). [vibe] is a word or two about how it sounds.
 */
data class Voice(
    val id: Int,
    val code: String,
    val accent: Accent,
    val female: Boolean,
    val top: Boolean = false,
    val engine: VoiceEngine = VoiceEngine.KOKORO,
    val vibe: String? = null,
    val display: String? = null,
) {
    val name: String =
        display ?: code.substringAfter('_').replaceFirstChar { it.uppercaseChar() }

    /** The voice's own name at its service ("Puck", "troy"), or the speaker number on the phone. */
    val remote: String get() = code.substringAfter('_')

    /** Online voices need a connection and a key, and are sent the text being read. */
    val online: Boolean get() = engine == VoiceEngine.GEMINI || engine == VoiceEngine.ORPHEUS

    /** espeak-ng language for this voice, or null for the model default (American English). */
    val lang: String?
        get() = when (accent) {
            Accent.UK -> "en-gb"
            Accent.ES -> "es"
            Accent.FR -> "fr-fr"
            Accent.IT -> "it"
            Accent.PT -> "pt-br"
            Accent.HI -> "hi"
            else -> null
        }
}

object Voices {
    private val codes = listOf(
        "af_alloy", "af_aoede", "af_bella", "af_heart", "af_jessica", "af_kore", "af_nicole",
        "af_nova", "af_river", "af_sarah", "af_sky", "am_adam", "am_echo", "am_eric",
        "am_fenrir", "am_liam", "am_michael", "am_onyx", "am_puck", "am_santa",
        "bf_alice", "bf_emma", "bf_isabella", "bf_lily", "bm_daniel", "bm_fable", "bm_george",
        "bm_lewis", "ef_dora", "em_alex", "ff_siwis", "hf_alpha", "hf_beta", "hm_omega",
        "hm_psi", "if_sara", "im_nicola", "jf_alpha", "jf_gongitsune", "jf_nezumi",
        "jf_tebukuro", "jm_kumo", "pf_dora", "pm_alex", "pm_santa", "zf_xiaobei", "zf_xiaoni",
        "zf_xiaoxiao", "zf_xiaoyi", "zm_yunjian", "zm_yunxi", "zm_yunxia", "zm_yunyang",
        "em_santa",
    )

    // The voices people usually like best.
    private val topPicks = setOf(
        "af_heart", "af_bella", "af_nicole", "am_michael", "am_fenrir", "am_puck",
        "bf_emma", "bm_george", "bm_fable",
    )

    val all: List<Voice> = codes.mapIndexed { i, code ->
        val accent = when (code[0]) {
            'a' -> Accent.US
            'b' -> Accent.UK
            'e' -> Accent.ES
            'f' -> Accent.FR
            'h' -> Accent.HI
            'i' -> Accent.IT
            'j' -> Accent.JA
            'p' -> Accent.PT
            else -> Accent.ZH
        }
        Voice(i, code, accent, female = code[1] == 'f', top = code in topPicks)
    }

    /** Supertonic 3: sids 0–4 are F1–F5, 5–9 are M1–M5. */
    val supertonic: List<Voice> = (0 until 10).map { sid ->
        val female = sid < 5
        val n = sid % 5 + 1
        Voice(
            3000 + sid, "st_$sid", Accent.US, female, top = sid == 0 || sid == 5,
            engine = VoiceEngine.SUPERTONIC, display = (if (female) "Sora " else "Kai ") + n,
            vibe = if (female) "Natural, female" else "Natural, male",
        )
    }

    /** Gemini's prebuilt voices and how Google describes each. */
    val gemini: List<Voice> = listOf(
        "Puck" to "Upbeat", "Zephyr" to "Bright", "Kore" to "Firm", "Charon" to "Informative",
        "Fenrir" to "Excitable", "Leda" to "Youthful", "Orus" to "Firm", "Aoede" to "Breezy",
        "Callirrhoe" to "Easy-going", "Autonoe" to "Bright", "Enceladus" to "Breathy", "Iapetus" to "Clear",
        "Umbriel" to "Easy-going", "Algieba" to "Smooth", "Despina" to "Smooth", "Erinome" to "Clear",
        "Algenib" to "Gravelly", "Rasalgethi" to "Informative", "Laomedeia" to "Upbeat", "Achernar" to "Soft",
        "Alnilam" to "Firm", "Schedar" to "Even", "Gacrux" to "Mature", "Pulcherrima" to "Forward",
        "Achird" to "Friendly", "Zubenelgenubi" to "Casual", "Vindemiatrix" to "Gentle", "Sadachbia" to "Lively",
        "Sadaltager" to "Knowledgeable", "Sulafat" to "Warm",
    ).mapIndexed { i, (name, vibe) ->
        val female = name in setOf(
            "Zephyr", "Kore", "Leda", "Aoede", "Callirrhoe", "Autonoe", "Despina", "Erinome", "Laomedeia",
            "Achernar", "Gacrux", "Pulcherrima", "Vindemiatrix", "Sulafat",
        )
        Voice(
            1000 + i, "gm_$name", Accent.US, female,
            top = name in setOf("Puck", "Sadachbia", "Laomedeia", "Achird", "Sulafat", "Charon"),
            engine = VoiceEngine.GEMINI, vibe = vibe,
        )
    }

    /** Groq's Orpheus voices (Canopy Labs): very expressive, especially with a delivery set. */
    val orpheus: List<Voice> = listOf(
        "autumn" to true, "diana" to true, "hannah" to true, "austin" to false, "daniel" to false, "troy" to false,
    ).mapIndexed { i, (name, female) ->
        Voice(
            2000 + i, "or_$name", Accent.US, female, top = name == "hannah" || name == "troy",
            engine = VoiceEngine.ORPHEUS, vibe = if (female) "Expressive, female" else "Expressive, male",
        )
    }

    private val everyone: Map<Int, Voice> = (all + supertonic + gemini + orpheus).associateBy { it.id }

    fun byId(id: Int): Voice = everyone[id] ?: all[3]

    /** Kokoro's Heart: what reading falls back to when an online voice can't be used. */
    val fallback: Voice get() = all[3]
}
