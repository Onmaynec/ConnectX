package dev.connectx.core.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val DarkColors = darkColorScheme()
private val LightColors = lightColorScheme()

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
        typography = MaterialTheme.typography,
        content = content,
    )
}
