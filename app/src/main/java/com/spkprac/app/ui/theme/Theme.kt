package com.spkprac.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.spkprac.app.data.CaptionSize
import com.spkprac.app.data.ThemeMode

/* ------------------------------------------------------------------ palette */

private val Teal = Color(0xFF0B7A6C)
private val TealDark = Color(0xFF6EE7D7)
private val Aqua = Color(0xFF3FC1C9)

private val LightColors = lightColorScheme(
    primary = Teal,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFA7F3E4),
    onPrimaryContainer = Color(0xFF002A24),
    inversePrimary = TealDark,
    secondary = Color(0xFF45655F),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCBE9E1),
    onSecondaryContainer = Color(0xFF06201C),
    tertiary = Color(0xFF9A5B00),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFDDB3),
    onTertiaryContainer = Color(0xFF2B1700),
    background = Color(0xFFF5FBF9),
    onBackground = Color(0xFF0B1412),
    surface = Color(0xFFFBFFFD),
    onSurface = Color(0xFF0B1412),
    surfaceVariant = Color(0xFFDCEAE5),
    onSurfaceVariant = Color(0xFF3F544F),
    surfaceTint = Teal,
    outline = Color(0xFF6F847F),
    outlineVariant = Color(0xFFC0D4CE),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002)
)

private val DarkColors = darkColorScheme(
    primary = TealDark,
    onPrimary = Color(0xFF003731),
    primaryContainer = Color(0xFF00574B),
    onPrimaryContainer = Color(0xFF9CF3E4),
    inversePrimary = Teal,
    secondary = Color(0xFFB1CCC6),
    onSecondary = Color(0xFF1C3531),
    secondaryContainer = Color(0xFF2C4844),
    onSecondaryContainer = Color(0xFFCDE8E2),
    tertiary = Color(0xFFFFB95C),
    onTertiary = Color(0xFF4A2800),
    tertiaryContainer = Color(0xFF5C3A00),
    onTertiaryContainer = Color(0xFFFFDDB3),
    background = Color(0xFF0B1412),
    onBackground = Color(0xFFDDE5E2),
    surface = Color(0xFF0F1A18),
    onSurface = Color(0xFFDDE5E2),
    surfaceVariant = Color(0xFF24322F),
    onSurfaceVariant = Color(0xFFBFD4CE),
    surfaceTint = TealDark,
    outline = Color(0xFF8AA09B),
    outlineVariant = Color(0xFF3A4B47),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6)
)

private val SpkShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp)
)

private val SpkTypography = Typography().let { base ->
    base.copy(
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.2).sp),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.1).sp),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        titleSmall = base.titleSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.2.sp),
        labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold)
    )
}

/* --------------------------------------------------------------- brand kit */

/** Fixed brand colours + gradient stops shared by every custom-drawn surface. */
object Brand {
    val Teal = Color(0xFF0B7A6C)
    val Mint = Color(0xFF6EE7D7)
    val Aqua = Color(0xFF3FC1C9)
    val Sky = Color(0xFF4EA1F0)
    val Amber = Color(0xFFFFB74D)
    val Coral = Color(0xFFF97066)
    val Violet = Color(0xFF8B7BF0)

    /** Big soft blobs painted behind the app content. */
    fun backdrop(dark: Boolean): List<Color> = if (dark) listOf(
        Color(0xFF0E3B36), Color(0xFF0B2A33), Color(0xFF16202B)
    ) else listOf(
        Color(0xFFE4FAF4), Color(0xFFE8F3FF), Color(0xFFF5FBF9)
    )

    /** Highlight gradient for cards, buttons and the voice orb. */
    fun accent(dark: Boolean): List<Color> = if (dark) listOf(
        Color(0xFF6EE7D7), Color(0xFF5AC8FA)
    ) else listOf(
        Color(0xFF0B7A6C), Color(0xFF3FC1C9)
    )

    fun warm(dark: Boolean): List<Color> = if (dark) listOf(
        Color(0xFFFFC46B), Color(0xFFFF9A6B)
    ) else listOf(
        Color(0xFFF0A020), Color(0xFFE8703A)
    )
}

/* ------------------------------------------------------- runtime ui prefs */

/** Cheap way for deeply nested composables to know about animation/haptics/caption prefs. */
data class UiPrefs(
    val animations: Boolean = true,
    val haptics: Boolean = true,
    val captionSize: CaptionSize = CaptionSize.MEDIUM
)

val LocalUiPrefs = staticCompositionLocalOf { UiPrefs() }

/** True when the currently applied colour scheme is dark. Use it to pick gradient stops. */
@Composable
fun isDarkScheme(): Boolean = MaterialTheme.colorScheme.background.luminance() < 0.5f

private val CaptionSmall = TextStyle(fontSize = 15.sp, lineHeight = 21.sp)
private val CaptionMedium = TextStyle(fontSize = 17.sp, lineHeight = 24.sp)
private val CaptionLarge = TextStyle(fontSize = 20.sp, lineHeight = 28.sp)

/** Base text style for conversation captions, honouring the caption-size preference. */
@Composable
fun captionTextStyle(): TextStyle = when (LocalUiPrefs.current.captionSize) {
    CaptionSize.SMALL -> CaptionSmall
    CaptionSize.MEDIUM -> CaptionMedium
    CaptionSize.LARGE -> CaptionLarge
}

/* ------------------------------------------------------------------ theme */

@Composable
fun SpkPracTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    uiPrefs: UiPrefs = UiPrefs(),
    content: @Composable () -> Unit
) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    CompositionLocalProvider(LocalUiPrefs provides uiPrefs) {
        MaterialTheme(
            colorScheme = if (dark) DarkColors else LightColors,
            typography = SpkTypography,
            shapes = SpkShapes,
            content = content
        )
    }
}
