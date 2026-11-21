package dev.yomiage

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import dev.yomiage.reader.Reader
import dev.yomiage.reader.TextChunker
import dev.yomiage.service.ReaderService
import dev.yomiage.tts.ModelManager

/** Handles "Read aloud" from the text-selection menu and text shared from other apps. */
class ReadTextActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val text = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
            ?: intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
        if (!text.isNullOrBlank()) {
            if (ReaderService.instance != null && ModelManager.isReady(this)) {
                // The floating player shows up on its own.
                Reader.read(TextChunker.paragraphs(text))
            } else {
                startActivity(
                    Intent(this, MainActivity::class.java)
                        .putExtra(MainActivity.EXTRA_TEXT, text)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
        finish()
    }
}
