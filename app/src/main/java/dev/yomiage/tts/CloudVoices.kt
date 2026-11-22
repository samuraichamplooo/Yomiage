package dev.yomiage.tts

import android.util.Base64
import android.util.Log
import dev.yomiage.data.Prefs
import dev.yomiage.data.Voice
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * An online voice can't be used right now. [limit]: a usage limit was reached. [setup]: something
 * only you can fix (a key, or Groq's one-time terms), shown in Voice settings instead of popping up.
 * [link]: where to fix it.
 */
class VoiceUnavailable(
    message: String,
    val limit: Boolean = false,
    val setup: Boolean = false,
    val link: String? = null,
) : Exception(message)

/** Groq's page for Orpheus, where it asks you to accept the model's terms. */
const val ORPHEUS_TERMS_URL = "https://console.groq.com/playground?model=canopylabs%2Forpheus-v1-english"

/** How online voices are asked to read. */
object Delivery {
    const val TEACHER = 0
    const val STORYTELLER = 1
    const val UPBEAT = 2
    const val CALM = 3

    val names = listOf("Engaging teacher", "Storyteller", "Upbeat & fun", "Calm & clear")

    /** Gemini takes a description of the style. */
    fun geminiStyle(d: Int): String = when (d) {
        STORYTELLER -> "a captivating storyteller: warm, vivid and expressive, with natural pauses that build interest"
        UPBEAT -> "an upbeat, fun host who makes everything sound exciting: energetic and smiling, but clear"
        CALM -> "a calm, clear narrator: relaxed, steady pace, gentle and easy to follow"
        else -> "an enthusiastic, warm teacher who makes the material genuinely interesting: lively and " +
            "expressive, natural pace, gently stressing the key terms"
    }

    /** Orpheus takes a short direction in brackets. */
    fun orpheusTag(d: Int): String = when (d) {
        STORYTELLER -> "[dramatic]"
        UPBEAT -> "[excited]"
        CALM -> "[calm]"
        else -> "[cheerful]"
    }
}

/** Online, expressive voices: Gemini's (your Gemini key) and Groq's Orpheus (your Groq key). */
object CloudVoices {
    private const val TAG = "CloudVoices"
    private const val SR = 24000

    // ---------------------------------------------------------------- Gemini

    /** Newest first; the first that works is remembered. */
    private val geminiModels = listOf(
        "gemini-3.8-flash-tts" to true,           // Interactions API
        "gemini-3.8-flash-lite-tts" to true,
        "gemini-3.1-flash-tts-preview" to false,  // generateContent
        "gemini-2.5-flash-preview-tts" to false,
    )
    @Volatile private var geminiModel = -1

    fun gemini(text: String, voice: Voice): FloatArray {
        val key = Prefs.current.geminiKey.trim()
        if (key.isEmpty()) throw VoiceUnavailable("Gemini voices need your Gemini key (under Simple explanations).", setup = true)
        val style = Delivery.geminiStyle(Prefs.current.delivery)
        val order = if (geminiModel >= 0) listOf(geminiModel) + geminiModels.indices.filter { it != geminiModel }
        else geminiModels.indices.toList()
        var last: Exception? = null
        for (i in order) {
            val (model, interactions) = geminiModels[i]
            try {
                val bytes = if (interactions) geminiInteraction(key, model, text, voice.remote, style)
                else geminiGenerate(key, model, text, voice.remote, style)
                geminiModel = i
                return toSamples(bytes)
            } catch (e: VoiceUnavailable) {
                throw e
            } catch (e: ModelGone) {
                last = e
                continue
            }
        }
        throw VoiceUnavailable("Gemini voices aren't available right now (${last?.message ?: "no model"}).")
    }

    private class ModelGone(message: String) : Exception(message)

    private fun geminiInteraction(key: String, model: String, text: String, voice: String, style: String): ByteArray {
        val body = JSONObject()
            .put("model", model)
            .put(
                "input",
                JSONArray().put(
                    JSONObject().put("type", "user_input").put(
                        "content",
                        JSONArray().put(
                            JSONObject().put("type", "text").put("text", text).put(
                                "annotations",
                                JSONArray().put(JSONObject().put("type", "speech_metadata").put("style", style)),
                            )
                        ),
                    )
                ),
            )
            .put("response_format", JSONObject().put("type", "audio"))
            .put("generation_config", JSONObject().put("speech_config", JSONArray().put(JSONObject().put("voice", voice))))
        val json = post("https://generativelanguage.googleapis.com/v1beta/interactions", body, mapOf("x-goog-api-key" to key), gemini = true)
        return findAudio(JSONObject(json)) ?: throw ModelGone("no audio in the reply")
    }

    private fun geminiGenerate(key: String, model: String, text: String, voice: String, style: String): ByteArray {
        val body = JSONObject()
            .put(
                "contents",
                JSONArray().put(
                    JSONObject().put("parts", JSONArray().put(JSONObject().put("text", "Read this aloud as $style:\n$text")))
                ),
            )
            .put(
                "generationConfig",
                JSONObject()
                    .put("responseModalities", JSONArray().put("AUDIO"))
                    .put(
                        "speechConfig",
                        JSONObject().put(
                            "voiceConfig",
                            JSONObject().put("prebuiltVoiceConfig", JSONObject().put("voiceName", voice)),
                        ),
                    ),
            )
        val json = post(
            "https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent", body,
            mapOf("x-goog-api-key" to key), gemini = true,
        )
        return findAudio(JSONObject(json)) ?: throw ModelGone("no audio in the reply")
    }

    /** The longest base64 "data" string anywhere in the reply: the audio. */
    private fun findAudio(o: Any?): ByteArray? {
        var best: String? = null
        fun walk(x: Any?) {
            when (x) {
                is JSONObject -> x.keys().forEach { k ->
                    val v = x.opt(k)
                    if (k == "data" && v is String && v.length > 400 && v.length > (best?.length ?: 0)) best = v else walk(v)
                }
                is JSONArray -> for (i in 0 until x.length()) walk(x.opt(i))
            }
        }
        walk(o)
        return best?.let { Base64.decode(it, Base64.DEFAULT) }
    }

    // ---------------------------------------------------------------- Orpheus (Groq)

    fun orpheus(text: String, voice: Voice): FloatArray {
        val key = Prefs.current.groqKey.trim()
        if (key.isEmpty()) throw VoiceUnavailable("Orpheus voices need your Groq key (under Simple explanations).", setup = true)
        val tag = Delivery.orpheusTag(Prefs.current.delivery)
        // Orpheus takes up to 200 characters at a time (direction included).
        val pieces = split(text, 200 - tag.length - 1)
        val out = ArrayList<FloatArray>()
        for (p in pieces) {
            val body = JSONObject()
                .put("model", "canopylabs/orpheus-v1-english")
                .put("voice", voice.remote)
                .put("input", "$tag $p")
                .put("response_format", "wav")
            val bytes = postBytes("https://api.groq.com/openai/v1/audio/speech", body, mapOf("Authorization" to "Bearer $key"))
            out.add(toSamples(bytes))
        }
        val total = out.sumOf { it.size }
        val all = FloatArray(total)
        var at = 0
        for (a in out) {
            a.copyInto(all, at)
            at += a.size
        }
        return all
    }

    /** Splits text into pieces of at most [max] characters at sentence, clause or word breaks. */
    private fun split(text: String, max: Int): List<String> {
        val out = ArrayList<String>()
        var rest = text.trim()
        while (rest.length > max) {
            val window = rest.substring(0, max)
            var cut = listOf(". ", "? ", "! ", "; ", ": ", ", ", " ").firstNotNullOfOrNull { sep ->
                window.lastIndexOf(sep).takeIf { it > max / 3 }?.let { it + sep.length - 1 }
            } ?: max
            if (cut <= 0) cut = max
            out.add(rest.substring(0, cut).trim())
            rest = rest.substring(cut).trim()
        }
        if (rest.isNotEmpty()) out.add(rest)
        return out
    }

    // ---------------------------------------------------------------- plumbing

    private fun post(url: String, body: JSONObject, headers: Map<String, String>, gemini: Boolean = false): String =
        String(postBytes(url, body, headers, gemini), Charsets.UTF_8)

    private fun postBytes(url: String, body: JSONObject, headers: Map<String, String>, gemini: Boolean = false): ByteArray {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 60_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
        }
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            if (code == 200) return conn.inputStream.use { it.readBytes() }
            val err = conn.errorStream?.use { String(it.readBytes()) }.orEmpty()
            Log.w(TAG, "HTTP $code from $url: ${err.take(300)}")
            val msg = try {
                JSONObject(err).optJSONObject("error")?.optString("message")
            } catch (e: Exception) {
                null
            } ?: "HTTP $code"
            throw when {
                msg.contains("terms", ignoreCase = true) -> VoiceUnavailable(
                    "Groq needs you to accept the Orpheus model's terms once. Sign in to the Groq console, accept " +
                        "them, then tap Try again.",
                    setup = true,
                    link = Regex("""https://console\.groq\.com[^\s"')]*""").find(msg)?.value?.trimEnd('.', ',')
                        ?: ORPHEUS_TERMS_URL,
                )
                code == 429 -> VoiceUnavailable("Usage limit reached for now. $msg".trim(), limit = true)
                code == 401 || code == 403 -> VoiceUnavailable("The key was refused: $msg", setup = true)
                gemini && (code == 404 || code == 400) -> ModelGone(msg)
                else -> VoiceUnavailable(msg)
            }
        } catch (e: IOException) {
            throw VoiceUnavailable("No connection (${e.javaClass.simpleName}).")
        } finally {
            conn.disconnect()
        }
    }

    /** WAV or raw 16-bit PCM → samples in [-1, 1] at 24 kHz. */
    private fun toSamples(bytes: ByteArray): FloatArray {
        var rate = SR
        var channels = 1
        var start = 0
        var end = bytes.size
        if (bytes.size > 44 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF") {
            val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            var p = 12
            while (p + 8 <= bytes.size) {
                val id = String(bytes, p, 4, Charsets.US_ASCII)
                var size = b.getInt(p + 4)
                if (id == "fmt ") {
                    channels = b.getShort(p + 10).toInt().coerceAtLeast(1)
                    rate = b.getInt(p + 12)
                } else if (id == "data") {
                    start = p + 8
                    if (size <= 0 || start + size > bytes.size) size = bytes.size - start   // streamed WAVs
                    end = start + size
                    break
                }
                p += 8 + size + (size and 1)
            }
        }
        val frames = (end - start) / (2 * channels)
        val b = ByteBuffer.wrap(bytes, start, end - start).order(ByteOrder.LITTLE_ENDIAN)
        val mono = FloatArray(frames)
        for (i in 0 until frames) {
            var sum = 0f
            for (c in 0 until channels) sum += b.short / 32768f
            mono[i] = sum / channels
        }
        return Resample.to(mono, rate, SR)
    }
}

/** Simple linear resampling (speech only, so it's plenty). */
object Resample {
    fun to(src: FloatArray, from: Int, to: Int): FloatArray {
        if (from == to || from <= 0 || src.isEmpty()) return src
        val n = (src.size.toLong() * to / from).toInt().coerceAtLeast(1)
        val out = FloatArray(n)
        val step = from.toDouble() / to
        for (i in 0 until n) {
            val x = i * step
            val j = x.toInt()
            val f = (x - j).toFloat()
            val a = src[j.coerceAtMost(src.size - 1)]
            val c = src[(j + 1).coerceAtMost(src.size - 1)]
            out[i] = a + (c - a) * f
        }
        return out
    }
}
