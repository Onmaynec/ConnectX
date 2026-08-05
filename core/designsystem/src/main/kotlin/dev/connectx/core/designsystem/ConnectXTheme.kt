package dev.connectx.core.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val ConnectXPurple = Color(0xFF8B5CF6)
private val ConnectXPurpleStrong = Color(0xFF7C3AED)
private val ConnectXPurpleSoft = Color(0xFF2A1748)
private val ConnectXBackground = Color(0xFF07090D)
private val ConnectXSurface = Color(0xFF0E1117)
private val ConnectXSurfaceRaised = Color(0xFF131720)
private val ConnectXBorder = Color(0xFF242936)
private val ConnectXBorderSoft = Color(0xFF191D26)
private val ConnectXText = Color(0xFFF7F7FA)
private val ConnectXTextMuted = Color(0xFF9298A7)
private val ConnectXGreen = Color(0xFF35D07F)
private val ConnectXRed = Color(0xFFFF5B64)

private val DarkColors = darkColorScheme(
    primary = ConnectXPurple,
    onPrimary = Color.White,
    primaryContainer = ConnectXPurpleSoft,
    onPrimaryContainer = ConnectXText,
    secondary = ConnectXPurpleStrong,
    onSecondary = Color.White,
    background = ConnectXBackground,
    onBackground = ConnectXText,
    surface = ConnectXSurface,
    onSurface = ConnectXText,
    surfaceVariant = ConnectXSurfaceRaised,
    onSurfaceVariant = ConnectXTextMuted,
    outline = ConnectXBorder,
    outlineVariant = ConnectXBorderSoft,
    tertiary = ConnectXGreen,
    error = ConnectXRed,
)

private val LightColors = lightColorScheme(
    primary = ConnectXPurpleStrong,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFEDE5FF),
    onPrimaryContainer = Color(0xFF24113E),
    background = Color(0xFFF7F7FA),
    onBackground = Color(0xFF14161B),
    surface = Color.White,
    onSurface = Color(0xFF14161B),
    surfaceVariant = Color(0xFFF0F1F5),
    onSurfaceVariant = Color(0xFF5E6470),
    outline = Color(0xFFD5D8E0),
    error = ConnectXRed,
)

private val ConnectXTypography = Typography(
    headlineLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 30.sp,
        lineHeight = 36.sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
        lineHeight = 30.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 17.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Medium,
        fontSize = 10.sp,
        lineHeight = 14.sp,
    ),
)

enum class ConnectXThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
}

@Composable
fun ConnectXTheme(
    mode: ConnectXThemeMode = ConnectXThemeMode.DARK,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (mode) {
        ConnectXThemeMode.SYSTEM -> isSystemInDarkTheme()
        ConnectXThemeMode.LIGHT -> false
        ConnectXThemeMode.DARK -> true
    }

    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = ConnectXTypography,
        content = content,
    )
}
