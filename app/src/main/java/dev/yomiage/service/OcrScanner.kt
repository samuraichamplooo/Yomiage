package dev.yomiage.service

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import kotlinx.coroutines.CancellableContinuation
import androidx.annotation.RequiresApi
import android.view.Display
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Reads text from a screenshot with on-device ML Kit, for images, PDFs, games and apps that
 * don't share their text with accessibility services.
 */
object OcrScanner {
    private const val TAG = "OcrScanner"

    suspend fun scan(service: AccessibilityService): List<ScreenBlock> = try {
        scanInternal(service)
    } catch (e: Throwable) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        Log.e(TAG, "text recognition unavailable", e)
        emptyList()
    }

    private suspend fun scanInternal(service: AccessibilityService): List<ScreenBlock> {
        val bmp = screenshot(service) ?: return emptyList()
        try {
            val text = recognize(bmp) ?: return emptyList()
            val top = statusBarHeight(service)
            val blocks = text.textBlocks.mapNotNull { tb ->
                val box = tb.boundingBox ?: return@mapNotNull null
                if (box.bottom <= top) return@mapNotNull null // clock, battery, notifications
                val parts = ArrayList<Part>()
                val sb = StringBuilder()
                for (line in tb.lines) {
                    val t = line.text.trim()
                    if (t.isEmpty()) continue
                    if (sb.isNotEmpty()) {
                        // Re-join words hyphenated across lines.
                        if (sb.endsWith("-") && t.first().isLowerCase()) sb.setLength(sb.length - 1) else sb.append(' ')
                    }
                    parts.add(Part(sb.length, t, line.boundingBox ?: box, null))
                    sb.append(t)
                }
                if (sb.isEmpty()) null else ScreenBlock(sb.toString(), Rect(box), parts)
            }
            // Top-to-bottom, then left-to-right for blocks on the same row.
            return blocks.sortedWith(compareBy<ScreenBlock>({ (it.bounds.top + 12) / 24 }, { it.bounds.left }))
        } finally {
            bmp.recycle()
        }
    }

    internal suspend fun screenshot(service: AccessibilityService): Bitmap? =
        suspendCancellableCoroutine { cont ->
            try {
                service.takeScreenshot(Display.DEFAULT_DISPLAY, service.mainExecutor, shotCallback(cont))
            } catch (e: Exception) {
                Log.w(TAG, "screenshot failed", e)
                if (cont.isActive) cont.resume(null)
            }
        }

    /**
     * A screenshot of just one app window (Android 14 and newer): no Yomiage player or highlight
     * on top, and nothing needs hiding first.
     */
    @RequiresApi(34)
    internal suspend fun windowScreenshot(service: AccessibilityService, windowId: Int): Bitmap? =
        suspendCancellableCoroutine { cont ->
            try {
                service.takeScreenshotOfWindow(windowId, service.mainExecutor, shotCallback(cont))
            } catch (e: Exception) {
                Log.w(TAG, "window screenshot failed", e)
                if (cont.isActive) cont.resume(null)
            }
        }

    private fun shotCallback(cont: CancellableContinuation<Bitmap?>) =
        object : AccessibilityService.TakeScreenshotCallback {
            override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                val buffer = result.hardwareBuffer
                val bmp = try {
                    Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)
                } catch (e: Exception) {
                    Log.w(TAG, "screenshot convert failed", e)
                    null
                } finally {
                    buffer.close()
                }
                if (cont.isActive) cont.resume(bmp)
            }

            override fun onFailure(errorCode: Int) {
                Log.w(TAG, "screenshot failed: $errorCode")
                if (cont.isActive) cont.resume(null)
            }
        }

    private suspend fun recognize(bmp: Bitmap): Text? = suspendCancellableCoroutine { cont ->
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        recognizer.process(InputImage.fromBitmap(bmp, 0))
            .addOnSuccessListener { if (cont.isActive) cont.resume(it) }
            .addOnFailureListener {
                Log.w(TAG, "recognition failed", it)
                if (cont.isActive) cont.resume(null)
            }
            .addOnCompleteListener { recognizer.close() }
    }

    private fun statusBarHeight(service: AccessibilityService): Int {
        val id = service.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (id > 0) service.resources.getDimensionPixelSize(id) else 0
    }
}
