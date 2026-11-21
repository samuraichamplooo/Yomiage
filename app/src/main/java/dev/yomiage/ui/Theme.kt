package dev.yomiage.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import dev.yomiage.theme.Look

// The app's colors come from the chosen look (classic sumi ink and vermilion, Material You, or glass).
val InkBlack: Color get() = Look.appBackground()
val Card: Color get() = Look.palette.card
val CardHigh: Color get() = Look.palette.cardHigh
val PaperWhite: Color get() = Look.palette.text
val Seal: Color get() = Look.palette.accent
val OnSeal: Color get() = Look.palette.onAccent
val Faint: Color get() = Look.palette.muted
val SoftText: Color get() = Look.palette.textSoft
val Line: Color get() = Look.palette.outline
val Good: Color get() = Look.palette.good
val Bad: Color get() = Look.palette.bad

@Composable
fun YomiageTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Look.scheme, content = content)
}
