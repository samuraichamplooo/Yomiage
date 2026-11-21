package dev.yomiage

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import dev.yomiage.ui.HomeScreen
import dev.yomiage.ui.YomiageTheme

class MainActivity : ComponentActivity() {
    companion object {
        const val EXTRA_TEXT = "dev.yomiage.TEXT"
    }

    private val pendingText = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        pendingText.value = intent.getStringExtra(EXTRA_TEXT)
        setContent {
            YomiageTheme {
                HomeScreen(pendingText)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_TEXT)?.let { pendingText.value = it }
    }
}
