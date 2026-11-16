package com.kaddie.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import com.kaddie.app.data.settings.AppSettings
import com.kaddie.app.data.settings.ThemeMode

private val LightColors = lightColorScheme(
    primary = Color(0xFF1B6B4A),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFA6F2C8),
    onPrimaryContainer = Color(0xFF002114),
    secondary = Color(0xFF4D6357),
    secondaryContainer = Color(0xFFCFE9D9),
    onSecondaryContainer = Color(0xFF0A1F16),
    tertiary = Color(0xFF3D6373),
    tertiaryContainer = Color(0xFFC1E8FB),
    background = Color(0xFFF5FBF6),
    surface = Color(0xFFF5FBF6),
    surfaceVariant = Color(0xFFDBE5DD),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8BD6AD),
    onPrimary = Color(0xFF003824),
    primaryContainer = Color(0xFF005137),
    onPrimaryContainer = Color(0xFFA6F2C8),
    secondary = Color(0xFFB3CCBD),
    secondaryContainer = Color(0xFF354B3F),
    onSecondaryContainer = Color(0xFFCFE9D9),
    tertiary = Color(0xFFA5CCDF),
    tertiaryContainer = Color(0xFF244C5B),
    background = Color(0xFF0F1512),
    surface = Color(0xFF0F1512),
    surfaceVariant = Color(0xFF404943),
)

private val KaddieShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
)

@Composable
fun KaddieTheme(settings: AppSettings, content: @Composable () -> Unit) {
    val dark = when (settings.themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val context = LocalContext.current
    val colors = when {
        settings.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, shapes = KaddieShapes, content = content)
}
