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

/** 藍 indigo, 朱 vermilion, 若竹 green on paper; ink-dark at night (the original palette). */
private val Light = lightColorScheme(
    primary = Color(0xFF2B4570), onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE4F2), onPrimaryContainer = Color(0xFF0F2445),
    secondary = Color(0xFFB5482E), onSecondary = Color.White,
    secondaryContainer = Color(0xFFF6DED5), onSecondaryContainer = Color(0xFF4A1508),
    tertiary = Color(0xFF4F7A5A), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFD9EBDC), onTertiaryContainer = Color(0xFF13301B),
    background = Color(0xFFF6F2EA), onBackground = Color(0xFF1F1D1A),
    surface = Color(0xFFFBF8F2), onSurface = Color(0xFF1F1D1A),
    surfaceVariant = Color(0xFFECE5D8), onSurfaceVariant = Color(0xFF5E574D),
    surfaceContainerLowest = Color(0xFFFFFDF9), surfaceContainerLow = Color(0xFFF8F4EC),
    surfaceContainer = Color(0xFFF2EDE3), surfaceContainerHigh = Color(0xFFEDE7DB), surfaceContainerHighest = Color(0xFFE7E0D3),
    outline = Color(0xFFBDB3A3), outlineVariant = Color(0xFFDDD5C7),
    error = Color(0xFFA8322A), onError = Color.White, errorContainer = Color(0xFFF8DAD5), onErrorContainer = Color(0xFF410E0A),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFAFC6EE), onPrimary = Color(0xFF0F2445),
    primaryContainer = Color(0xFF253B60), onPrimaryContainer = Color(0xFFDCE4F2),
    secondary = Color(0xFFF2A88F), onSecondary = Color(0xFF4A1508),
    secondaryContainer = Color(0xFF6B2A19), onSecondaryContainer = Color(0xFFF6DED5),
    tertiary = Color(0xFFA6D1AF), onTertiary = Color(0xFF13301B),
    tertiaryContainer = Color(0xFF2F4C37), onTertiaryContainer = Color(0xFFD9EBDC),
    background = Color(0xFF15171B), onBackground = Color(0xFFE9E4DA),
    surface = Color(0xFF1A1D22), onSurface = Color(0xFFE9E4DA),
    surfaceVariant = Color(0xFF2A2E35), onSurfaceVariant = Color(0xFFBDB6AA),
    surfaceContainerLowest = Color(0xFF111316), surfaceContainerLow = Color(0xFF1C1F24),
    surfaceContainer = Color(0xFF20242A), surfaceContainerHigh = Color(0xFF272B32), surfaceContainerHighest = Color(0xFF30353D),
    outline = Color(0xFF6E685E), outlineVariant = Color(0xFF3A3F47),
    error = Color(0xFFF0A49A), onError = Color(0xFF410E0A), errorContainer = Color(0xFF7A2A22), onErrorContainer = Color(0xFFF8DAD5),
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

private val LightAccents = Accents(Color(0xFFF6DED5), Color(0xFF4A1508), Color(0xFFE2A93B), Color(0xFF34548A), Color(0xFF1E3356),
    Color(0xFFB5482E), Color.White, Color(0xFFF6DED5), Color(0xFF4A1508))
private val DarkAccents = Accents(Color(0xFF6B2A19), Color(0xFFF6DED5), Color(0xFFE8B865), Color(0xFF2E4A78), Color(0xFF1A2940),
    Color(0xFFF2A88F), Color(0xFF4A1508), Color(0xFF6B2A19), Color(0xFFF6DED5))

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
fun CramtaleTheme(dark: Boolean = isSystemInDarkTheme(), materialYou: Boolean = false, content: @Composable () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    // Material You: the phone's wallpaper palette (Android 12+); otherwise Cramtale's own
    val dynamic = materialYou && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S
    val scheme = when {
        dynamic && dark -> androidx.compose.material3.dynamicDarkColorScheme(context)
        dynamic -> androidx.compose.material3.dynamicLightColorScheme(context)
        dark -> Dark
        else -> Light
    }
    val accents = if (dynamic) accentsFrom(scheme) else if (dark) DarkAccents else LightAccents
    androidx.compose.runtime.CompositionLocalProvider(LocalAccents provides accents) {
        MaterialTheme(colorScheme = scheme, typography = Type, shapes = AppShapes, content = content)
    }
}

/** With Material You, the extra colours come from the wallpaper scheme too (tertiary for taps, error tones for the accent). */
private fun accentsFrom(s: androidx.compose.material3.ColorScheme) = Accents(
    tapped = s.tertiaryContainer, onTapped = s.onTertiaryContainer, amber = s.tertiary,
    heroStart = s.primary, heroEnd = s.primary.copy(alpha = 1f).let { androidx.compose.ui.graphics.lerp(it, Color.Black, 0.35f) },
    accent = s.error, onAccent = s.onError, accentContainer = s.errorContainer, onAccentContainer = s.onErrorContainer,
)

/** The reader's text size setting (Settings → Reading), as a multiple of [ReadingText]. */
val LocalReadingScale = androidx.compose.runtime.staticCompositionLocalOf { 1f }

/** [ReadingText] at the reader's chosen size. */
@androidx.compose.runtime.Composable
fun readingStyle(): TextStyle {
    val k = LocalReadingScale.current
    return ReadingText.copy(fontSize = ReadingText.fontSize * k, lineHeight = ReadingText.lineHeight * k)
}
