package com.nextnotif.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val LightColors = lightColorScheme(
    primary = Color(0xFF4F46E5),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE0E7FF),
    onPrimaryContainer = Color(0xFF1E1B4B),
    secondary = Color(0xFF0EA5E9),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE0F2FE),
    onSecondaryContainer = Color(0xFF0C4A6E),
    tertiary = Color(0xFFF97316),
    tertiaryContainer = Color(0xFFFFEDD5),
    background = Color(0xFFFAFAFF),
    onBackground = Color(0xFF1A1C2E),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1A1C2E),
    surfaceVariant = Color(0xFFF1F2F9),
    onSurfaceVariant = Color(0xFF494E63),
    outline = Color(0xFFC4C6D6),
    error = Color(0xFFDC2626),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF7F1D1D),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA5B4FC),
    onPrimary = Color(0xFF1E1B4B),
    primaryContainer = Color(0xFF312E81),
    onPrimaryContainer = Color(0xFFE0E7FF),
    secondary = Color(0xFF7DD3FC),
    onSecondary = Color(0xFF082F49),
    secondaryContainer = Color(0xFF0C4A6E),
    onSecondaryContainer = Color(0xFFE0F2FE),
    tertiary = Color(0xFFFDBA74),
    tertiaryContainer = Color(0xFF7C2D12),
    background = Color(0xFF0F1117),
    onBackground = Color(0xFFE5E7F0),
    surface = Color(0xFF181B25),
    onSurface = Color(0xFFE5E7F0),
    surfaceVariant = Color(0xFF1E2230),
    onSurfaceVariant = Color(0xFFA3A8C0),
    outline = Color(0xFF494E63),
    error = Color(0xFFF87171),
    onError = Color(0xFF450A0A),
    errorContainer = Color(0xFF7F1D1D),
    onErrorContainer = Color(0xFFFEE2E2),
)

private val AppShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

private val BaseTypography = Typography()

private val AppTypography = BaseTypography.copy(
    headlineMedium = BaseTypography.headlineMedium.copy(fontSize = 28.sp),
    titleLarge = BaseTypography.titleLarge.copy(fontSize = 22.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = BaseTypography.titleMedium.copy(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = BaseTypography.titleSmall.copy(fontSize = 14.sp, fontWeight = FontWeight.Medium),
    bodyLarge = BaseTypography.bodyLarge.copy(fontSize = 16.sp),
    bodyMedium = BaseTypography.bodyMedium.copy(fontSize = 14.sp),
    bodySmall = BaseTypography.bodySmall.copy(fontSize = 12.sp),
    labelLarge = BaseTypography.labelLarge.copy(fontSize = 14.sp, fontWeight = FontWeight.Medium),
    labelMedium = BaseTypography.labelMedium.copy(fontSize = 12.sp, fontWeight = FontWeight.Medium),
    labelSmall = BaseTypography.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.Medium),
)

// Relay-on hero card gradient.
object Brand {
    val heroGradient: List<Color> = listOf(Color(0xFF4F46E5), Color(0xFF7C3AED), Color(0xFF0EA5E9))
}

@Composable
fun NextNotifTheme(content: @Composable () -> Unit) {
    val darkTheme = isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        shapes = AppShapes,
        typography = AppTypography,
        content = content,
    )
}
