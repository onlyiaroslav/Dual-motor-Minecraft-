package dev.starling.app

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// ---------------------------------------------------------------------------
// Palette ufficiale Doge Client
//   Oro Dogecoin #C2A633 / accento #E1B303
//   Antracite profondo #121214 (background) / #1C1C20 (surface)
//   Testo ad alto contrasto #F5F5F5
// ---------------------------------------------------------------------------
val DogePrimary = Color(0xFFC2A633)        // oro Dogecoin
val DogeAccent = Color(0xFFE1B303)         // accento acceso
val DogePrimaryDeep = Color(0xFF8F7A22)    // oro scuro (gradienti/bordi)
val DogeSecondary = Color(0xFF8B6F1F)      // ambra secondaria
val DogeTertiary = Color(0xFFF5F5F5)       // testo chiaro (compat)
val DogeBackground = Color(0xFF121214)     // antracite profondo
val DogeBackgroundAlt = Color(0xFF17171A)
val DogeSurface = Color(0xFF1C1C20)        // card
val DogeSurfaceHigh = Color(0xFF26262C)    // card elevata / dialoghi
val DogeOutline = Color(0x1FFFFFFF)        // bordo sottile
val DogeTextPrimary = Color(0xFFF5F5F5)
val DogeTextSecondary = Color(0xFFA8A8B3)
val DogeSuccess = Color(0xFF4ADE80)
val DogeDanger = Color(0xFFFF5C5C)

// ---------------------------------------------------------------------------
// ColorScheme Material 3 — sempre dark: il brand non ha variante chiara.
// ---------------------------------------------------------------------------
private val DogeDarkColorScheme = darkColorScheme(
    primary = DogePrimary,
    onPrimary = Color(0xFF1A1608),
    primaryContainer = Color(0xFF3A3113),
    onPrimaryContainer = DogeAccent,
    secondary = DogeSecondary,
    onSecondary = Color(0xFFF5F5F5),
    secondaryContainer = Color(0xFF2E2A18),
    onSecondaryContainer = DogeAccent,
    tertiary = DogeAccent,
    onTertiary = Color(0xFF1A1608),
    background = DogeBackground,
    onBackground = DogeTextPrimary,
    surface = DogeSurface,
    onSurface = DogeTextPrimary,
    surfaceVariant = DogeSurfaceHigh,
    onSurfaceVariant = DogeTextSecondary,
    outline = DogeOutline,
    outlineVariant = Color(0x14FFFFFF),
    error = DogeDanger,
    onError = Color(0xFF2A0808),
    scrim = Color(0xCC000000),
)

// ---------------------------------------------------------------------------
// Tipografia Material 3
// ---------------------------------------------------------------------------
private val DogeTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 30.sp,
        fontWeight = FontWeight.ExtraBold,
        letterSpacing = (-0.4).sp,
    ),
    headlineLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 26.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.2).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 22.sp,
        fontWeight = FontWeight.Bold,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 18.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 15.sp,
        fontWeight = FontWeight.SemiBold,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 15.sp,
        lineHeight = 21.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.6.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 10.sp,
        fontWeight = FontWeight.Medium,
        letterSpacing = 1.2.sp,
    ),
)

private val DogeShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(26.dp),
)

@Composable
fun DogeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DogeDarkColorScheme,
        typography = DogeTypography,
        shapes = DogeShapes,
        content = content,
    )
}
