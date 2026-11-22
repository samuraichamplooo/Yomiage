package dev.yomiage.tts

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

sealed interface ModelStatus {
    data object Missing : ModelStatus
    data class Downloading(val bytes: Long, val total: Long) : ModelStatus
    data class Unpacking(val fraction: Float) : ModelStatus
    data object Ready : ModelStatus
    data class Failed(val message: String) : ModelStatus
}

/**
 * Downloads the Kokoro v1.0 voice pack (all 54 voices, ~330 MB) from the sherpa-onnx
 * model releases once, and unpacks the files the engine needs into the app's storage.
 */
object ModelManager : ModelPack(
    name = "kokoro-multi-lang-v1_0",
    neededSpace = 900L * 1024 * 1024,
    required = listOf("model.onnx", "voices.bin", "tokens.txt", "espeak-ng-data/phontab"),
    wanted = { rel ->
        rel == "model.onnx" || rel == "voices.bin" || rel == "tokens.txt" ||
            rel == "lexicon-zh.txt" || rel.startsWith("espeak-ng-data/")
    },
) {
    override fun onDelete() = KokoroEngine.release()
}

/**
 * Supertonic 3 (Supertone): a fast, natural on-device voice model with 10 voices (~100M
 * parameters), also from the sherpa-onnx model releases.
 */
object SupertonicPack : ModelPack(
    name = "sherpa-onnx-supertonic-3-tts-int8-2026-05-11",
    neededSpace = 500L * 1024 * 1024,
    required = listOf(
        "duration_predictor.int8.onnx", "text_encoder.int8.onnx", "vector_estimator.int8.onnx",
        "vocoder.int8.onnx", "tts.json", "unicode_indexer.bin", "voice.bin",
    ),
    wanted = { rel -> !rel.contains('/') && (rel.endsWith(".onnx") || rel.endsWith(".json") || rel.endsWith(".bin")) },
) {
    override fun onDelete() = SupertonicEngine.release()
}

/** One downloadable voice model pack (a .tar.bz2 from the sherpa-onnx releases). */
abstract class ModelPack(
    val name: String,
    private val neededSpace: Long,
    private val required: List<String>,
    private val wanted: (String) -> Boolean,
) {
    private val TAG = "ModelPack"
    @Suppress("PropertyName")
    val NAME get() = name
    private val URL_PACK get() = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/$name.tar.bz2"
    private val NEEDED_SPACE get() = neededSpace

    protected open fun onDelete() {}

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    private val _status = MutableStateFlow<ModelStatus>(ModelStatus.Missing)
    val status: StateFlow<ModelStatus> = _status

    fun dir(context: Context) = File(context.filesDir, NAME)
    fun isReady(context: Context) = File(dir(context), ".complete").exists()

    fun refresh(context: Context) {
        if (job?.isActive == true) return
        _status.value = if (isReady(context)) ModelStatus.Ready else ModelStatus.Missing
    }

    fun start(context: Context) {
        val app = context.applicationContext
        if (job?.isActive == true || isReady(app)) return
        job = scope.launch {
            try {
                if (app.filesDir.usableSpace < NEEDED_SPACE) {
                    throw IOException("Not enough free space. The voice pack needs about ${NEEDED_SPACE / (1024 * 1024)} MB while it unpacks.")
                }
                val tmp = File(app.cacheDir, "$NAME.tar.bz2")
                var tries = 0
                while (true) {
                    tries++
                    download(tmp)
                    try {
                        unpack(tmp, dir(app))
                        break
                    } catch (e: Exception) {
                        // A broken or partial download: start fresh once.
                        Log.w(TAG, "unpack failed", e)
                        tmp.delete()
                        if (tries >= 2) throw e
                    }
                }
                tmp.delete()
                _status.value = ModelStatus.Ready
            } catch (e: Exception) {
                Log.e(TAG, "voice pack failed", e)
                _status.value = ModelStatus.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun delete(context: Context) {
        job?.cancel()
        onDelete()
        dir(context).deleteRecursively()
        File(context.cacheDir, "$NAME.tar.bz2").delete()
        _status.value = ModelStatus.Missing
    }

    private fun download(tmp: File) {
        var attempt = 0
        while (true) {
            attempt++
            val have = if (tmp.exists()) tmp.length() else 0L
            val conn = (URL(URL_PACK).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 30_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Yomiage")
                if (have > 0) setRequestProperty("Range", "bytes=$have-")
            }
            try {
                val code = conn.responseCode
                if (code == 416 && have > 0) return // already have the whole file
                if (code != 200 && code != 206) throw IOException("Download failed (HTTP $code)")
                val append = code == 206
                val length = conn.contentLengthLong
                val total = if (length > 0) (if (append) have + length else length) else -1L
                var done = if (append) have else 0L
                _status.value = ModelStatus.Downloading(done, total)
                conn.inputStream.use { input ->
                    FileOutputStream(tmp, append).use { out ->
                        val buf = ByteArray(1 shl 16)
                        var lastUi = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (done - lastUi > 512 * 1024) {
                                lastUi = done
                                _status.value = ModelStatus.Downloading(done, total)
                            }
                        }
                    }
                }
                if (total > 0 && done < total) throw IOException("Connection dropped")
                return
            } catch (e: IOException) {
                if (attempt >= 6) throw e
                Log.w(TAG, "download interrupted, resuming", e)
                Thread.sleep(1500L * attempt)
            } finally {
                conn.disconnect()
            }
        }
    }

    private fun unpack(tmp: File, dest: File) {
        val work = File(dest.parentFile, "$NAME.partial")
        work.deleteRecursively()
        work.mkdirs()
        val total = tmp.length().coerceAtLeast(1)
        val counter = CountingStream(FileInputStream(tmp))
        _status.value = ModelStatus.Unpacking(0f)
        BZip2CompressorInputStream(BufferedInputStream(counter, 1 shl 16)).use { bz ->
            TarArchiveInputStream(bz).use { tar ->
                var lastUi = 0L
                var entry = tar.nextTarEntry
                while (entry != null) {
                    var rel = entry.name.removePrefix("./")
                    if (rel.startsWith("$NAME/")) rel = rel.removePrefix("$NAME/")
                    if (rel.isNotEmpty() && !rel.contains("..") && wanted(rel)) {
                        val f = File(work, rel)
                        if (entry.isDirectory) {
                            f.mkdirs()
                        } else if (entry.isFile) {
                            f.parentFile?.mkdirs()
                            FileOutputStream(f).use { tar.copyTo(it, 1 shl 16) }
                        }
                    }
                    if (counter.count - lastUi > 1024 * 1024) {
                        lastUi = counter.count
                        _status.value = ModelStatus.Unpacking(counter.count.toFloat() / total)
                    }
                    entry = tar.nextTarEntry
                }
            }
        }
        for (f in required) {
            if (!File(work, f).exists()) throw IOException("The voice pack is missing $f")
        }
        dest.deleteRecursively()
        if (!work.renameTo(dest)) throw IOException("Couldn't move the voice pack into place")
        File(dest, ".complete").writeText("ok")
    }

    private class CountingStream(input: InputStream) : FilterInputStream(input) {
        var count = 0L
            private set

        override fun read(): Int {
            val b = super.read()
            if (b >= 0) count++
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = super.read(b, off, len)
            if (n > 0) count += n
            return n
        }

        override fun skip(n: Long): Long {
            val s = super.skip(n)
            count += s
            return s
        }
    }
}
