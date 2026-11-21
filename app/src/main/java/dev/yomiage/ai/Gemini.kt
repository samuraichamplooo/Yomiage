package dev.yomiage.ai

import android.os.SystemClock
import android.util.Log
import dev.yomiage.data.Prefs
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

class GeminiException(message: String) : Exception(message)

/**
 * Talks to Google's Gemini API with the user's own key (free from Google AI Studio).
 * Replies are streamed so reading can start before the whole answer is written.
 *
 * The free tier is often "overloaded" (HTTP 503) or out of quota for one model (429) while
 * another model still works, so this retries and falls back through several free models.
 */
object Gemini {
    private const val TAG = "Gemini"
    private const val BASE = "https://generativelanguage.googleapis.com/v1beta/models/"

    // Model names change often: "latest" aliases plus recent fixed names, Flash first, then Flash-Lite.
    private val models = listOf(
        "gemini-flash-latest", "gemini-3.8-flash", "gemini-3.6-flash",
        "gemini-flash-lite-latest", "gemini-3.5-flash-lite", "gemini-3.1-flash-lite",
    )

    private sealed interface Result {
        data object Ok : Result
        data object NotFound : Result          // no such model: try the next
        data object BadConfig : Result         // the model doesn't like our settings: retry plainer
        data class Busy(val detail: String) : Result   // overloaded / server error: retry, then next model
        data class Quota(val detail: String) : Result  // this model's free quota is used up: next model
    }

    /** The model that answered last, for the key test. */
    @Volatile
    var lastModel: String = ""
        private set

    /**
     * Streams a reply. [onText] receives each new piece; [onStatus] hears about retries.
     * Blocking: call from a background thread.
     */
    fun stream(
        key: String,
        system: String,
        user: String,
        cancelled: () -> Boolean,
        timeBudgetMs: Long = 60_000,
        onStatus: (String) -> Unit = {},
        onText: (String) -> Unit,
    ) {
        if (key.isBlank()) throw GeminiException("Add your free Gemini API key in Yomiage first.")
        val deadline = SystemClock.elapsedRealtime() + timeBudgetMs
        val saved = Prefs.current.geminiModel
        val order = (listOf(saved) + models).filter { it.isNotBlank() }.distinct()
        var busy = 0
        var quota = 0
        var lastDetail = ""
        for ((mi, model) in order.withIndex()) {
            var thinking = true
            var tries = 0
            while (tries < 3) {
                if (cancelled()) return
                if (SystemClock.elapsedRealtime() > deadline) break
                tries++
                val r = attempt(key.trim(), model, thinking, system, user, cancelled, onText)
                when (r) {
                    Result.Ok -> {
                        lastModel = model
                        if (model != saved) Prefs.update { it.copy(geminiModel = model) }
                        return
                    }
                    Result.NotFound -> break
                    Result.BadConfig -> {
                        if (!thinking) break
                        thinking = false
                    }
                    is Result.Quota -> {
                        quota++
                        lastDetail = r.detail
                        break
                    }
                    is Result.Busy -> {
                        busy++
                        lastDetail = r.detail
                        if (tries >= 2) break
                        onStatus("Gemini is busy, trying again…")
                        Thread.sleep(1200)
                    }
                }
            }
            if (SystemClock.elapsedRealtime() > deadline) break
            if (mi < order.size - 1 && (busy > 0 || quota > 0)) onStatus("Gemini is busy, trying another model…")
        }
        val why = if (lastDetail.isNotBlank()) " ($lastDetail)" else ""
        throw GeminiException(
            when {
                busy > 0 && quota == 0 -> "Google's free Gemini models are overloaded right now. Try again in a minute.$why"
                quota > 0 && busy == 0 -> "Your free Gemini limit is used up for now. It resets daily.$why"
                busy > 0 -> "Gemini is busy and some free limits are used up. Try again in a minute.$why"
                else -> "Gemini didn't accept any of the models Yomiage knows.$why"
            }
        )
    }

