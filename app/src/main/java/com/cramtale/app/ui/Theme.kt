package com.cramtale.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 藍 indigo (main), 朱 vermilion (the one accent: what to do now, what's wrong), 翠 jade (right, known),
 * 金 amber (a word you tapped). Cool paper by day, deep indigo at night.
 */
private val Light = lightColorScheme(
    primary = Color(0xFF2E3FA3), onPrimary = Color.White,
    primaryContainer = Color(0xFFE2E6FC), onPrimaryContainer = Color(0xFF111A5A),
    // Material's tonal parts (tonal buttons, selected segments) use secondary: a quiet slate, so the
    // vermilion accent (Accents) stays rare
    secondary = Color(0xFF515A7E), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE3E6F3), onSecondaryContainer = Color(0xFF1B2246),
    tertiary = Color(0xFF0D8574), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFCDF2EA), onTertiaryContainer = Color(0xFF00382F),
    background = Color(0xFFF3F4F8), onBackground = Color(0xFF141826),
    surface = Color(0xFFF3F4F8), onSurface = Color(0xFF141826),
    surfaceVariant = Color(0xFFE6E8F0), onSurfaceVariant = Color(0xFF575D72),
    surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFF9FAFC),
    surfaceContainer = Color(0xFFECEEF5), surfaceContainerHigh = Color(0xFFE6E8F0), surfaceContainerHighest = Color(0xFFDFE2EC),
    outline = Color(0xFFB9BED0), outlineVariant = Color(0xFFE1E4ED),
    error = Color(0xFFC0321F), onError = Color.White, errorContainer = Color(0xFFFFE2DB), onErrorContainer = Color(0xFF5A1206),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF9EABFF), onPrimary = Color(0xFF0E1650),
    primaryContainer = Color(0xFF283380), onPrimaryContainer = Color(0xFFDEE2FF),
    secondary = Color(0xFFB9C1E6), onSecondary = Color(0xFF232A4E),
    secondaryContainer = Color(0xFF2A3253), onSecondaryContainer = Color(0xFFDDE1F7),
    tertiary = Color(0xFF52D7BE), onTertiary = Color(0xFF00382F),
    tertiaryContainer = Color(0xFF00473C), onTertiaryContainer = Color(0xFFB9F5E8),
    background = Color(0xFF0C0F1E), onBackground = Color(0xFFE9EBF6),
    surface = Color(0xFF0C0F1E), onSurface = Color(0xFFE9EBF6),
    surfaceVariant = Color(0xFF232A44), onSurfaceVariant = Color(0xFFA6ADC6),
    surfaceContainerLowest = Color(0xFF141933), surfaceContainerLow = Color(0xFF12172C),
    surfaceContainer = Color(0xFF1A2039), surfaceContainerHigh = Color(0xFF212844), surfaceContainerHighest = Color(0xFF29314F),
    outline = Color(0xFF5B6383), outlineVariant = Color(0xFF262D48),
    error = Color(0xFFFF8A70), onError = Color(0xFF4A0D02), errorContainer = Color(0xFF63200F), onErrorContainer = Color(0xFFFFDAD1),
)

/** Colours outside Material's slots. */
data class Accents(
    /** A tapped word's background. */
    val tapped: Color,
    val onTapped: Color,
    /** The highlight on the home hero and the icon. */
    val amber: Color,
    /** The hero card's gradient. */
    val heroStart: Color,
    val heroEnd: Color,
    /** 朱 vermilion: the one accent (act here, what's wrong, a review word you missed). */
    val accent: Color,
    val onAccent: Color,
    val accentContainer: Color,
    val onAccentContainer: Color,
)

private val LightAccents = Accents(Color(0xFFFFE6A8), Color(0xFF3D2A00), Color(0xFFFFB52E), Color(0xFF3449B8), Color(0xFF1B2470),
    Color(0xFFD9412A), Color.White, Color(0xFFFFE2DB), Color(0xFF5A1206))
private val DarkAccents = Accents(Color(0xFF5A4300), Color(0xFFFFE6A8), Color(0xFFFFC861), Color(0xFF2B3A9E), Color(0xFF151C52),
    Color(0xFFFF7D5E), Color(0xFF4A0D02), Color(0xFF63200F), Color(0xFFFFDAD1))

// the accent reads like a theme colour: MaterialTheme.colorScheme.accent
val androidx.compose.material3.ColorScheme.accent: Color @Composable @androidx.compose.runtime.ReadOnlyComposable get() = LocalAccents.current.accent
val androidx.compose.material3.ColorScheme.onAccent: Color @Composable @androidx.compose.runtime.ReadOnlyComposable get() = LocalAccents.current.onAccent
val androidx.compose.material3.ColorScheme.accentContainer: Color @Composable @androidx.compose.runtime.ReadOnlyComposable get() = LocalAccents.current.accentContainer
val androidx.compose.material3.ColorScheme.onAccentContainer: Color @Composable @androidx.compose.runtime.ReadOnlyComposable get() = LocalAccents.current.onAccentContainer

val LocalAccents = androidx.compose.runtime.staticCompositionLocalOf { LightAccents }

private val Type = Typography().let { t ->
    t.copy(
        displaySmall = t.displaySmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
        headlineMedium = t.headlineMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp),
        headlineSmall = t.headlineSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.2).sp),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.Bold),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        bodyLarge = t.bodyLarge.copy(lineHeight = 26.sp),
        bodyMedium = t.bodyMedium.copy(lineHeight = 22.sp),
        labelLarge = t.labelLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.2.sp),
    )
}

/** The story text: serif (a Japanese Mincho face on Android), large, with room between lines for ruby-like glosses. */
val ReadingText = TextStyle(fontFamily = FontFamily.Serif, fontSize = 20.sp, lineHeight = 38.sp, letterSpacing = 0.5.sp)

/** Big single words (vocabulary check, word popup). */
val WordText = TextStyle(fontFamily = FontFamily.Serif, fontSize = 48.sp, lineHeight = 56.sp, fontWeight = FontWeight.Medium)

private val AppShapes = Shapes(
    small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(20.dp), large = RoundedCornerShape(28.dp),
)

@Composable
fun CramtaleTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    androidx.compose.runtime.CompositionLocalProvider(LocalAccents provides if (dark) DarkAccents else LightAccents) {
        MaterialTheme(colorScheme = if (dark) Dark else Light, typography = Type, shapes = AppShapes, content = content)
    }
}

/** The reader's text size setting (Settings → Reading), as a multiple of [ReadingText]. */
val LocalReadingScale = androidx.compose.runtime.staticCompositionLocalOf { 1f }

/** [ReadingText] at the reader's chosen size. */
@androidx.compose.runtime.Composable
fun readingStyle(): TextStyle {
    val k = LocalReadingScale.current
    return ReadingText.copy(fontSize = ReadingText.fontSize * k, lineHeight = ReadingText.lineHeight * k)
}
