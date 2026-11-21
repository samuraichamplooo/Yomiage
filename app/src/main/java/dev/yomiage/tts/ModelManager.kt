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
object ModelManager {
    private const val TAG = "ModelManager"
    const val NAME = "kokoro-multi-lang-v1_0"
    private const val URL_PACK =
        "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/$NAME.tar.bz2"
    private const val NEEDED_SPACE = 900L * 1024 * 1024

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
                    throw IOException("Not enough free space. The voice pack needs about 900 MB while it unpacks.")
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
        KokoroEngine.release()
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

    private fun wanted(rel: String) =
        rel == "model.onnx" || rel == "voices.bin" || rel == "tokens.txt" ||
            rel == "lexicon-zh.txt" || rel.startsWith("espeak-ng-data/")

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
        for (name in listOf("model.onnx", "voices.bin", "tokens.txt", "espeak-ng-data/phontab")) {
            if (!File(work, name).exists()) throw IOException("The voice pack is missing $name")
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
