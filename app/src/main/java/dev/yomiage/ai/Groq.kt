package dev.yomiage.ai

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

/** Backup AI: Groq's free, very fast API (OpenAI-compatible), with the user's own free key. */
object Groq {
    private const val TAG = "Groq"
    private const val BASE = "https://api.groq.com/openai/v1"

    // Good, fast general models first. Reasoning models come later because they're slower to start.
    private val preferred = listOf(
        "llama-3.3-70b-versatile",
        "meta-llama/llama-4-maverick-17b-128e-instruct",
        "meta-llama/llama-4-scout-17b-16e-instruct",
        "openai/gpt-oss-120b",
        "openai/gpt-oss-20b",
        "llama-3.1-8b-instant",
    )

    @Volatile
    private var available: List<String>? = null

    @Volatile
    var lastModel: String = ""
        private set

    private sealed interface Result {
        data object Ok : Result
        data object NotFound : Result
        data class Busy(val detail: String) : Result
    }

    fun stream(key: String, system: String, user: String, cancelled: () -> Boolean, onText: (String) -> Unit) {
        if (key.isBlank()) throw AiException("Add a free Groq key in Yomiage first.")
        val have = available ?: listModels(key.trim()).also { if (it.isNotEmpty()) available = it }
        val order = if (have.isEmpty()) {
            preferred
        } else {
            val picks = preferred.filter { it in have }
            picks.ifEmpty { have.filter { chatModel(it) }.take(3) }
        }
        var detail = ""
        for (model in order.take(4)) {
            if (cancelled()) return
            when (val r = attempt(key.trim(), model, system, user, cancelled, onText)) {
                Result.Ok -> {
                    lastModel = model
                    return
                }
                Result.NotFound -> continue
                is Result.Busy -> detail = r.detail
            }
        }
        throw AiException("Groq is busy or its free limit is used up for now." + if (detail.isNotBlank()) " ($detail)" else "")
    }

    private fun chatModel(id: String): Boolean {
        val s = id.lowercase()
        return listOf("whisper", "tts", "guard", "embed", "compound", "playai", "orpheus", "prompt-guard").none { it in s }
    }

    private fun listModels(key: String): List<String> = try {
        val c = URL("$BASE/models").openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 15_000
        c.setRequestProperty("Authorization", "Bearer $key")
        try {
            when (c.responseCode) {
                200 -> {
                    val arr = JSONObject(c.inputStream.bufferedReader().use { it.readText() }).optJSONArray("data")
                    (0 until (arr?.length() ?: 0)).mapNotNull { arr?.optJSONObject(it)?.optString("id") }
                }
                401, 403 -> throw AiException("Your Groq key was rejected. Copy it again from console.groq.com.")
                else -> emptyList()
            }
        } finally {
            c.disconnect()
        }
    } catch (e: AiException) {
        throw e
    } catch (e: UnknownHostException) {
        throw AiException("Couldn't reach Groq. Check your internet connection.")
    } catch (e: Exception) {
        Log.w(TAG, "model list failed", e)
        emptyList()
    }

    private fun attempt(
        key: String,
        model: String,
        system: String,
        user: String,
        cancelled: () -> Boolean,
        onText: (String) -> Unit,
    ): Result {
        val body = JSONObject()
            .put("model", model)
            .put(
                "messages",
                JSONArray()
                    .put(JSONObject().put("role", "system").put("content", system))
                    .put(JSONObject().put("role", "user").put("content", user)),
            )
            .put("temperature", 0.4)
            .put("max_tokens", 2048)
            .put("stream", true)
        var conn: HttpURLConnection? = null
        var got = false
        try {
            val c = URL("$BASE/chat/completions").openConnection() as HttpURLConnection
            conn = c
            c.requestMethod = "POST"
            c.doOutput = true
            c.connectTimeout = 10_000
            c.readTimeout = 30_000
            c.setRequestProperty("Content-Type", "application/json")
            c.setRequestProperty("Authorization", "Bearer $key")
            c.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = c.responseCode
            if (code != 200) {
                val err = try {
                    c.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
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
                    code == 401 || code == 403 -> throw AiException("Your Groq key was rejected. Copy it again from console.groq.com.")
                    code == 404 || msg.contains("decommissioned", true) || msg.contains("does not exist", true) -> Result.NotFound
                    else -> Result.Busy("$code: ${msg.take(120)}")
                }
            }
            var thinking = false
            BufferedReader(InputStreamReader(c.inputStream, Charsets.UTF_8)).use { reader ->
                while (true) {
                    if (cancelled()) return Result.Ok
                    val line = reader.readLine() ?: break
                    if (!line.startsWith("data:")) continue
                    val json = line.substring(5).trim()
                    if (json.isEmpty() || json == "[DONE]") continue
                    val delta = try {
                        JSONObject(json).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta")
                    } catch (e: Exception) {
                        null
                    } ?: continue
                    val raw = if (delta.isNull("content")) "" else delta.optString("content", "")
                    // Some models think out loud inside <think> tags: skip that part.
                    val shown = StringBuilder()
                    var rest = raw
                    while (rest.isNotEmpty()) {
                        if (thinking) {
                            val e = rest.indexOf("</think>")
                            if (e < 0) {
                                rest = ""
                            } else {
                                thinking = false
                                rest = rest.substring(e + 8)
                            }
                        } else {
                            val st = rest.indexOf("<think>")
                            if (st < 0) {
                                shown.append(rest)
                                rest = ""
                            } else {
                                shown.append(rest, 0, st)
                                thinking = true
                                rest = rest.substring(st + 7)
                            }
                        }
                    }
                    val t = shown.toString()
                    if (t.isNotEmpty()) {
                        got = true
                        onText(t)
                    }
                }
            }
            return if (got) Result.Ok else Result.Busy("empty reply")
        } catch (e: AiException) {
            throw e
        } catch (e: UnknownHostException) {
            throw AiException("Couldn't reach Groq. Check your internet connection.")
        } catch (e: SocketTimeoutException) {
            return if (got) Result.Ok else Result.Busy("timed out")
        } catch (e: IOException) {
            return if (got) Result.Ok else Result.Busy(e.javaClass.simpleName)
        } finally {
            conn?.disconnect()
        }
    }
}
