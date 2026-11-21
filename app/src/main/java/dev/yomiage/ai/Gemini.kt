package dev.yomiage.ai

import android.util.Log
import dev.yomiage.data.Prefs
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.UnknownHostException

class GeminiException(message: String) : Exception(message)

/**
 * Talks to Google's Gemini API with the user's own key (free from Google AI Studio).
 * Replies are streamed so reading can start before the whole answer is written.
 */
object Gemini {
    private const val TAG = "Gemini"
    private const val BASE = "https://generativelanguage.googleapis.com/v1beta/models/"

    // Model names change often; "latest" aliases first, then recent fixed names.
    private val models = listOf(
        "gemini-flash-latest", "gemini-3.8-flash", "gemini-3.6-flash", "gemini-flash-lite-latest",
    )

    private sealed interface Attempt {
        data object Ok : Attempt
        data object NotFound : Attempt
        data object BadConfig : Attempt
    }

    /** Streams a reply. [onText] receives each new piece. Blocking: call from a background thread. */
    fun stream(key: String, system: String, user: String, cancelled: () -> Boolean, onText: (String) -> Unit) {
        if (key.isBlank()) throw GeminiException("Add your free Gemini API key in Yomiage first.")
        val saved = Prefs.current.geminiModel
        val order = (listOf(saved) + models).filter { it.isNotBlank() }.distinct()
        for (model in order) {
            for (thinking in listOf(true, false)) {
                when (attempt(key.trim(), model, thinking, system, user, cancelled, onText)) {
                    Attempt.Ok -> {
                        if (model != saved) Prefs.update { it.copy(geminiModel = model) }
                        return
                    }
                    Attempt.NotFound -> break
                    Attempt.BadConfig -> continue
                }
            }
        }
        throw GeminiException("Gemini didn't accept any of the models Yomiage knows. Try again later.")
    }

    private fun attempt(
        key: String,
        model: String,
        thinking: Boolean,
        system: String,
        user: String,
        cancelled: () -> Boolean,
        onText: (String) -> Unit,
    ): Attempt {
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put(
                "contents",
                JSONArray().put(
                    JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", user)))
                ),
            )
            .put(
                "generationConfig",
                JSONObject().put("temperature", 0.4).put("maxOutputTokens", 4096).apply {
                    if (thinking) put("thinkingConfig", JSONObject().put("thinkingLevel", "low"))
                },
            )
        val conn = try {
            (URL("$BASE$model:streamGenerateContent?alt=sse").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 15_000
                readTimeout = 60_000
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("x-goog-api-key", key)
            }
        } catch (e: IOException) {
            throw GeminiException("Couldn't reach Gemini. Check your internet connection.")
        }
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code != 200) {
                val err = try {
                    conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                } catch (e: Exception) {
                    ""
                }
                Log.w(TAG, "HTTP $code from $model: ${err.take(400)}")
                val msg = try {
                    JSONObject(err).optJSONObject("error")?.optString("message") ?: ""
                } catch (e: Exception) {
                    ""
                }
                return when {
                    code == 404 -> Attempt.NotFound
                    code == 400 && (msg.contains("thinking", true) || msg.contains("Unknown name", true)) -> Attempt.BadConfig
                    code == 400 && msg.contains("API key", true) ->
                        throw GeminiException("Your Gemini API key didn't work. Check it in Yomiage.")
                    code == 401 || code == 403 ->
                        throw GeminiException("Your Gemini API key was rejected. Check it in Yomiage.")
                    code == 429 ->
                        throw GeminiException("Gemini's free limit is used up for now. Try again in a minute.")
                    code >= 500 -> throw GeminiException("Gemini is busy right now. Try again in a moment.")
                    else -> throw GeminiException("Gemini error ($code). ${msg.take(120)}")
                }
            }
            BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { reader ->
                while (true) {
                    if (cancelled()) return Attempt.Ok
                    val line = reader.readLine() ?: break
                    if (!line.startsWith("data:")) continue
                    val json = line.substring(5).trim()
                    if (json.isEmpty() || json == "[DONE]") continue
                    val parts = try {
                        JSONObject(json).optJSONArray("candidates")?.optJSONObject(0)
                            ?.optJSONObject("content")?.optJSONArray("parts")
                    } catch (e: Exception) {
                        null
                    } ?: continue
                    for (i in 0 until parts.length()) {
                        val p = parts.optJSONObject(i) ?: continue
                        if (p.optBoolean("thought", false)) continue
                        val t = p.optString("text", "")
                        if (t.isNotEmpty()) onText(t)
                    }
                }
            }
            return Attempt.Ok
        } catch (e: UnknownHostException) {
            throw GeminiException("Couldn't reach Gemini. Check your internet connection.")
        } catch (e: IOException) {
            throw GeminiException("The connection to Gemini dropped. Try again.")
        } finally {
            conn.disconnect()
        }
    }

    /** A tiny request to check that a key works. */
    fun test(key: String) {
        val sb = StringBuilder()
        stream(key, "Reply with one word.", "Say ready.", { false }) { sb.append(it) }
        if (sb.isBlank()) throw GeminiException("Gemini answered with nothing. Try again.")
    }
}
