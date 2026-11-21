package dev.yomiage.theme

import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect

/**
 * Keeps an activity's window in step with the look: glass shows the wallpaper (blurred) behind
 * it, and the status and navigation bar icons are light or dark to match.
 */
@Composable
fun Styled(activity: ComponentActivity) {
    val p = Look.palette
    LaunchedEffect(p.kind, p.dark) {
        Look.styleWindow(activity)
        val bars = if (p.dark) SystemBarStyle.dark(Color.TRANSPARENT)
        else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        activity.enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
    }
}
