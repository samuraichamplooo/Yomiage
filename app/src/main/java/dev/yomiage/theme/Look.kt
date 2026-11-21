package dev.yomiage.theme

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.view.WindowManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import dev.yomiage.data.Prefs
import java.util.function.Consumer

/** The looks to choose from. */
object ThemeKind {
    const val CLASSIC = 0       // sumi ink, washi paper and a vermilion seal
    const val MATERIAL_YOU = 1  // colors from your wallpaper, light or dark with the phone
    const val GLASS = 2         // see-through frosted glass with bright edges
}

/** Every color the app, the reading view and the floating player use. */
@Immutable
data class Palette(
    val kind: Int,
    val dark: Boolean,
    /** Behind everything in the app (in glass, a tint over the blurred wallpaper). */
    val background: Color,
    /** The floating player and menus. */
    val surface: Color,
    /** How solid the floating player is. */
    val panelAlpha: Float,
    val card: Color,
    val cardHigh: Color,
    val text: Color,
    val textSoft: Color,
    val muted: Color,
    val accent: Color,
    val onAccent: Color,
    val accentContainer: Color,
    val onAccentContainer: Color,
    val outline: Color,
    val hairline: Color,
    /** For faint washes over panels: white on dark looks, black on light ones. */
    val tint: Color,
    val good: Color,
    val bad: Color,
) {
    val glass get() = kind == ThemeKind.GLASS
}

object Look {
    /** The current colors. Reading it from a composable redraws it when the look changes. */
    var palette by mutableStateOf(classic())
        private set

    /** Material 3 colors to match, for buttons, switches and text fields. */
    var scheme by mutableStateOf(classicScheme(classic()))
        private set

    /** Whether the phone is blurring behind windows right now (it stops in power saving). */
    var blurOn by mutableStateOf(false)
        private set

    fun refresh(context: Context) {
        val kind = Prefs.current.theme
        val dark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val dynamic = Build.VERSION.SDK_INT >= 31
        when (kind) {
            ThemeKind.MATERIAL_YOU -> {
                val cs = when {
                    dynamic && dark -> dynamicDarkColorScheme(context)
                    dynamic -> dynamicLightColorScheme(context)
                    dark -> darkColorScheme()
                    else -> lightColorScheme()
                }
                palette = materialYou(cs, dark)
                scheme = cs
            }
            ThemeKind.GLASS -> {
                val accentScheme = when {
                    dynamic && dark -> dynamicDarkColorScheme(context)
                    dynamic -> dynamicLightColorScheme(context)
                    else -> null
                }
                val p = glass(dark, accentScheme)
                palette = p
                scheme = glassScheme(p)
            }
            else -> {
                val p = classic()
                palette = p
                scheme = classicScheme(p)
            }
        }
    }

    // ---------------------------------------------------------------- the looks

    fun classic() = Palette(
        kind = ThemeKind.CLASSIC, dark = true,
        background = Color(0xFF0E0D0C), surface = Color(0xFF141211), panelAlpha = 0.96f,
        card = Color(0xFF181614), cardHigh = Color(0xFF221F1C),
        text = Color(0xFFF3EDE4), textSoft = Color(0xFFD9D1C8), muted = Color(0xFF9A928A),
        accent = Color(0xFFD8462B), onAccent = Color(0xFFF3EDE4),
        accentContainer = Color(0xFF5A1E12), onAccentContainer = Color(0xFFFFDAD2),
        outline = Color(0xFF3A3530), hairline = Color(0x26FFFFFF), tint = Color.White,
        good = Color(0xFFB9E4B0), bad = Color(0xFFFFB4A6),
    )

    private fun materialYou(cs: ColorScheme, dark: Boolean) = Palette(
        kind = ThemeKind.MATERIAL_YOU, dark = dark,
        background = cs.surface, surface = cs.surfaceContainerHigh, panelAlpha = 0.97f,
        card = cs.surfaceContainer, cardHigh = cs.surfaceContainerHighest,
        text = cs.onSurface, textSoft = cs.onSurfaceVariant, muted = cs.outline,
        accent = cs.primary, onAccent = cs.onPrimary,
        accentContainer = cs.primaryContainer, onAccentContainer = cs.onPrimaryContainer,
        outline = cs.outlineVariant, hairline = cs.outlineVariant.copy(alpha = 0.7f), tint = cs.onSurface,
        good = if (dark) Color(0xFFA6D69A) else Color(0xFF2E6B2A), bad = cs.error,
    )

    private fun glass(dark: Boolean, cs: ColorScheme?): Palette {
        val accent = cs?.primary ?: if (dark) Color(0xFF64A8FF) else Color(0xFF0A64D8)
        val onAccent = cs?.onPrimary ?: Color.White
        return if (dark) {
            Palette(
                kind = ThemeKind.GLASS, dark = true,
                background = Color(0x99080809), surface = Color(0xFF1C1C1F), panelAlpha = 0.62f,
                card = Color(0x1AFFFFFF), cardHigh = Color(0x2EFFFFFF),
                text = Color(0xFFF7F7F8), textSoft = Color(0xD9FFFFFF), muted = Color(0x99FFFFFF),
                accent = accent, onAccent = onAccent,
                accentContainer = cs?.primaryContainer ?: Color(0x5564A8FF),
                onAccentContainer = cs?.onPrimaryContainer ?: Color.White,
                outline = Color(0x38FFFFFF), hairline = Color(0x59FFFFFF), tint = Color.White,
                good = Color(0xFFA6E3A1), bad = Color(0xFFFF9F92),
            )
        } else {
            Palette(
                kind = ThemeKind.GLASS, dark = false,
                background = Color(0x73F6F6F8), surface = Color(0xFFF7F7F9), panelAlpha = 0.72f,
                card = Color(0x8CFFFFFF), cardHigh = Color(0x26000000),
                text = Color(0xFF101012), textSoft = Color(0xCC101012), muted = Color(0x8C101012),
                accent = accent, onAccent = onAccent,
                accentContainer = cs?.primaryContainer ?: Color(0x330A64D8),
                onAccentContainer = cs?.onPrimaryContainer ?: Color(0xFF0A2A55),
                outline = Color(0x2E000000), hairline = Color(0xCCFFFFFF), tint = Color.Black,
                good = Color(0xFF2E7D32), bad = Color(0xFFC62828),
            )
        }
    }

