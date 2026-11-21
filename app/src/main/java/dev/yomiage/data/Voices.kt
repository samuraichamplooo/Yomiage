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

/**
 * One Kokoro v1.0 voice. [id] is the speaker id inside voices.bin
 * (see sherpa-onnx scripts/kokoro/v1.0/generate_voices_bin.py).
 */
data class Voice(
    val id: Int,
    val code: String,
    val accent: Accent,
    val female: Boolean,
    val top: Boolean = false,
) {
    val name: String =
        code.substringAfter('_').replaceFirstChar { it.uppercaseChar() }

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

    fun byId(id: Int): Voice = all.getOrNull(id) ?: all[3]
}
