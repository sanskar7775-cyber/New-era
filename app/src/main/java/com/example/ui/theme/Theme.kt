package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.shape.RoundedCornerShape
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

private val NewEraLightColorScheme = lightColorScheme(
    primary = VioletLightPrimary,
    onPrimary = Color.White,
    primaryContainer = VioletLightPrimaryContainer,
    onPrimaryContainer = Color(0xFF2E1065),
    secondary = VioletLightSecondary,
    onSecondary = Color.White,
    secondaryContainer = VioletLightSecondaryContainer,
    onSecondaryContainer = VioletLightTextPrimary,
    tertiary = VioletLightPrimary,
    onTertiary = Color.White,
    background = VioletLightBackground,
    onBackground = VioletLightTextPrimary,
    surface = VioletLightSurface,
    onSurface = VioletLightTextPrimary,
    surfaceVariant = VioletLightSurfaceVariant,
    onSurfaceVariant = VioletLightTextSecondary,
    outline = VioletLightOutline,
    outlineVariant = VioletLightOutline.copy(alpha = 0.65f),
    error = NewEraError,
    onError = Color.White
)

private val NewEraDarkColorScheme = darkColorScheme(
    primary = VioletDarkPrimary,
    onPrimary = Color(0xFF140A2E),
    primaryContainer = VioletDarkPrimaryContainer,
    onPrimaryContainer = VioletDarkOnPrimaryContainer,
    secondary = VioletDarkSecondary,
    onSecondary = Color.White,
    secondaryContainer = VioletDarkSecondaryContainer,
    onSecondaryContainer = VioletDarkTextPrimary,
    tertiary = Color(0xFFC4B5FD),
    onTertiary = Color(0xFF140A2E),
    tertiaryContainer = Color(0xFF2A1F4D),
    onTertiaryContainer = VioletDarkOnPrimaryContainer,
    background = VioletDarkBackground,
    onBackground = VioletDarkTextPrimary,
    surface = VioletDarkSurface,
    onSurface = VioletDarkTextPrimary,
    surfaceVariant = VioletDarkSurfaceVariant,
    onSurfaceVariant = VioletDarkTextSecondary,
    outline = VioletDarkOutline,
    outlineVariant = VioletDarkOutline.copy(alpha = 0.7f),
    error = NewEraDarkError,
    onError = Color(0xFF450A0A)
)

val NewEraShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

@Composable
fun NewEraTheme(
    darkTheme: Boolean = true,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> NewEraDarkColorScheme
        else -> NewEraLightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = NewEraTypography,
        shapes = NewEraShapes,
        content = content
    )
}
