package dev.yomiage.ai

import android.os.SystemClock
import dev.yomiage.data.Prefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

open class AiException(message: String) : Exception(message)

enum class Engine(val label: String) {
    GEMINI("Gemini"),
    GROQ("Groq"),
    ON_DEVICE("On-device Gemini Nano"),
}

/**
 * Picks an AI to write simple explanations: Gemini first, then Groq, then Gemini Nano on the phone.
 * If one fails, the next is tried right away, and the one that failed waits at the back of the line
 * for a while so you don't sit through it again.
 */
object Ai {
    private const val COOL_DOWN_MS = 10 * 60_000L
    private val coolDown = HashMap<Engine, Long>()

    @Volatile
    var lastEngine: Engine? = null
        private set

    fun configured(): List<Engine> {
        val s = Prefs.current
        val list = ArrayList<Engine>()
        if (s.geminiKey.isNotBlank()) list.add(Engine.GEMINI)
        if (s.groqKey.isNotBlank()) list.add(Engine.GROQ)
        if (s.useOnDevice && OnDevice.mightWork()) list.add(Engine.ON_DEVICE)
        return list
    }

    fun canTry() = configured().isNotEmpty()

    private fun ordered(): List<Engine> {
        val now = SystemClock.elapsedRealtime()
        // Stable sort: keeps the preferred order, but engines that just failed go last.
        return configured().sortedBy { if ((coolDown[it] ?: 0L) > now) 1 else 0 }
    }

    /** Streams an answer from the first engine that works. Returns the engine that answered. */
    suspend fun stream(
        system: String,
        user: String,
        cancelled: () -> Boolean,
        onStatus: (String) -> Unit = {},
        onText: (String) -> Unit,
    ): Engine {
        val engines = ordered()
        if (engines.isEmpty()) {
            throw AiException("Add a free Gemini or Groq key, or turn on on-device AI, in Yomiage.")
        }
        var lastError: Exception? = null
        for ((i, engine) in engines.withIndex()) {
            if (cancelled()) throw CancellationException("cancelled")
            var produced = false
            val sink: (String) -> Unit = {
                produced = true
                onText(it)
            }
            val hasBackup = i < engines.size - 1
            try {
                runEngine(engine, system, user, cancelled, hasBackup, onStatus, sink)
                coolDown.remove(engine)
                lastEngine = engine
                return engine
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (produced) {
                    // Part of an answer arrived: keep it rather than starting over elsewhere.
                    lastEngine = engine
                    return engine
                }
                lastError = e
                coolDown[engine] = SystemClock.elapsedRealtime() + COOL_DOWN_MS
                if (hasBackup) onStatus("${engine.label} isn't answering. Trying ${engines[i + 1].label}…")
            }
        }
        throw AiException(lastError?.message ?: "None of the AI options answered. Try again in a minute.")
    }

    private suspend fun runEngine(
        engine: Engine,
        system: String,
        user: String,
        cancelled: () -> Boolean,
        hasBackup: Boolean,
        onStatus: (String) -> Unit,
        onText: (String) -> Unit,
    ) {
        when (engine) {
            Engine.GEMINI -> withContext(Dispatchers.IO) {
                Gemini.stream(
                    Prefs.current.geminiKey, system, user, cancelled,
                    // Don't wait long on a busy Gemini when there's something else to try.
                    timeBudgetMs = if (hasBackup) 20_000 else 50_000,
                    onStatus = onStatus, onText = onText,
                )
            }
            Engine.GROQ -> withContext(Dispatchers.IO) {
                Groq.stream(Prefs.current.groqKey, system, user, cancelled, onText)
            }
            Engine.ON_DEVICE -> OnDevice.stream("$system\n\n$user", cancelled, onText)
        }
    }

    /** Tries every engine separately with a tiny request. Returns one line per engine. */
    suspend fun testAll(): List<String> {
        val s = Prefs.current
        val out = ArrayList<String>()
        for (engine in Engine.values()) {
            val skip = when (engine) {
                Engine.GEMINI -> if (s.geminiKey.isBlank()) "no key added" else null
                Engine.GROQ -> if (s.groqKey.isBlank()) "no key added" else null
                Engine.ON_DEVICE -> if (!s.useOnDevice) "turned off" else null
            }
            if (skip != null) {
                out.add("${engine.label}: $skip")
                continue
            }
            val sb = StringBuilder()
            val line = try {
                runEngine(engine, "Reply with one word.", "Say ready.", { false }, false, {}) { sb.append(it) }
                if (sb.isBlank()) "✗ answered with nothing" else {
                    coolDown.remove(engine)
                    "✓ works" + when (engine) {
                        Engine.GEMINI -> " (${Gemini.lastModel})"
                        Engine.GROQ -> " (${Groq.lastModel})"
                        Engine.ON_DEVICE -> ""
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                "✗ ${e.message ?: e.javaClass.simpleName}"
            }
            out.add("${engine.label}: $line")
        }
        return out
    }
}
