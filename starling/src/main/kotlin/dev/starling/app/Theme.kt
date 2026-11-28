package dev.starling.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// ---------------------------------------------------------------------------
// Doge Client color palette
// ---------------------------------------------------------------------------
val DogePrimary = Color(0xFFF4A460)   // Gold / yellow
val DogeSecondary = Color(0xFF8B4513) // Dark brown
val DogeTertiary = Color(0xFFE8E0D0) // Off-white (text)
val DogeBackground = Color(0xFF121212) // Dark mode default
val DogeSurface = Color(0xFF1E1E1E)

// ---------------------------------------------------------------------------
// ColorScheme Material 3 (Dark)
// ---------------------------------------------------------------------------
private val DogeDarkColorScheme = darkColorScheme(
    primary = DogePrimary,
    secondary = DogeSecondary,
    tertiary = DogeTertiary,
    background = DogeBackground,
    surface = DogeSurface,
    onPrimary = Color.BLACK,
    onSecondary = Color.WHITE,
    onBackground = DogeTertiary,
    onSurface = DogeTertiary,
)

// ---------------------------------------------------------------------------
// Tipografia moderna e arrotondata
// ---------------------------------------------------------------------------
private val DogeTypography = Typography(
    displayLarge = TextStyle(
        fontSize = 34.sp,
        fontWeight = FontWeight.ExtraBold,
        letterSpacing = 0.8.sp
    ),
    headlineLarge = TextStyle(
        fontSize = 28.sp,
        fontWeight = FontWeight.Bold
    ),
    titleLarge = TextStyle(
        fontSize = 20.sp,
        fontWeight = FontWeight.Medium
    ),
    bodyLarge = TextStyle(
        fontSize = 16.sp
    ),
    labelLarge = TextStyle(
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium
    ),
)

// ---------------------------------------------------------------------------
// Tema Compose Doge Client
// ---------------------------------------------------------------------------
@Composable
fun DogeTheme(content: @Composable () -> Unit) {
    val colorScheme = if (isSystemInDarkTheme()) {
        DogeDarkColorScheme
    } else {
        DogeDarkColorScheme.copy(
            background = Color.White,
            surface = Color(0xFFF5F5F5),
            onBackground = Color(0xFF121212),
            onSurface = Color(0xFF121212),
        )
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = DogeTypography,
        content = content
    )
}