    private fun classicScheme(p: Palette) = darkColorScheme(
        primary = p.accent,
        onPrimary = p.onAccent,
        primaryContainer = p.accentContainer,
        onPrimaryContainer = p.onAccentContainer,
        secondary = Color(0xFFE7BDB2),
        onSecondary = Color(0xFF442A23),
        background = p.background,
        onBackground = p.text,
        surface = p.background,
        onSurface = p.text,
        surfaceVariant = p.cardHigh,
        onSurfaceVariant = Color(0xFFD1C7BE),
        outline = Color(0xFF6E6760),
        outlineVariant = Color(0xFF2E2A26),
        surfaceContainerLowest = p.background,
        surfaceContainerLow = Color(0xFF141210),
        surfaceContainer = p.card,
        surfaceContainerHigh = p.cardHigh,
        surfaceContainerHighest = Color(0xFF2B2724),
    )

    private fun glassScheme(p: Palette): ColorScheme {
        val base = if (p.dark) darkColorScheme() else lightColorScheme()
        return base.copy(
            primary = p.accent, onPrimary = p.onAccent,
            primaryContainer = p.accentContainer, onPrimaryContainer = p.onAccentContainer,
            background = Color.Transparent, onBackground = p.text,
            surface = Color.Transparent, onSurface = p.text,
            surfaceVariant = p.cardHigh, onSurfaceVariant = p.textSoft,
            outline = p.muted, outlineVariant = p.outline,
            surfaceContainerLowest = Color.Transparent, surfaceContainerLow = p.card,
            surfaceContainer = p.card, surfaceContainerHigh = p.cardHigh, surfaceContainerHighest = p.cardHigh,
            error = p.bad,
        )
    }

    // ---------------------------------------------------------------- glass helpers

    /** The bright, light-catching rim of a glass panel (a plain hairline in the other looks). */
    fun edge(p: Palette = palette): BorderStroke =
        if (!p.glass) {
            BorderStroke(1.dp, p.hairline)
        } else if (p.dark) {
            BorderStroke(
                1.dp,
                Brush.linearGradient(listOf(Color(0x8CFFFFFF), Color(0x14FFFFFF), Color(0x14FFFFFF), Color(0x47FFFFFF))),
            )
        } else {
            BorderStroke(
                1.dp,
                Brush.linearGradient(listOf(Color(0xF2FFFFFF), Color(0x80FFFFFF), Color(0x1A000000), Color(0xB3FFFFFF))),
            )
        }

    /** A soft shine across the top of a glass panel. */
    fun sheen(p: Palette = palette): Brush? =
        if (!p.glass) null
        else Brush.verticalGradient(
            listOf(Color.White.copy(alpha = if (p.dark) 0.10f else 0.45f), Color.Transparent),
        )

    /** Floating panels: solid in Classic and Material You, see-through in glass. */
    fun panel(alpha: Float, p: Palette = palette): Color = p.surface.copy(alpha = if (p.glass) p.panelAlpha else alpha)

    // ---------------------------------------------------------------- app windows

    private val blurListeners = HashMap<Activity, Consumer<Boolean>>()

    /**
     * Liquid glass in the app: your own wallpaper shows through, blurred, behind frosted cards.
     * Other looks: a plain background. (Blur depends on the phone; without it, the wallpaper is
     * tinted more strongly instead.)
     */
    fun styleWindow(activity: Activity) {
        val w = activity.window
        val p = palette
        if (p.glass) {
            w.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
            w.setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
            if (Build.VERSION.SDK_INT >= 31) {
                val radius = (48 * activity.resources.displayMetrics.density).toInt()
                w.setBackgroundBlurRadius(radius)
                if (activity !in blurListeners) {
                    val l = Consumer<Boolean> { on -> blurOn = on }
                    blurListeners[activity] = l
                    try {
                        activity.windowManager.addCrossWindowBlurEnabledListener(l)
                    } catch (_: Exception) {
                    }
                }
            }
        } else {
            w.clearFlags(WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER)
            w.setBackgroundDrawable(ColorDrawable(p.background.toArgb()))
            if (Build.VERSION.SDK_INT >= 31) w.setBackgroundBlurRadius(0)
        }
    }

    fun releaseWindow(activity: Activity) {
        val l = blurListeners.remove(activity) ?: return
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                activity.windowManager.removeCrossWindowBlurEnabledListener(l)
            } catch (_: Exception) {
            }
        }
    }

    /** The app's background: in glass, a tint over the wallpaper (stronger when there's no blur). */
    fun appBackground(p: Palette = palette): Color =
        if (!p.glass) p.background
        else if (blurOn) p.background
        else if (p.dark) Color(0xCC08080A) else Color(0xB3F6F6F8)
}
