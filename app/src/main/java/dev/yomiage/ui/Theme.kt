package dev.yomiage.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Sumi ink, washi paper and a vermilion seal.
val InkBlack = Color(0xFF0E0D0C)
val Card = Color(0xFF181614)
val CardHigh = Color(0xFF221F1C)
val PaperWhite = Color(0xFFF3EDE4)
val Seal = Color(0xFFD8462B)
val Faint = Color(0xFF9A928A)

private val scheme = darkColorScheme(
    primary = Seal,
    onPrimary = PaperWhite,
    primaryContainer = Color(0xFF5A1E12),
    onPrimaryContainer = Color(0xFFFFDAD2),
    secondary = Color(0xFFE7BDB2),
    onSecondary = Color(0xFF442A23),
    background = InkBlack,
    onBackground = PaperWhite,
    surface = InkBlack,
    onSurface = PaperWhite,
    surfaceVariant = CardHigh,
    onSurfaceVariant = Color(0xFFD1C7BE),
    outline = Color(0xFF6E6760),
    outlineVariant = Color(0xFF2E2A26),
    surfaceContainerLowest = InkBlack,
    surfaceContainerLow = Color(0xFF141210),
    surfaceContainer = Card,
    surfaceContainerHigh = CardHigh,
    surfaceContainerHighest = Color(0xFF2B2724),
)

@Composable
fun YomiageTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, content = content)
}