    private fun attempt(
        key: String,
        model: String,
        thinking: Boolean,
        system: String,
        user: String,
        cancelled: () -> Boolean,
        onText: (String) -> Unit,
    ): Result {
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
        var conn: HttpURLConnection? = null
        var gotText = false
        try {
            val c = URL("$BASE$model:streamGenerateContent?alt=sse").openConnection() as HttpURLConnection
            conn = c
            c.requestMethod = "POST"
            c.doOutput = true
            c.connectTimeout = 15_000
            c.readTimeout = 40_000
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            c.setRequestProperty("x-goog-api-key", key)
            c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            if (code != 200) {
                val err = try {
                    c.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                } catch (e: Exception) {
                    ""
                }
                Log.w(TAG, "HTTP $code from $model: ${err.take(500)}")
                val msg = try {
                    JSONObject(err).optJSONObject("error")?.optString("message") ?: ""
                } catch (e: Exception) {
                    ""
                }
                val detail = "$code: ${msg.take(140)}".trimEnd(' ', ':')
                return when {
                    code == 404 -> Result.NotFound
                    code == 400 && msg.contains("API key", true) ->
                        throw GeminiException("Your Gemini API key didn't work. Copy it again from Google AI Studio. ($detail)")
                    code == 400 && thinking -> Result.BadConfig
                    code == 401 || code == 403 ->
                        throw GeminiException("Your Gemini API key was rejected. Copy it again from Google AI Studio. ($detail)")
                    code == 429 -> Result.Quota(detail)
                    code >= 500 -> Result.Busy(detail)
                    else -> throw GeminiException("Gemini error ($detail)")
                }
            }
            BufferedReader(InputStreamReader(c.inputStream, Charsets.UTF_8)).use { reader ->
                while (true) {
                    if (cancelled()) return Result.Ok
                    val line = reader.readLine() ?: break
                    if (!line.startsWith("data:")) continue
                    val json = line.substring(5).trim()
                    if (json.isEmpty() || json == "[DONE]") continue
                    val obj = try {
                        JSONObject(json)
                    } catch (e: Exception) {
                        continue
                    }
                    obj.optJSONObject("error")?.let { e ->
                        // An error in the middle of the stream.
                        val detail = "${e.optInt("code")}: ${e.optString("message").take(140)}"
                        if (!gotText) return Result.Busy(detail)
                        return Result.Ok
                    }
                    val parts = obj.optJSONArray("candidates")?.optJSONObject(0)
                        ?.optJSONObject("content")?.optJSONArray("parts") ?: continue
                    for (i in 0 until parts.length()) {
                        val p = parts.optJSONObject(i) ?: continue
                        if (p.optBoolean("thought", false)) continue
                        val t = p.optString("text", "")
                        if (t.isNotEmpty()) {
                            gotText = true
                            onText(t)
                        }
                    }
                }
            }
            return if (gotText) Result.Ok else Result.Busy("empty reply")
        } catch (e: UnknownHostException) {
            throw GeminiException("Couldn't reach Gemini. Check your internet connection.")
        } catch (e: SocketTimeoutException) {
            return if (gotText) Result.Ok else Result.Busy("timed out")
        } catch (e: IOException) {
            if (gotText) return Result.Ok
            Log.w(TAG, "connection problem with $model", e)
            return Result.Busy(e.javaClass.simpleName)
        } finally {
            conn?.disconnect()
        }
    }

    /** A tiny request to check that a key works. Returns the model that answered. */
    fun test(key: String): String {
        val sb = StringBuilder()
        stream(key, "Reply with one word.", "Say ready.", { false }, timeBudgetMs = 45_000) { sb.append(it) }
        if (sb.isBlank()) throw GeminiException("Gemini answered with nothing. Try again.")
        return lastModel
    }
}
