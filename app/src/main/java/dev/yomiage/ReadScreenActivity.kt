package dev.yomiage

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import dev.yomiage.service.ReaderService
import dev.yomiage.tts.ModelManager

/**
 * The "Read My Screen" launcher entry. It shows nothing: it closes at once and Yomiage reads the
 * app underneath (or, while reading, shows or hides the player). That makes it work with "Hey Google, open Read My Screen", a side-button
 * double press set to open an app, routines and app-icon shortcuts.
 */
class ReadScreenActivity : Activity() {
    companion object {
        const val ACTION_READ = "dev.yomiage.READ_SCREEN"
        const val ACTION_TAP = "dev.yomiage.TAP_TO_READ"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val service = ReaderService.instance
        if (service == null || !ModelManager.isReady(this)) {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } else {
            val tap = intent?.action == ACTION_TAP ||
                intent?.component?.className?.endsWith("TapToReadLauncher") == true
            service.gesture(tapMode = tap)
        }
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}
