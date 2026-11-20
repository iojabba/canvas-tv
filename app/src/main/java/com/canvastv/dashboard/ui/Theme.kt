package com.canvastv.dashboard.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.tv.material3.Typography
import com.canvastv.dashboard.model.FontChoice
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.canvastv.dashboard.model.ThemeMode
import com.canvastv.dashboard.model.ThemeSpec
import kotlinx.coroutines.delay
import java.util.Calendar

@Immutable
data class Palette(
    val dark: Boolean,
    val surface: Color,
    val onSurface: Color,
    val muted: Color,
    val accent: Color,
    val scrim: Color,
    /** Accent for text and thin lines: darkened on light backgrounds so it stays readable. */
    val accentText: Color = accent,
    val highContrast: Boolean = false,
)

val LocalPalette = staticCompositionLocalOf { paletteFor(ThemeSpec(), dark = true, amoled = false) }

fun parseColor(hex: String?, fallback: Color = Color.Gray): Color = runCatching {
    Color(android.graphics.Color.parseColor(hex))
}.getOrDefault(fallback)

fun Color.toHex(): String = String.format("#%08X", android.graphics.Color.argb(alpha, red, green, blue))

fun paletteFor(t: ThemeSpec, dark: Boolean, amoled: Boolean): Palette {
    val accent = parseColor(t.accentHex, Color(0xFFFFC857))
    // Pale accents (yellow, light blue) are hard to read as text on a light background.
    val lightText = if (accent.luminance() > 0.35f) lerp(accent, Color.Black, 0.55f) else accent
    return when {
        t.highContrast && dark -> Palette(true, Color.Black, Color.White, Color(0xFFE6E6E6), accent, Color.Black, accent, true)
        t.highContrast -> Palette(false, Color.White, Color.Black, Color(0xFF1A1A1A), accent, Color.White, lerp(accent, Color.Black, 0.7f), true)
        amoled -> Palette(true, Color(0xFF000000), Color(0xFFF2F4F7), Color(0xFF9AA4B2), accent, Color.Black)
        dark -> Palette(true, Color(0xFF111A24), Color(0xFFF2F4F7), Color(0xFFA9B4C2), accent, Color(0xFF05080C))
        else -> Palette(false, Color(0xFFF4F6F9), Color(0xFF111821), Color(0xFF4F5B69), accent, Color(0xFFDDE3EA), lightText)
    }
}

fun fontFamilyFor(f: FontChoice): FontFamily = when (f) {
    FontChoice.SANS -> FontFamily.SansSerif
    FontChoice.CONDENSED -> FontFamily(android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.NORMAL))
    FontChoice.SERIF -> FontFamily.Serif
    FontChoice.MONO -> FontFamily.Monospace
}

/** The default TV typography with every style switched to [family]. */
private fun typographyFor(family: FontFamily): Typography {
    val b = Typography()
    fun TextStyle.f() = copy(fontFamily = family)
    return Typography(
        displayLarge = b.displayLarge.f(), displayMedium = b.displayMedium.f(), displaySmall = b.displaySmall.f(),
        headlineLarge = b.headlineLarge.f(), headlineMedium = b.headlineMedium.f(), headlineSmall = b.headlineSmall.f(),
        titleLarge = b.titleLarge.f(), titleMedium = b.titleMedium.f(), titleSmall = b.titleSmall.f(),
        bodyLarge = b.bodyLarge.f(), bodyMedium = b.bodyMedium.f(), bodySmall = b.bodySmall.f(),
        labelLarge = b.labelLarge.f(), labelMedium = b.labelMedium.f(), labelSmall = b.labelSmall.f(),
    )
}

/** Applies the dashboard theme. AUTO switches to dark between 7 PM and 7 AM. */
@Composable
fun DashboardTheme(spec: ThemeSpec, content: @Composable () -> Unit) {
    var hour by remember { mutableIntStateOf(Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) }
    if (spec.mode == ThemeMode.AUTO) {
        LaunchedEffect(Unit) {
            while (true) {
                delay(60_000)
                hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            }
        }
    }
    val dark = when (spec.mode) {
        ThemeMode.DARK, ThemeMode.AMOLED -> true
        ThemeMode.LIGHT -> false
        ThemeMode.AUTO -> hour >= 19 || hour < 7
    }
    val palette = paletteFor(spec, dark, spec.mode == ThemeMode.AMOLED)
    val density = LocalDensity.current
    CompositionLocalProvider(
        LocalPalette provides palette,
        LocalDensity provides Density(density.density, density.fontScale * spec.fontScale),
    ) {
        androidx.tv.material3.MaterialTheme(
            colorScheme = if (dark) androidx.tv.material3.darkColorScheme(primary = palette.accent)
            else androidx.tv.material3.lightColorScheme(primary = palette.accent),
            typography = typographyFor(fontFamilyFor(spec.font)),
            content = content,
        )
    }
}

val AccentChoices = listOf("#FFC857", "#4FC3F7", "#81C784", "#F06292", "#BA68C8", "#FF8A65", "#E0E0E0")

val BackgroundPresets: List<Triple<String, String, String>> = listOf(
    Triple("Deep sea", "#0F2027", "#2C5364"),
    Triple("Dusk", "#1F1C2C", "#928DAB"),
    Triple("Forest", "#0B3D2E", "#4B7F52"),
    Triple("Ember", "#2B1A12", "#8E3B2F"),
    Triple("Slate", "#1C2833", "#5D6D7E"),
    Triple("Midnight", "#000000", "#1A1A2E"),
)
