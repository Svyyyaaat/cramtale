package com.cramtale.app.ui

import androidx.compose.ui.graphics.luminance

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

/** Dusk: aubergine, peach and moss on blush paper; plum-dark at night. */
private val Light = lightColorScheme(
    primary = Color(0xFF6B3F63), onPrimary = Color.White,
    primaryContainer = Color(0xFFF2DDEC), onPrimaryContainer = Color(0xFF2C0F27),
    // Material's tonal parts (tonal buttons, selected segments): a quiet mauve
    secondary = Color(0xFF6F5A69), onSecondary = Color.White,
    secondaryContainer = Color(0xFFEEDFE9), onSecondaryContainer = Color(0xFF281A24),
    tertiary = Color(0xFF5E7F5A), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFDCE9D6), onTertiaryContainer = Color(0xFF172B14),
    background = Color(0xFFF6F0EE), onBackground = Color(0xFF261D24),
    surface = Color(0xFFFBF7F5), onSurface = Color(0xFF261D24),
    surfaceVariant = Color(0xFFEDE3E3), onSurfaceVariant = Color(0xFF615259),
    surfaceContainerLowest = Color(0xFFFFFCFB), surfaceContainerLow = Color(0xFFF9F3F1),
    surfaceContainer = Color(0xFFF2EAE8), surfaceContainerHigh = Color(0xFFEDE4E2), surfaceContainerHighest = Color(0xFFE7DDDB),
    outline = Color(0xFFB7A7AD), outlineVariant = Color(0xFFE3D7D8),
    error = Color(0xFFB3261E), onError = Color.White, errorContainer = Color(0xFFF9DEDC), onErrorContainer = Color(0xFF410E0B),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFE5B4D9), onPrimary = Color(0xFF46193F),
    primaryContainer = Color(0xFF5E3157), onPrimaryContainer = Color(0xFFFFD7F3),
    secondary = Color(0xFFD9C0D1), onSecondary = Color(0xFF3E2C39),
    secondaryContainer = Color(0xFF56424F), onSecondaryContainer = Color(0xFFF6DCEE),
    tertiary = Color(0xFFB8CFAE), onTertiary = Color(0xFF243620),
    tertiaryContainer = Color(0xFF3A4E35), onTertiaryContainer = Color(0xFFD4EBC9),
    background = Color(0xFF1B1519), onBackground = Color(0xFFEDE0E5),
    surface = Color(0xFF211A1F), onSurface = Color(0xFFEDE0E5),
    surfaceVariant = Color(0xFF4D434A), onSurfaceVariant = Color(0xFFD0C2C9),
    surfaceContainerLowest = Color(0xFF161115), surfaceContainerLow = Color(0xFF241D22),
    surfaceContainer = Color(0xFF2A2228), surfaceContainerHigh = Color(0xFF342C32), surfaceContainerHighest = Color(0xFF3F363D),
    outline = Color(0xFF998D95), outlineVariant = Color(0xFF4D434A),
    error = Color(0xFFF2B8B5), onError = Color(0xFF601410), errorContainer = Color(0xFF8C1D18), onErrorContainer = Color(0xFFF9DEDC),
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
    /** Text on the hero card, and its button. */
    val onHero: Color = Color.White,
    val heroButton: Color = Color.White,
    val onHeroButton: Color = Color(0xFF4A2846),
)

// Dusk: aubergine (tapped words in its soft tint), a peach accent used sparingly, moss for right/known
private val LightAccents = Accents(Color(0xFFE8C2DD), Color(0xFF2C0F27), Color(0xFFF0A47A), Color(0xFF7A4772), Color(0xFF4A2846),
    Color(0xFFB8613A), Color.White, Color(0xFFFBDCCB), Color(0xFF43200C))
private val DarkAccents = Accents(Color(0xFF5E3157), Color(0xFFFFD7F3), Color(0xFFF0A47A), Color(0xFF5E3157), Color(0xFF2E1A2B),
    Color(0xFFF4B08A), Color(0xFF4A1E05), Color(0xFF6A3A22), Color(0xFFFFDCC8))

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

/** True when the colours come from the wallpaper (Material You): the app's mark follows them. */
val LocalDynamicColor = androidx.compose.runtime.staticCompositionLocalOf { false }

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
    androidx.compose.runtime.CompositionLocalProvider(LocalAccents provides accents, LocalDynamicColor provides dynamic) {
        MaterialTheme(colorScheme = scheme, typography = Type, shapes = AppShapes, content = content)
    }
}

/** With Material You, the extra colours come from the wallpaper scheme too (secondary for taps, tertiary for the accent). */
private fun accentsFrom(s: androidx.compose.material3.ColorScheme) = Accents(
    tapped = s.primary.copy(alpha = if (s.background.luminance() < 0.5f) 0.38f else 0.22f), onTapped = s.onPrimaryContainer, amber = s.primaryContainer,
    // the wallpaper's own soft tones (a darkened primary turns muddy grey with pale wallpapers)
    heroStart = s.primaryContainer, heroEnd = s.tertiaryContainer,
    onHero = s.onPrimaryContainer, heroButton = s.primary, onHeroButton = s.onPrimary,
    accent = s.tertiary, onAccent = s.onTertiary, accentContainer = s.tertiaryContainer, onAccentContainer = s.onTertiaryContainer,
)

/** The reader's text size setting (Settings → Reading), as a multiple of [ReadingText]. */
val LocalReadingScale = androidx.compose.runtime.staticCompositionLocalOf { 1f }

/** [ReadingText] at the reader's chosen size. */
@androidx.compose.runtime.Composable
fun readingStyle(): TextStyle {
    val k = LocalReadingScale.current
    return ReadingText.copy(fontSize = ReadingText.fontSize * k, lineHeight = ReadingText.lineHeight * k)
}
